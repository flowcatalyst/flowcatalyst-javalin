# FlowCatalyst Java SDK

Plain-Java client SDK for the FlowCatalyst platform. Java 25+, blocking API
(scale with virtual threads), Jackson as the only runtime dependency. Mirrors
the TypeScript SDK's surface: control-plane resources, transactional outbox,
declaration sync, and webhook signature verification.

```java
var client = FlowCatalystClient.builder()
        .baseUrl("https://your-instance.flowcatalyst.io")
        .clientCredentials("oac_your_client_id", "your_client_secret")
        .build();

var eventTypes = client.eventTypes().list(null);
```

This SDK lives in the `flowcatalyst-javalin` reactor as the `sdk` module
(`io.flowcatalyst:flowcatalyst-sdk`). It depends on the in-repo
`flowcatalyst-usecase` module, which provides the TSID primitives
(`io.flowcatalyst.sdk.tsid.Tsid`, platform Go bit layout) and the use-case
envelope shared with the platform.

## Modules

| Package | What it does |
|---|---|
| `io.flowcatalyst.sdk` | `FlowCatalystClient` — builder config, resource accessors |
| `…sdk.resources` | 15 typed resource families (event types, subscriptions, dispatch pools, connections, roles, permissions, applications, clients, principals, processes, scheduled jobs, audit logs, me, router) |
| `…sdk.error` | `sealed interface SdkError` + `FlowCatalystException` — handle failures with a pattern-matching `switch` |
| `…sdk.outbox` | Transactional outbox: `OutboxManager`, DTO builders, `OutboxDriver` SPI + `JdbcOutboxDriver`, raw SQL migrations in `migrations/` |
| `…sdk.tsid` | TSID generation (13-char Crockford Base32, platform-compatible; collision-free monotonic sequence) — provided by the `flowcatalyst-usecase` module |
| `…sdk.sync` | `DefinitionSynchronizer` + `DefinitionSet` — bulk-sync roles / event types / connections / subscriptions / dispatch pools / principals / processes / scheduled jobs / OpenAPI per application, optionally scoped to one client (`forClient`) and merged across sets (`syncGrouped`) |
| `…sdk.annotations` | `@AsEventType` / `@AsSubscription` (now with `connectionCode` / `sharedConnection`) / `@AsDispatchPool` / `@AsRole` + `DefinitionScanner` (explicit class registration — no classpath scanning; connections have no annotation yet — declare them with `Definitions.Connection` directly) |
| `…sdk.webhook` | `WebhookSignature.verify(...)` — HMAC-SHA256 verification of signed deliveries |

## Auth modes

- **Client credentials** (service account): `.clientCredentials(id, secret)` —
  tokens are cached with a 60s expiry buffer, refreshed single-flight, and
  transparently refreshed once on a 401.
- **User token**: `.accessToken(String)` or `.accessToken(Supplier<String>)` —
  the host app owns refresh; 401s are surfaced, not retried.

Transient statuses (408/429/502/503/504) retry with exponential backoff
(default 3 attempts, 100ms base delay).

## Error handling

Every failure is a `FlowCatalystException` carrying one `SdkError` variant:

```java
try {
    client.eventTypes().get(id);
} catch (FlowCatalystException e) {
    switch (e.error()) {
        case SdkError.NotFound nf -> handleMissing();
        case SdkError.RateLimited rl -> backOff(rl.retryAfter());
        case SdkError.Validation v -> log(v.errors());
        default -> throw e;
    }
}
```

## Transactional outbox

Events are not published over HTTP — they are written to your database's
`outbox_messages` table inside your own transaction (migrations in
`migrations/postgresql` and `migrations/mysql`), and the outbox poller ships
them:

```java
var driver = new JdbcOutboxDriver(dataSource);
var outbox = new OutboxManager(driver, "clt_your_client_tsid");

driver.withTransaction(tx -> {
    // business writes on (Connection) tx ...
    outbox.createEvent(CreateEventDto
            .create("orders:sales:order:placed", Map.of("orderId", orderId))
            .withMessageGroup(orderId), tx);
    return null;
});
```

Event `type`s and dispatch-job `code`s must be fully qualified
`application:subdomain:aggregate:action` strings — the SDK rejects bare codes.

## Creating users and invitations

`principals().createUser(...)` accepts two optional flags that control who
sends the "set your password" mail. Prefer pattern 1 when you want the user
to land back inside your own application.

**1. Login-detected (recommended).** Create the user with `sendInvitation:
false` and send your own email that links to your application. The platform
detects the passwordless account at the hosted login and handles password
creation itself, then returns the user to your stored OAuth redirect.

```java
PrincipalResponse user = client.principals().createUser(new CreateUserRequest()
        .email("new.user@example.com")
        .name("New User")
        .sendInvitation(false));
// Send your own "welcome" email now, linking to your app.
```

**2. Embedded link.** Create the user with `returnInviteLink: true` and read
`inviteLink` from the response to embed in your own email. The user sets a
password on the platform and lands on the platform's own landing page.
`returnInviteLink: true` always suppresses the platform's own invite email,
even when `sendInvitation` is left at its default — the token can only be
minted once, so asking for the link back means you are taking over delivery.

