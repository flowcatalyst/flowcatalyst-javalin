# The use-case envelope and the audit log — Java interpretation

This is the Java reading of `pkg/fcsdk/{usecase,usecaseop,usecasepgx}` and
`internal/platform/shared/platformsink` from `flowcatalyst-go`. Read the Go
`docs/usecase-envelope-migration-handover.md` for the history; this file is
about the *intent* and how it maps onto Java 25.

Code: `usecase/src/main/java/io/flowcatalyst/sdk/usecase/**` (envelope),
`server/src/main/java/io/flowcatalyst/platform/shared/platformsink/PlatformSink.java`
(the platform's sink).

---

## 1. What the envelope is for

Every state-changing business operation — "create event type", "pause
subscription", "sync roles" — is a **use case**: one domain operation, not
"one write". It may persist several aggregates and emit several events, but it
is *one* decision by *one* principal, and the platform wants three things to be
true about it **by construction, not by review**:

1. **The phases always run, in order.**
   `Validate` (command shape, pure) → `Authorize` (may *this* principal act on
   *this* resource?) → `Execute` (load, check invariants, build the change).
   An operation can leave a phase empty; it cannot skip one.

2. **Authorization is never silently absent.** A use case that is intentionally
   open says so — `Authorize.publicAccess()` — in a way you can grep. The
   coarse permission check ("may create event types at all") stays in the HTTP
   handler; the *resource-level* check ("within this client?") is the use
   case's job, because the use case is reached from several entry points
   (admin API, BFF, SDK sync, login-JIT) and each entry point keeps its own gate.

3. **Aggregate written ⇒ domain event written ⇒ audit row written, in ONE
   transaction.** No code path can persist an aggregate without the event and
   the audit. `Execute` returns a `Plan` — a *description* of the change — and
   the driver applies it. `Execute` never sees the unit of work, so it cannot
   write anything itself.

The domain event is the platform's product (it is what subscribers receive,
what projections are built from, what fan-out matches). The audit row is the
platform's memory of *who asked for what*: it stores the **command** (the
input DTO, as JSON) keyed by the aggregate the event names. Event = what
happened; audit = what was requested and by whom. They are written together
because a history with one but not the other is a history you cannot trust.

## 2. Go → Java, piece by piece

