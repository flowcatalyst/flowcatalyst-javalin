# Agent prompt templates — the spec → implement → audit pipeline

These are the prompts used to port units with fresh agents, so a new
session can run the same pipeline identically. Substitute `<aggregate>`,
`<Aggregate>`, `<path-prefix>`, `<Go package>`. Always:
JAVA_HOME=/Users/andrewgraaff/.local/share/mise/installs/graalvm/25.0.1,
Maven from the repo root, `-pl server` without `-am` (other modules are in
`~/.m2`; `mvn -q -B -N install` puts the parent pom there,
`mvn -q -B -pl usecase,sdk install` the libraries), never edit poms or
anything under `../flowcatalyst-go`, and "if compilation breaks in a file
you didn't write, wait 30 s and retry — another agent is mid-edit".

---

## 1. Spec + port (one agent per aggregate, up to three in parallel)

> You are porting the `<aggregate>` aggregate of
> /Users/andrewgraaff/Developer/flowcatalyst-go into
> /Users/andrewgraaff/Developer/flowcatalyst-javalin, module `server`,
> following CONVENTIONS.md §2, §3, §8 EXACTLY (read CONVENTIONS.md,
> docs/usecase-envelope.md, docs/spec/eventtype.md and the whole
> `server/src/main/java/io/flowcatalyst/platform/eventtype/**` + its tests
> first — eventtype is the TEMPLATE: entity with intent-named transitions,
> parser records for formatted values, `operations/Access.loadScoped`,
> `Events.of(ec, entity…)` factories, repository shape with `ListFilter`,
> Api with `Request.toCommand()`/`Response.from()`, 4-line handlers,
> `@ParameterizedTest` validation tables, `TestHttp` API tests).
> Run tests with `mvn -q -B -pl server -Dtest='<Aggregate>*' -Dsurefire.failIfNoSpecifiedTests=false test`.
> You own `io.flowcatalyst.platform.<aggregate>.**`,
> `docs/spec/<aggregate>.md`, and ONE line-block in
> `io.flowcatalyst.server.Platform.register` (add after the existing
> registrations; don't reorder).
>
> Process (§8):
> 1. Spec first: `docs/spec/<aggregate>.md` from the CONTRACT — the
>    lockfile operations under `<path-prefix>` (server/src/main/resources/openapi/openapi.lock.json:
>    paths, request/response schemas, status codes) plus behaviour
>    extracted from Go `internal/platform/<aggregate>/{entity.go,repository.go,operations/*.go,api/*.go}`
>    and its tests (validation rules, authorization placement, state
>    machine, error codes, domain events type/subject/messageGroup/data,
>    persistence rules; `Sync*` operations are ported but sdksync/bff
>    routes are not wired) — written as behaviour/tables, not code; flag
>    "load-bearing or accident?" questions for the owner.
> 2. Implement the spec in Java as if Go never existed, in the eventtype
>    template shape: entity record + transitions, repository (jOOQ over
>    `io.flowcatalyst.db.generated.Tables.*`), `operations/<Aggregate>Events.java`,
>    one operation per file with command records named like Go's,
>    `api/<Aggregate>Api` with DTO records from the lockfile, registration
>    in `Platform`, tests (`<Aggregate>Test` no-DB transitions,
>    `<Aggregate>OperationsTest` via the envelope asserting `msg_events` +
>    `aud_logs`, `<Aggregate>ApiTest` via `TestHttp` with test headers).
> 3. Self-check against the spec and §8 (a separate audit agent follows):
>    records, enums with `parse`, exhaustive switches, `Optional` returns,
>    no empty-string sentinels inside the JVM,
>    `UseCaseException.resourceNotFound` / `requireNonBlank`,
>    `Checks.checkApplicationAccess` for app-scoped sync, defaults applied
>    in the domain, AssertJ sentence-named tests.
> Run your tests, then `LockfileCoverageTest` (your routes present, no
> drift), then the full server suite once.
>
> Report: spec path + owner questions, files, routes table with status
> codes, operations + authorization placement, deviations, test counts.

## 2. Audit (one agent per landed aggregate)

> You are the step-3 auditor (CONVENTIONS.md §8 — read it, §2/§3 template
> rules included) for the freshly ported `<aggregate>` aggregate. Scope:
> ONLY `server/src/main/java/io/flowcatalyst/platform/<aggregate>/**`, its
> tests, and `docs/spec/<aggregate>.md`. Audit the Java as a Java program
> against `docs/spec/<aggregate>.md` and the §8 checklist — NOT against the
> Go. Compare with the template `io.flowcatalyst.platform.eventtype.**`
> and align shapes where they diverge without reason (entity/transitions,
> parser record, `Access.loadScoped`, `Events.of`, repository `ListFilter`
> / `fetchOptional` / one `DSLContext` / upsert listing each column once,
> Api `Request.toCommand()`/`Response.from()` / 4-line handlers /
> `Auth.scoped`, tests `@ParameterizedTest` / AssertJ sentences / no
> table-wide counts). Check records / sealed / enums-with-parse / exhaustive
> switch, `Optional` only as return, no `""` sentinels in the JVM,
> `resourceNotFound` / `requireNonBlank` / `checkApplicationAccess` used,
> no Go-isms, `-Xlint:all` clean, spec ↔ code agreement (fix whichever is
> wrong; never change wire shapes / status codes / error codes, which the
> tests pin). Apply fixes, rerun the tests and `LockfileCoverageTest`.
>
> Report: findings table (file, issue, change / kept + why), any new
> template-worthy rule (exact wording for CONVENTIONS.md), design smells
> (describe, don't refactor), test results.

Recurring audit findings are promoted into `CONVENTIONS.md` ("Rules
promoted from audits"); design smells go to `docs/backlog.md`.

## 3. Subsystem spec (data plane / auth — step 1 only, reviewed before code)

> You are doing SEMANTIC EXTRACTION (CONVENTIONS.md §8 step 1) for
> `<subsystem>` of /Users/andrewgraaff/Developer/flowcatalyst-go. Produce
> a behavioural specification, NOT code, at `docs/spec/<subsystem>.md`,
> citing Go file:line for every fact. Sections: purpose & boundaries;
> message/data model (every field, JSON name, optionality); topology &
> concurrency as behaviour (invariants as sentences; a separate column for
> what Go does because of Go); state machines (tables); ONE table of every
> timing/sizing constant with "load-bearing or accident?" + evidence; wire
> contracts (headers, signing, status → outcome tables, golden vectors);
> backend/store contracts; config; observability (every route, metric);
> HA/leadership; shutdown; edge cases mined from tests (each with its
> test); open questions for the owner as yes/no decisions. Mark contracts
> [C] vs internal mechanics [I]. Do not propose the Java design.

The owner reviews the spec (chat, artifact comments, or `**Ruling:**`
lines in the file); rulings become spec lines + conformance tests. Only
then: implement as if Go never existed against the §8 idiom checklist
(sealed taxonomies, one queue per stage, interruption, StructuredTaskScope
for scatter-gather, named retry policies instead of panic recovery, sealed
outcomes vs exceptions, records, JFR events), then audit Java-vs-spec.
