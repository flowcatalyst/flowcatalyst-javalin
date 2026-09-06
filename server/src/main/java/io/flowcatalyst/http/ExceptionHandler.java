package io.flowcatalyst.http;

/// Handles one thrown exception type, registered through
/// `Routes.exception(Class, ExceptionHandler)` and resolved by
/// [ExceptionMappers]. Unlike [Handler] this does not itself throw — an
/// exception mapper that fails is a bug in the mapper, not something the
/// seam recovers from a second time.
@FunctionalInterface
public interface ExceptionHandler<E extends Exception> {

    void handle(E e, Exchange ctx);
}
