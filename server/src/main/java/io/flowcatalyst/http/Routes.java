package io.flowcatalyst.http;

/// Registration surface for handlers and filters, framework-independent.
/// Path syntax is Javalin's and stays so on the Vert.x adapter: `{id}`
/// matches one segment, `<path>` matches the rest; the adapter translates,
/// handlers never see the difference.
///
/// Ordering guarantees, enforced by every adapter:
/// 1. `before` filters run in registration order, then the route handler,
///    then `after` filters in registration order, all on the request's
///    thread.
/// 2. `exception` mappers are matched most specific first — exact class
///    before superclass — regardless of registration order.
/// 3. A thrown exception skips the remaining `before`/handler but `after`
///    filters still run.
public interface Routes {

    Routes get(String path, Handler h);

    Routes post(String path, Handler h);

    Routes put(String path, Handler h);

    Routes patch(String path, Handler h);

    Routes delete(String path, Handler h);

    Routes before(Handler h);

    Routes before(String path, Handler h);

    Routes after(Handler h);

    <E extends Exception> Routes exception(Class<E> type, ExceptionHandler<E> h);

    /// A view of this `Routes`: registrations made through it carry `group`.
    /// Shares the underlying registry and exception mappers with the
    /// `Routes` it was taken from.
    Routes in(Group group);
}
