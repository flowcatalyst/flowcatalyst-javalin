# Brief: domain errors as a sealed result, and `Committed` only from the unit of work

Owner ruling 2026-09-17: use cases return a result type; they do not throw `UseCaseException`.
Sequencing: after the Vert.x conversion's P1 (handler adapter) lands, because handlers are the
seam that changes. Do not start on the Javalin handlers.

## Why

- The `usecase` module already has a sealed `UseCaseError` with six kinds. Carrying it inside a
  thrown `UseCaseException` makes callers `catch` where they should `switch`, hides the error path
  from readers, and lets a handler forget a case without the compiler noticing.
- The parity work on the Rust port found eleven authorization/session/token defects that no type
  system caught; the pattern below turns two of the underlying rules into compile errors:
  "success means the transaction committed" and "writes happen only inside the transaction".

## Shape

```java
// usecase module
public sealed interface Result<T> permits Result.Committed, Result.Failed {
    record Committed<T>(T value, List<DomainEvent> events) implements Result<T> {
        Committed { /* package-private constructor: only TxScopedUnitOfWork can mint one */ }
    }
    record Failed<T>(UseCaseError error) implements Result<T> {}
}

public interface UnitOfWork extends AutoCloseable {
    TxHandle tx();                                   // the only way to write
    <T> Result<T> commit(T value);                   // mints Committed; runtime guard against reuse
    <T> Result<T> fail(UseCaseError error);
}

// repositories: write methods take the handle, so a handler with no unit of work cannot write
void save(TxHandle tx, Role role);
```

- `Committed`'s constructor is package-private to the unit-of-work package; the sealed hierarchy
  plus visibility does what the Go SDK's mint-only token does, at compile time.
- `TxHandle` is handed out only by the unit of work. Every repository write takes it. Reads may keep
  their current signatures.
- `commit` sets a `committed` flag and throws `IllegalStateException` on a second call or on a
  write after commit; Java cannot express exactly-once in the type, so it is a runtime guard with a
  test.
- Handlers `switch` on the result exhaustively and map `Failed(error)` through the existing
  `HttpError` rendering, so wire shapes (`ErrorModel`, codes, statuses) do not change; the parity
  harness must stay green throughout.

## Migration

1. Add `Result`, `TxHandle`, and the guarded `commit` to the `usecase` module alongside the
   existing types; add a temporary adapter `Results.fromException(UseCaseException)` so a use case
   can be converted one at a time.
2. Convert one aggregate end to end (roles is small and well covered by `roles/crud.json`), then
   the rest by aggregate, deleting each use case's `throw` sites as it converts.
3. Change repository write signatures to take `TxHandle` per aggregate as it converts; the compiler
   then lists every direct-write call site, which is the enforcement.
4. Delete `UseCaseException` when no thrower remains; delete the adapter.
5. Definition of done: no `throw new UseCaseException` in `server/`; every handler's result handling
   is an exhaustive switch (a sealed hierarchy makes a missing arm a compile error); parity 43
   scenarios unchanged; the unit-of-work guard tests (double commit, write after commit) pass.

## Not in scope

Changing error codes or messages (the parity harness pins them); `ScopedValue` for request context
(separate, small, do it with the Vert.x P1 handler adapter).
