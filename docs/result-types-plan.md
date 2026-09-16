# Result types instead of `UseCaseException` — assessment and plan

Status: proposal, 2026-09-16. Not adopted. Written in answer to the owner's
question "should we move to result types and remove the exceptions, and would
our tests stay valid?"

## 0. Where the code stands today

Measured on `main` at `c4798f79`:

| What | Count |
|---|---|
| `UseCaseException.<factory>(…)` call sites in production code | 502, across 234 files (158 under `operations/`, 11 under `api/`, 5 under `shared/`) |
| Places that **catch** `UseCaseException` in production code | 12 (three `Sync*` operations, `PrincipalApi` ×3, `PasswordResetApi`, `PortalAuthApi`, `PortalSso`, `OidcBridgeApi`, `SendPasswordReset`, `CronExpression`) |
| Where it is turned into HTTP | one place: `HttpError.install` |
| Use cases on the `Operation` envelope / on `TxOperation` | 132 / 11 |
| Sealed outcome types already returned and switched on | 20+ (`FetchOutcome`, `Attempt`, `Verification`, `PollResult`, `MediationOutcome`, `Verdict`, …) |
| Test files touching `UseCaseException` | 47 (39 of them through a private `assertUseCaseError(call, kind, code)` helper; 59 inline `isInstanceOf(UseCaseException)`) |
| Wire-level `*ApiTest` files (assert status + body, never the exception) | 40 |
| `assertThatThrownBy` in tests, all types | 471 — the majority pin constructor invariants (`IllegalArgumentException` 47, `NullPointerException` 28, `IllegalStateException` 25), which are programming errors and stay exceptions under any plan |

Two structural facts matter more than the counts:

1. **The envelope already defers writes.** `Operation.run` runs Validate →
   Authorize → Execute *outside* any transaction and only then applies the
   returned `Plan` in one transaction. An expected failure (validation,
   authorization, not-found, conflict) therefore never has anything to roll
   back. Only the 11 `TxOperation` use cases write inside the transaction and
   rely on throw-to-rollback for a mid-transaction refusal.
2. **`UseCaseException` is caught in one place by design** (`HttpError.install`),
   plus 12 in-code catches. The 12 are the only places where an exception is
   used as control flow; everything else is "throw at the leaf, map at the
   edge", which is the disciplined form of the pattern.

CONVENTIONS §8 already rules the middle ground: expected outcomes at
verification boundaries are sealed result types; genuinely exceptional
conditions are exceptions; "not Either-everywhere, not
exceptions-for-control-flow".

## 1. Assessment

**Agree in principle for the use-case layer, disagree for the handler layer,
and disagree on doing it now.**

Where a `Result` genuinely improves the code:

- **The use-case envelope.** `UseCaseError` is already a sealed type; carrying
  it inside an unchecked exception is the one place the codebase smuggles a
  domain value through the exception channel. `Operation.run → Result<E>`
  makes "this call can refuse" visible in the signature and lets the compiler
  check that every caller handles it. Because the envelope defers writes, the
  change is mostly mechanical.
- **The 12 in-code catches.** Each is a caller that *wants* to continue after
  a refusal (a sync that records per-item outcomes, an SSO flow that falls
  back). These are exactly the cases the convention says should be sealed
  outcomes, and they can be converted one at a time today without any
  framework change.

Where it makes the code worse:

- **HTTP handlers and the `Checks`/`Access` gates** (35 `*Api` classes, 369
  gate call sites). Java has no `?` operator. A handler body that today reads
  as five straight-line statements becomes nested `switch`es or a chain of
  early returns per gate, and every handler must remember to write the error
  envelope that `HttpError.install` writes once today. The compiler-checked
  benefit is small here: the wire contract is already pinned by 40 `*ApiTest`
  classes, the 1,326-step parity corpus and the e2e suite, which is a
  stronger guarantee than the type system gives.