```java
PrincipalResponse user = client.principals().createUser(new CreateUserRequest()
        .email("new.user@example.com")
        .name("New User")
        .returnInviteLink(true));
String inviteLink = user.getInviteLink();
// Embed inviteLink in your own email; never log it or store it in plaintext —
// it is a live 72-hour bearer credential, exactly like a password.
```

## Declaring definitions

Programmatically:

```java
var set = Definitions.DefinitionSet.define("orders")
        .withEventTypes(List.of(
                Definitions.EventType.of("orders:sales:order:placed", "Order Placed")))
        .withRoles(List.of(Definitions.Role.of("admin")
                .withPermissions(List.of(Definitions.Permission.of("admin", "*", "*")))));

client.definitions().sync(set, SyncOptions.removingUnlisted());
```

Or with annotations and explicit registration:

```java
@AsEventType(code = "orders:sales:order:placed", name = "Order Placed")
public record OrderPlaced(String orderId) {}

var set = DefinitionScanner.scan("orders", List.of(OrderPlaced.class));
client.definitions().sync(set);
```

### Connections and per-client sync

A connection is application-owned — the platform assigns its own service
account, so a `Definitions.Connection` carries no credentials, only
`code`/`name`/`description`/`externalId`. Reference it from a subscription by
`connectionCode` rather than `connectionId`: codes are stable across
environments, ids are not. Connections sync BEFORE subscriptions in the same
set, so a subscription may name a connection the same sync just created.

```java
var set = Definitions.DefinitionSet.define("orders")
        .withConnections(List.of(Definitions.Connection.of("billing-hook", "Billing Webhook")))
        .withSubscriptions(List.of(Definitions.Subscription.of(
                        "order-placed", "Order Placed", "https://billing.example.com/hook",
                        List.of(Definitions.SubscriptionEventType.of("orders:sales:order:placed")))
                .withConnectionCode("billing-hook")));

client.definitions().sync(set, SyncOptions.removingUnlisted());
```

A bare `connectionCode` names a connection owned by the SAME application; add
`.withSharedConnection(true)` to name a shared (application-less) connection
instead — the two namespaces never fall back to one another.

To scope a whole set of connections/subscriptions to one client (tenant),
call `.forClient(clientIdOrIdentifier)` on the `DefinitionSet`. Sync several
sets that may share an application (and possibly a client) with
`syncGrouped(sets)` rather than `syncAll(sets, options)`: `syncGrouped` MERGES
every set sharing an application code before issuing one platform call per
`(application, client)` scope — global scope first, then each client scope in
first-seen order — so two sets contributing to the SAME scope never become
two separate `removeUnlisted` calls, the second of which would delete what
the first just created.

```java
var acme = Definitions.DefinitionSet.define("orders").forClient("acme")
        .withConnections(List.of(Definitions.Connection.of("hook", "Acme Hook")));
var beta = Definitions.DefinitionSet.define("orders").forClient("beta")
        .withConnections(List.of(Definitions.Connection.of("hook", "Beta Hook")));

Map<String, SyncResult> results = client.definitions().syncGrouped(List.of(acme, beta));
```

`sync`, `syncAll` and `syncGrouped` all throw `DefinitionSyncException` (a
`FlowCatalystException` carrying `SdkError.PartialFailure`) if any category —
most commonly connections or subscriptions — could not be synced: a
duplicate code within one scope, or an HTTP failure. A connection sync
failure skips that SAME scope's subscription sync (their `connectionCode`s
may not resolve) without stopping any *other* scope or application from
being attempted; `syncAll`/`syncGrouped` run every set/application to
completion before throwing once, so a caller sees every failure at once
rather than stopping at the first. The exception carries whatever DID sync —
`result()` (`sync`), `results()` (`syncAll`) or `resultsByApplication()`
(`syncGrouped`) — so a deploy step can log the partial outcome instead of
silently treating "no exception" as success:

```java
try {
    client.definitions().sync(set, SyncOptions.removingUnlisted());
} catch (DefinitionSyncException e) {
    log.error("definition sync had failures: {}", e.getMessage());
    if (e.result().connections() instanceof SyncResult.Category.Synced synced) {
        log.info("connections DID sync: {} created", synced.created());
    }
    throw e;
}
```

## Webhook verification

```java
WebhookSignature.verify(rawBodyBytes,
        request.header("X-FlowCatalyst-Signature"),
        request.header("X-FlowCatalyst-Timestamp"),
        signingSecret);
```

The body must be the raw bytes as received (sign-then-parse).

## Building

```
make build-java-sdk        # from the repo root: regenerates models + verify
```

Models under `io.flowcatalyst.sdk.generated.model` are generated at build time
from `openapi/openapi.json` (refreshed by `make sdk-spec`) — wire drift
surfaces as compile errors in the hand-written resource layer.

Examples live in `src/test/java/examples/`; run with:

```
mvn -q test-compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java \
    -Dexec.mainClass=examples.ListEventTypes -Dexec.classpathScope=test
```

Releases: `make release-java-sdk BUMP=minor` (tags `java-sdk/vX.Y.Z`).
