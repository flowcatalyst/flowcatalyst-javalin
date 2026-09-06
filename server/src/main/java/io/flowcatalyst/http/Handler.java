package io.flowcatalyst.http;

/// A route handler or filter (`before`/`after`), framework-independent.
/// `throws Exception` so use-case and I/O code never needs a try/catch just
/// to satisfy the signature; whatever is thrown reaches [ExceptionMappers]
/// through the adapter.
@FunctionalInterface
public interface Handler {

    void handle(Exchange ctx) throws Exception;
}
