# Brief — application provisioning: the last two lockfile operations

Orchestrator: Fable. Coder: Sonnet, medium effort, own worktree. Two routes
on the application aggregate that `docs/spec/application.md` §10 deferred
until the OAuth-client, service-account and principal units existed. They
all exist now. After this unit `LockfileCoverageTest` reports 245/245 and
its `REQUIRED_COVERAGE` goes to `1.0`.

## Authority

`docs/spec/application.md` §10 (behaviour), §8 (the event
`platform:iam:application:service-account-provisioned` already listed),
§11 questions 2, 3 and 8 (keep Go's behaviour, they are recorded). The
lockfile is the wire contract: `provisionApplicationServiceAccount`
(no body → 201 `ApplicationProvisionServiceAccountResponse`) and
`provisionApplicationLoginClient` (`ProvisionLoginClientRequest` → 201
`ApplicationProvisionLoginClientResponse`); read both schemas in
`server/src/main/resources/openapi/openapi.lock.json` and mirror the
member names exactly.

## Files you own

```
server/src/main/java/io/flowcatalyst/platform/application/operations/{ProvisionServiceAccount, ProvisionServiceAccountCommand}.java
server/src/main/java/io/flowcatalyst/platform/application/api/ApplicationApi.java   (two handlers + two response records; nothing else changes)
server/src/main/java/io/flowcatalyst/platform/oauthclient/OAuthClientRepository.java (one read, see §hasLoginClient)
server/src/test/java/io/flowcatalyst/platform/application/**                        (extend the existing tests)
server/src/test/java/io/flowcatalyst/server/LockfileCoverageTest.java                (REQUIRED_COVERAGE = 1.0 — the only change)
```

`Platform.java`: only the `ApplicationApi.State` construction gains the
repositories the handlers need (service accounts, principals, OAuth
clients, `Optional<Encryption>`); do not touch any other line. Note the
OAuth-client repository is currently constructed ~90 lines *after*
`ApplicationApi.register` — the repositories are stateless over the pool,
so construct the instances the State needs right where the State is built
rather than reordering the file.

## `POST /api/applications/{id}/provision-service-account`

Anchor only (`Checks.requireAnchor`). A `TxOperation` — template
`serviceaccount.operations.CreateServiceAccountWithCredentials`, which
already shows how a service account and its `SERVICE` principal are written
in one transaction. In order, all inside the one transaction, each aggregate
through the scoped commit helper with its own event:

1. Load the application; unknown → 404 `Application`; `serviceAccountId`
   already set → 409 `ALREADY_PROVISIONED` "Application already has a
   service account provisioned".
2. Service account: code `app:<application code>`, name
   `<application name> Service Account`, description
   `Service account for application: <name>`, `applicationId` = the app,
   generated webhook credentials (`WebhookSecrets`, as the create op does).
   Event: the service-account created event the create op emits.
3. Its `SERVICE` principal (`Principal.newService(sa.id(), name)`) with
   `allApplications = false`, accessible application ids `[app]`, and one
   role `platform:application-service` with assignment source `PROVISIONED`
   — persisted with its role and application-access junction rows the way
   the principal repository does it for the create op.
4. The application: `serviceAccountId` = the **principal** id (spec §1 —
   the FK points at `iam_principals`), `updatedAt` now. Event
   `service-account-provisioned` with `serviceAccountId` = the
   service-account id (spec §11 q8: kept as Go has it, recorded).
5. A `CONFIDENTIAL` OAuth client: `clientId` a fresh `EntityType.OAUTH_CLIENT`
   id, name `<application name> Service Account Client`, `principalId` =
   the service principal, grant types `client_credentials`,
   `refresh_token`, scopes `openid`, `applicationIds` `[app]`, secret from
   `oauthclient.operations.Secrets` (`generatePlaintext` +
   `encryptedRef`; no app key → the internal `SECRET` error, which rolls
   everything back). Event: `OAuthClientCreated`.

Result → 201 `{message: "Service account provisioned", serviceAccount:
{principalId, name, oauthClient: {id, clientId, clientSecret}}}` with the
plaintext secret exactly once.

## `POST /api/applications/{id}/provision-login-client`

Anchor only. Not a new operation: validate `redirectUris` non-empty
(400 `REDIRECT_URIS_REQUIRED` "At least one redirect URI is required"),
load the application (404), then run the existing
`oauthclient.operations.CreateOAuthClient` with: `clientId` fresh,
`clientName` `<application name> Login`, `clientType` `CONFIDENTIAL` only
when the body says so, else `PUBLIC` (PKCE required follows from the type,
as the create op already does), `redirectUris` from the body,
`allowedOrigins` from the body (**deliberate deviation**: Go declares the
field on the request and never reads it — a Go defect; Java stores it;
record in `docs/backlog.md` under the application section), grant types
`authorization_code`, `refresh_token`, scopes `openid profile email`,
`applicationIds` `[app]`. The `disclose` consumer captures the plaintext
for a `CONFIDENTIAL` client; `PUBLIC` has no secret (`clientSecret`
absent or empty exactly as the lockfile schema allows — check `required`).

Response 201 `{message: "Login client provisioned", loginClient:
{clientType, redirectUris, oauthClient: {id, clientId, clientSecret}}}`.

## `hasLoginClient`

Spec §11 q3 says it is always `false` because nothing could answer it.
Now something can: add `OAuthClientRepository.hasLoginClientFor(applicationId)`
— exists an active client linked to the application whose grant types
contain `authorization_code` — and use it where the application response
is built. Ruling: implemented (the SPA renders its "provision login
client" form on it, spec line ~92); note the change in §11 q3 as done.

## Tests you owe (CLAUDE.md: works, not exists)

- Provision service account: after 201, the service account row, the
  principal row with its role junction and application-access row, the
  application's `serviceAccountId` (principal id), and the OAuth client
  row with `principal_id` all exist; the response secret authenticates —
  `POST /oauth/token` `client_credentials` with the returned `clientId` /
  `clientSecret` mints a token (the `OAuthProviderTest` shows how the
  provider is driven; if wiring it in the same test server is too heavy,
  assert instead that the stored secret ref decrypts to the returned
  plaintext through `Encryption`).
- Second provision → 409 `ALREADY_PROVISIONED`, and **no** new service
  account / principal / client rows (count before and after).
- Atomicity: with no app key configured the call fails and none of the
  four rows exist afterwards.
- Login client: `PUBLIC` default has `pkce_required` true and no secret;
  `CONFIDENTIAL` returns a secret once; `redirectUris` empty → 400;
  unknown app → 404; non-anchor → 403; `hasLoginClient` flips to `true`
  on the application response afterwards.
- Mutants to run and report: (1) drop the `ALREADY_PROVISIONED` check;
  (2) store the service-account id instead of the principal id on the
  application; (3) drop the role assignment on the service principal;
  (4) make the login client `CONFIDENTIAL` regardless of the body. Each
  must be killed by a named test.

## Build

`export JAVA_HOME=$(mise where java)`; `mvn -q -pl server test -Dtest=…
-Dsurefire.timeout=600`; never `mvn install`; `-Werror` on; Jackson 3
(`tools.jackson`, `asString()`). Full `mvn -q -pl server clean test`
before you report. Commit on your worktree branch; do not merge.

## Report

Files; each test and the behaviour it pins; the four mutants and their
killers; the lockfile coverage line; anything underspecified (`// SPEC?`);
an honest verdict.