| Go | Java | Note |
|---|---|---|
| `usecaseop.Operation[C,E]{Name, Validate, Authorize, Execute}` | `record Operation<C, E extends DomainEvent>(name, validate, authorize, execute)` + staged builder `Operation.<C,E>named("X").validate(..).authorize(..).execute(..)` | The builder's stages make "authorize before execute, both present" a compile-time fact; the record constructor also refuses a null `authorize`/`execute`. This replaces the Go `uowseal` analyzer. |
| `usecaseop.Run(ctx, uow, op, cmd, ec)` | `op.run(uow, cmd, ec)` | Java allows generic instance methods; Go's free function was a language limitation. |
| `usecaseop.Plan[E]` (unexported `apply` = the seal) + `Save/Delete/Emit/SaveAll/Sync` | `sealed interface Plan<E>` with five nested records and static factories `Plan.save(..)` etc. | `PlanApplier` (package-private) is the only consumer, via an exhaustive `switch`. |
| `usecaseop.Public[C]` | `Operation.Authorize.publicAccess()` | Same meaning: deliberately open. |
| `TxOperation[C,R]` + `RunTx` | `record TxOperation<C,R>` + `op.run(uow, cmd, ec)`; `Execute` receives a `TxScopedUnitOfWork` | For multi-aggregate orchestrations returning a custom result. |
| `usecase.Error{Kind, Code, Message, Details, Cause}` + `HTTPStatus()` | `sealed interface UseCaseError` with records `Validation / BusinessRule / Authorization / NotFound / Conflict / Internal`; `httpStatus()` and `kind()` are exhaustive switches | Thrown wrapped in the unchecked `UseCaseException`; `UseCaseException.conflict("CODE_EXISTS", …)` where Go wrote `return usecase.Conflict(…)`. |
| `usecase.Result[E]` + `internal/sealed.Token` | **not ported** | It was the Go-only seal on "a Success came from a Commit". The `Plan` seal plus the fact that `Execute` has no unit of work gives the same guarantee; Java exceptions are the failure channel. |
| `usecase.DomainEvent` (12 methods) + `EventMetadata` | `interface DomainEvent { EventMetadata metadata(); Object data(); }` + defaults for the 11 accessors | Events are records; override an accessor only when the value is derived (e.g. `messageGroup()`). `data()` returns a record the sink serialises. |
| `usecase.ExecutionContext` | `record ExecutionContext(principalId, correlationId, causationId, executionId, initiatedAt)` with `of / withCorrelation / fromParentEvent / withCausation` | UUID strings as in Go. |
| `usecase.HasID.IDStr()` | `interface HasId { String id(); }` | Records with an `id` component satisfy it for free. |
| `usecasepgx.UnitOfWork` + `Commit/CommitDelete/EmitEvent/CommitAll/CommitSync` | `UnitOfWork(DataSource, Sink)` with `commit / commitDelete / emitEvent / commitAll / commitSync` | Same error codes: `TX_BEGIN, PERSIST, PERSIST_BATCH, DELETE, DELETE_BATCH, EVENT_WRITE, AUDIT_WRITE, TX_COMMIT`. Same write order for sync: saves → deletes → rollup, one event + one audit per row. |
| `usecasepgx.Run / RunErr` + `TxScopedUnitOfWork` + `CommitScoped/…` | `uow.inTransaction(scoped -> …)`; `scoped.commit / commitDelete / emitEvent / dbTx() / connection()` | Commits when the body returns, rolls back on any throwable, exception propagates unchanged. |
| `usecasepgx.DbTx` (wraps `pgx.Tx`), `WrapTxForBootstrap` | `DbTx` (wraps `java.sql.Connection`), `DbTx.wrapForBootstrap` | Package-private constructor: holding a `DbTx` means "inside a use-case transaction". |
| `usecasepgx.Persist[A]` | `interface Persist<A extends HasId> { persist(A, DbTx); delete(A, DbTx); }` | Implemented on the repository, never the aggregate. jOOQ or plain JDBC inside. |
| `usecasepgx.Sink` | `interface Sink { writeEvent(DbTx, DomainEvent); writeAudit(DbTx, DomainEvent, Object command); }` | |
| `platformsink.Sink` (platform) | `PlatformSink(ObjectMapper)` → `msg_events` + `aud_logs` | Same columns, same `deduplication_id`, same `context_data`, `operation` = command record's simple name. |
| `outboxpgx.Sink` (consumer apps) | `OutboxSink(Config, ObjectMapper)` → `outbox_messages` | Same snake_case payload, keys sorted, `status 0`, TSID ids. |
| `pkg/fcsdk/tsid` (42/10/12 layout, Unix epoch) | `io.flowcatalyst.sdk.tsid.Tsid` | Note: the Java SDK in the Go repo uses a 42/22 layout with a 2020 epoch; the platform port uses the Go layout so ids stay time-sortable next to Go-issued ones. |
| `internal/tsid.EntityType` (45 prefixes) | `io.flowcatalyst.platform.shared.tsid.EntityType` enum | `EntityType.AUDIT_LOG.generate()` → `aud_…`. |

### What the audit row contains

| `aud_logs` column | Value | Java source |
|---|---|---|
| `id` | `aud_{13-char TSID}` | `EntityType.AUDIT_LOG.generate()` |
| `entity_type` | second segment of the event subject, capitalised (`platform.eventtype.evt_1` → `Eventtype`) | `EventConventions.extractAggregateType(event.subject())` |
| `entity_id` | third segment of the subject | `EventConventions.extractEntityId(...)` |
| `operation` | the command's type name — `CreateCommand`, `UpdateCommand`, `SyncRolesCommand`… | `SinkSupport.commandName(command)` → **name your command records exactly as the Go commands are named** |
| `operation_json` | the command serialised as JSON | `ObjectMapper.writeValueAsString(command)` |
| `principal_id` | the acting principal (NULL when none) | `event.principalId()` |
| `application_id`, `client_id` | NULL today (Go writes NULL too) | — |
| `performed_at` | the event's time | `event.time()` |

So: the audit log is **command-centric** (what was asked), the event is
**outcome-centric** (what is now true), and the subject of the event is the
key that ties them to the aggregate.

## 3. A worked example — `CreateEventType`

The Go original is `internal/platform/eventtype/operations/create.go`. The Java
version has exactly the same three phases and the same error codes.

