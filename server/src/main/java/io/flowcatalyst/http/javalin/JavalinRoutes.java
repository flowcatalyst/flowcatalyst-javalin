package io.flowcatalyst.http.javalin;

import io.flowcatalyst.http.Admission;
import io.flowcatalyst.http.Budgets;
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
    private final Budgets budgets;
    private final List<Registration> registrations;
    private final Group group;

    JavalinRoutes(JavalinDefaultRoutingApi api, ExceptionMappers mappers, Budgets budgets) {
        this(api, mappers, budgets, new ArrayList<>(), null);
    }

    private JavalinRoutes(JavalinDefaultRoutingApi api, ExceptionMappers mappers, Budgets budgets,
                           List<Registration> registrations, Group group) {
        this.api = api;
        this.mappers = mappers;
        this.budgets = budgets;
        this.registrations = registrations;
        this.group = group;
    }

    /// The tier-2 budgets this adapter enforces (`docs/spec/admission.md` §2).
    public Budgets budgets() {
        return budgets;
    }

    /// Wraps a route handler in the request's admission: the group permit
    /// (untimed, only for a budgeted group) and the [Admission] scope that the
    /// pool gate's nested-checkout guard reads. On this adapter the scope
    /// covers the route handler; Javalin's `before`/`after` run outside it.
    private io.javalin.http.Handler admitted(Handler h) {
        return c -> {
            Budgets.Permit permit = budgets.acquire(group);
            try {
                ScopedValue.where(Admission.CURRENT, new Admission(c.path())).call(() -> {
                    h.handle(new JavalinExchange(c));
                    return null;
                });
            } finally {
                permit.close();
            }
        };
    }

    @Override
    public Routes get(String path, Handler h) {
        api.get(path, admitted(h));
        registrations.add(new Registration("GET", path, group));
        return this;
    }

    @Override
    public Routes post(String path, Handler h) {
        api.post(path, admitted(h));
        registrations.add(new Registration("POST", path, group));
        return this;
    }

    @Override
    public Routes put(String path, Handler h) {
        api.put(path, admitted(h));
        registrations.add(new Registration("PUT", path, group));
        return this;
    }

    @Override
    public Routes patch(String path, Handler h) {
        api.patch(path, admitted(h));
        registrations.add(new Registration("PATCH", path, group));
        return this;
    }

    @Override
    public Routes delete(String path, Handler h) {
        api.delete(path, admitted(h));
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
        return new JavalinRoutes(api, mappers, budgets, registrations, group);
    }

    @Override
    public List<Registration> registrations() {
        return Collections.unmodifiableList(registrations);
    }
}
