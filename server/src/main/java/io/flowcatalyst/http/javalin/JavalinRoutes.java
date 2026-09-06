package io.flowcatalyst.http.javalin;

import io.flowcatalyst.http.ExceptionHandler;
import io.flowcatalyst.http.ExceptionMappers;
import io.flowcatalyst.http.Group;
import io.flowcatalyst.http.Handler;
import io.flowcatalyst.http.RouteRegistry;
import io.flowcatalyst.http.Routes;
import io.javalin.router.JavalinDefaultRoutingApi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/// [Routes] and [RouteRegistry] over a Javalin [JavalinDefaultRoutingApi]:
/// every `get`/`post`/`put`/`patch`/`delete` wraps the handler as a
/// [JavalinExchange] and records a [RouteRegistry.Registration]; `before`,
/// `after` wrap likewise but record nothing; `exception` registers into the
/// shared [ExceptionMappers] instead of onto Javalin directly, so
/// specificity is resolved by the seam (see [JavalinAdapter]), not by
/// Javalin's own last-registered-wins order.
///
/// `in(Group)` returns a view that shares this instance's registry and
/// exception mappers but stamps every registration it makes with `group`.
public final class JavalinRoutes implements Routes, RouteRegistry {

    private final JavalinDefaultRoutingApi api;
    private final ExceptionMappers mappers;
    private final List<Registration> registrations;
    private final Group group;

    JavalinRoutes(JavalinDefaultRoutingApi api, ExceptionMappers mappers) {
        this(api, mappers, new ArrayList<>(), null);
    }

    private JavalinRoutes(JavalinDefaultRoutingApi api, ExceptionMappers mappers,
                           List<Registration> registrations, Group group) {
        this.api = api;
        this.mappers = mappers;
        this.registrations = registrations;
        this.group = group;
    }

    @Override
    public Routes get(String path, Handler h) {
        api.get(path, c -> h.handle(new JavalinExchange(c)));
        registrations.add(new Registration("GET", path, group));
        return this;
    }

    @Override
    public Routes post(String path, Handler h) {
        api.post(path, c -> h.handle(new JavalinExchange(c)));
        registrations.add(new Registration("POST", path, group));
        return this;
    }

    @Override
    public Routes put(String path, Handler h) {
        api.put(path, c -> h.handle(new JavalinExchange(c)));
        registrations.add(new Registration("PUT", path, group));
        return this;
    }

    @Override
    public Routes patch(String path, Handler h) {
        api.patch(path, c -> h.handle(new JavalinExchange(c)));
        registrations.add(new Registration("PATCH", path, group));
        return this;
    }

    @Override
    public Routes delete(String path, Handler h) {
        api.delete(path, c -> h.handle(new JavalinExchange(c)));
        registrations.add(new Registration("DELETE", path, group));
        return this;
    }

    @Override
    public Routes before(Handler h) {
        api.before(c -> h.handle(new JavalinExchange(c)));
        return this;
    }

    @Override
    public Routes before(String path, Handler h) {
        api.before(path, c -> h.handle(new JavalinExchange(c)));
        return this;
    }

    @Override
    public Routes after(Handler h) {
        api.after(c -> h.handle(new JavalinExchange(c)));
        return this;
    }

    @Override
    public <E extends Exception> Routes exception(Class<E> type, ExceptionHandler<E> h) {
        mappers.register(type, h);
        return this;
    }

    @Override
    public Routes in(Group group) {
        return new JavalinRoutes(api, mappers, registrations, group);
    }

    @Override
    public List<Registration> registrations() {
        return Collections.unmodifiableList(registrations);
    }
}