```java
// platform/eventtype/operations/CreateCommand.java
public record CreateCommand(String code, String name, String description, String clientId, JsonNode schema) {}

// platform/eventtype/operations/EventTypeEvents.java — one file for the aggregate's events
public final class EventTypeEvents {
    public static final String SOURCE  = "platform:admin";
    public static final String CREATED = "platform:admin:eventtype:created";
    public static String subjectFor(String id) { return "platform.eventtype." + id; }

    public record EventTypeCreated(EventMetadata metadata, String eventTypeId, String code, String name,
                                   String description, String application, String subdomain, String aggregate,
                                   String eventName, String clientId) implements DomainEvent {
        @Override public Object data() {
            return new Data(eventTypeId, code, name, description, application, subdomain, aggregate, eventName, clientId);
        }
        private record Data(String eventTypeId, String code, String name, String description, String application,
                            String subdomain, String aggregate, String eventName, String clientId) {}
    }
}

// platform/eventtype/operations/CreateEventType.java — one operation per file
public final class CreateEventType {
    public static Operation<CreateCommand, EventTypeCreated> of(EventTypeRepository repo) {
        return Operation.<CreateCommand, EventTypeCreated>named("CreateEventType")
            .validate(cmd -> {
                if (cmd.code() == null || cmd.code().isBlank())
                    throw UseCaseException.validation("CODE_REQUIRED", "Event type code is required");
                if (cmd.name() == null || cmd.name().isBlank())
                    throw UseCaseException.validation("NAME_REQUIRED", "Event type name is required");
                String[] parts = cmd.code().split(":", -1);
                if (parts.length != 4)
                    throw UseCaseException.validation("INVALID_CODE_FORMAT",
                        "Event type code must follow format: application:subdomain:aggregate:event");
                String[] names = {"application", "subdomain", "aggregate", "event"};
                for (int i = 0; i < 4; i++)
                    if (parts[i].isBlank())
                        throw UseCaseException.validation("INVALID_CODE_FORMAT",
                            "Event type code part '" + names[i] + "' cannot be empty");
            })
            // Resource-level check: a non-anchor principal may only create within a client it can
            // access; anchor-level (null clientId) creates are anchor-only. The coarse
            // "may write event types" permission was checked by the handler.
            .authorize(cmd -> Auth.checkScopeAccess(Auth.current(), cmd.clientId()))
            .execute((cmd, ec) -> {
                if (repo.findByCode(cmd.code()).isPresent())
                    throw UseCaseException.conflict("CODE_EXISTS",
                        "Event type with code '" + cmd.code() + "' already exists");

                EventType et = EventType.create(cmd.code(), cmd.name())      // throws Validation(INVALID_CODE_FORMAT)
                        .withDescription(cmd.description())
                        .withClientId(cmd.clientId())
                        .withCreatedBy(ec.principalId());
                if (cmd.schema() != null)
                    et = et.withSchemaVersion(SpecVersion.initial(et.id(), "1.0", cmd.schema()));

                var event = new EventTypeCreated(
                        EventMetadata.of(ec, EventTypeEvents.CREATED, EventTypeEvents.SOURCE, EventTypeEvents.subjectFor(et.id())),
                        et.id(), et.code(), et.name(), et.description(), et.application(), et.subdomain(),
                        et.aggregate(), et.eventName(), et.clientId());

                return Plan.save(et, repo, event);   // the ONLY way to reach the database
            });
    }
}

// platform/eventtype/api/EventTypeApi.java — the handler does: coarse permission → command → run → response
app.post("/api/event-types", ctx -> {
    Checks.requireAny(Auth.current(), EVENT_TYPE_CREATE, EVENT_TYPE_UPDATE, EVENT_TYPE_DELETE);  // coarse gate, 403 if not
    var cmd = ctx.bodyAsClass(CreateEventTypeRequest.class).toCommand();
    var event = CreateEventType.of(state.repo()).run(state.uow(), cmd, Auth.executionContext());
    ctx.status(201).json(new CreatedResponse(event.eventTypeId()));
});

// and once, centrally:
app.exception(UseCaseException.class, (e, ctx) -> HttpError.write(ctx, e.error()));   // {"error": code, "message", "details"}
```

What the single call to `run` guarantees: `event_types` row upserted,
`msg_events` row with `type = platform:admin:eventtype:created`,
`subject = platform.eventtype.evt_…`, `deduplication_id = type-eventId`, and
`aud_logs` row with `entity_type = Eventtype`, `operation = CreateCommand`,
`operation_json = {"code": …, "name": …}` — or none of them.

## 4. Deliberate deviations from the Go code

- `Result<E>` / `sealed.Token` not ported (see table). `run` returns the event
  or throws.
- Failure is an unchecked `UseCaseException` carrying the sealed
  `UseCaseError`. Go returns `error`; Java lambdas and a single transport-level
  handler are the idiomatic equivalent.
- Authorization data: Go reads `auth.FromContext(ctx)`; the platform will bind
  the authenticated principal in a `ScopedValue` (`Auth.current()`), which the
  `Authorize` lambda reads. The envelope itself stays ignorant of auth, as in Go.
- `HasId.id()` instead of `IDStr()` — Java has no field/method name clash.
- `DomainEvent.data()` returns an object the sink serialises instead of
  `ToDataJSON() []byte`; the shape is the same, the serialiser is owned by the
  platform's one `ObjectMapper`.
- `commitAll` / `commitSync` are not exposed on `TxScopedUnitOfWork` (Go
  doesn't expose scoped variants of them either).
- A `Persist` that throws a `UseCaseException` is still wrapped as an internal
  `PERSIST` error, exactly like Go. Repositories are CRUD; they don't make
  domain decisions.
