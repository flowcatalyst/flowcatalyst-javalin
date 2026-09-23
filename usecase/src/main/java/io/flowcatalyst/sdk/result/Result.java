package io.flowcatalyst.sdk.result;

import java.util.Objects;
import java.util.function.Function;

/// The outcome of a step whose failure is expected: a value, or an error the
/// caller must handle. Switch on it — the compiler checks both arms:
///
/// ```java
/// switch (eligibility(p)) {
///     case Result.Ok(Principal eligible) -> issue(eligible);
///     case Result.Err(ResetIneligible why) -> log(why.reason());
/// }
/// ```
///
/// **`E` is a sealed interface of records, and the records are the context.**
/// `Result<Principal, String>` loses everything a caller could act on; a
/// `Federated(principalId, idpType)` keeps it, and a new case breaks every
/// switch that has not handled it. Crossing a layer, wrap rather than
/// flatten: the upper error type gets a case holding the lower error
/// ([#mapError]), so the chain of *why* survives to wherever it is finally
/// logged or answered.
///
/// Where it stops (`CONVENTIONS.md` §8): infrastructure failure is still an
/// exception, and an [io.flowcatalyst.sdk.usecase.op.Operation]'s phases
/// still raise [io.flowcatalyst.sdk.usecase.UseCaseException] — that is the
/// envelope's contract with the transports. [#orElseThrow] is the one
/// bridge, at that boundary.
///
/// Neither arm holds `null`: an absent value is its own case, not an `Ok`
/// with nothing in it.
public sealed interface Result<T, E> permits Result.Ok, Result.Err {

    record Ok<T, E>(T value) implements Result<T, E> {
        public Ok {
            Objects.requireNonNull(value, "value");
        }
    }

    record Err<T, E>(E error) implements Result<T, E> {
        public Err {
            Objects.requireNonNull(error, "error");
        }
    }

    static <T, E> Result<T, E> ok(T value) {
        return new Ok<>(value);
    }

    static <T, E> Result<T, E> err(E error) {
        return new Err<>(error);
    }

    /// Transforms the value; an error passes through untouched.
    default <U> Result<U, E> map(Function<? super T, ? extends U> f) {
        return switch (this) {
            case Ok<T, E>(T v) -> new Ok<>(f.apply(v));
            case Err<T, E>(E e) -> new Err<>(e);
        };
    }

    /// Chains a step that can itself fail; the first error wins.
    default <U> Result<U, E> flatMap(Function<? super T, ? extends Result<U, E>> f) {
        return switch (this) {
            case Ok<T, E>(T v) -> Objects.requireNonNull(f.apply(v), "flatMap result");
            case Err<T, E>(E e) -> new Err<>(e);
        };
    }

    /// Lifts the error into the caller's own error type — typically a case
    /// that wraps it, so the lower layer's context is carried, not dropped.
    default <F> Result<T, F> mapError(Function<? super E, ? extends F> f) {
        return switch (this) {
            case Ok<T, E>(T v) -> new Ok<>(v);
            case Err<T, E>(E e) -> new Err<>(f.apply(e));
        };
    }

    /// The value, or the exception `f` builds from the error. For the
    /// envelope boundary only (an operation phase answering a
    /// `UseCaseException`); everywhere else, switch.
    default <X extends RuntimeException> T orElseThrow(Function<? super E, X> f) {
        return switch (this) {
            case Ok<T, E>(T v) -> v;
            case Err<T, E>(E e) -> throw f.apply(e);
        };
    }
}