- **Infrastructure failures** (`SQLException`, I/O, JSON parse, interruption).
  These are exceptional, are not part of any caller's decision, and the
  convention already keeps them as exceptions. Wrapping them in `Result`
  would be Either-everywhere.

On timing: the Java platform is at the point of replacing Go in production.
A 500-site refactor of the error channel is the wrong thing to ship alongside
a cutover, even with parity as the gate. The plan below is for **after** the
cutover has settled; §4 lists what is worth doing now.

## 2. Would the tests remain valid?

Yes in substance; about one file in eight needs a mechanical edit at the
assertion site.

| Test group | Effect |
|---|---|
| 40 wire-level `*ApiTest` classes | Unchanged and still valid. They assert status and body, not the exception. These are the tests that pin the behaviour the platform's users see. |
| Parity corpus (`parity/`), e2e (`e2e/`) | Unchanged. They are the real safety net for the refactor. |
| 39 test classes using the `assertUseCaseError` helper | Only the helper body changes (from `assertThatThrownBy(call).isInstanceOf(UseCaseException)` to switching on the returned `Result`); callers stay as they are. |
| 59 inline `isInstanceOf(UseCaseException)` assertions | Rewritten one by one to assert on the `Err` variant. Mechanical. |
| ~100 constructor-invariant exception tests | Unchanged; those exceptions stay. |
| The mutant discipline (CLAUDE.md) | Still applies unchanged: a converted test must fail when the refusal is removed, exactly as before. The refactor must not be allowed to turn a "throws with code X" assertion into "returns something". |

## 3. The plan (after cutover)

Phase 0 — rulings before any code:
- Scope is the use-case envelope only: `Operation`, `TxOperation`, the
  operations packages, and the 12 in-code catches. Handlers keep throwing
  into `HttpError.install`; infrastructure keeps exceptions.
- Shape: a project-owned `sealed interface Result<T> permits Ok<T>, Err`
  with `Err(UseCaseError)`; no third-party Either. `Result.orThrow()` and
  `Result.catching(Supplier)` as the bridge during migration.

Phase 1 — envelope (module `usecase`):
- `Validate` and `Authorize` return `Optional<UseCaseError>`; `Execute`
  returns `Result<Plan<E>>`; `Operation.run` returns `Result<E>`.
- `TxOperation.run` returns `Result<R>`; an `Err` from Execute rolls the
  transaction back explicitly instead of relying on the throw.
- `UseCaseException` stays as the bridge type: `run` gains a sibling
  `runOrThrow` so unmigrated callers compile unchanged.
- Tests: `OperationTest`/`TxOperationTest` converted first; the mutant for
  each ("Err from Execute still commits") must be killed.

Phase 2 — the 12 in-code catches: convert each to a switch on `Result`.
Small, self-contained, each with its own test.

Phase 3 — operations packages, one aggregate per commit, Sonnet mechanical
under the spec: 158 files. Order by the parity corpus's coverage so every
commit is checked by a parity run. The `assertUseCaseError` helper bodies
change; the 59 inline assertions are rewritten.

Phase 4 — handlers call `run(...)` and `switch` once at the top of the body
(`case Err e -> HttpError.write(ctx, e)`); `Checks`/`Access` keep throwing.
`runOrThrow` and the bridge are deleted. `HttpError.install` remains for the
true 500s.

Gate for every phase: full clean reactor green, parity 0 DIFF against the
same Go commit before and after, e2e Java column green.

Rough size: phases 1–2 a day or two; phase 3 a few days of delegated work
with review; phase 4 a day. The risk is concentrated in phase 1's
`TxOperation` rollback change, which is why it goes first and alone.

## 4. Worth doing now, independently of the plan

- Convert the 12 in-code `catch (UseCaseException)` sites to sealed
  outcomes. They are the convention's actual target and cost nothing
  structurally.
- Keep applying "outcomes, not exceptions, at verification boundaries" to
  new code, as the router's `Attempt` / `FetchOutcome` do.
