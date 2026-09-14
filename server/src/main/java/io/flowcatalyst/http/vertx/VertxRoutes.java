package io.flowcatalyst.http.vertx;

import io.flowcatalyst.http.ExceptionHandler;
import io.flowcatalyst.http.ExceptionMappers;
import io.flowcatalyst.http.Group;
import io.flowcatalyst.http.Handler;
import io.flowcatalyst.http.RouteRegistry;
import io.flowcatalyst.http.Routes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/// The registration side of the Vert.x adapter: records everything and mounts
/// nothing until [VertxListener] asks. Matching is the adapter's own
/// (`docs/spec/vertx-listener.md` §1): one Vert.x route per distinct path
/// pattern, methods dispatched from [Mount#byMethod], so a known path with
/// the wrong method is a 404 through the platform envelope, never a 405.
public final class VertxRoutes implements Routes, RouteRegistry {
    /// A route handler with the group its registration carried.
    record Grouped(Handler handler, Group group) {
    }

    /// Everything registered under one Javalin-syntax path pattern.
    static final class Mount {
        final String pattern;
        final Map<String, Grouped> byMethod = new LinkedHashMap<>();

        Mount(String pattern) {
            this.pattern = pattern;
        }
    }

    /// A `before` filter, path-scoped or global. Javalin's scoping rules for
    /// the two shapes the platform uses: an exact path, or a `/prefix/*`.
    record Before(Pattern scope, Handler handler) {
        boolean matches(String path) {
            return scope == null || scope.matcher(path).matches();
        }
    }

    private final ExceptionMappers mappers;
    private final Map<String, Mount> mounts;
    private final List<Before> befores;
    private final List<Handler> afters;
    private final List<Registration> registrations;
    private final Group group;

    VertxRoutes(ExceptionMappers mappers) {
        this(mappers, new LinkedHashMap<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), null);
    }

    private VertxRoutes(ExceptionMappers mappers, Map<String, Mount> mounts, List<Before> befores,
                        List<Handler> afters, List<Registration> registrations, Group group) {
        this.mappers = mappers;
        this.mounts = mounts;
        this.befores = befores;
        this.afters = afters;
        this.registrations = registrations;
        this.group = group;
    }

    private Routes add(String method, String path, Handler h) {
        mounts.computeIfAbsent(path, Mount::new).byMethod.put(method, new Grouped(h, group));
        registrations.add(new Registration(method, path, group));
        return this;
    }

    @Override
    public Routes get(String path, Handler h) {
        return add("GET", path, h);
    }

    @Override
    public Routes post(String path, Handler h) {
        return add("POST", path, h);
    }

    @Override
    public Routes put(String path, Handler h) {
        return add("PUT", path, h);
    }

    @Override
    public Routes patch(String path, Handler h) {
        return add("PATCH", path, h);
    }

    @Override
    public Routes delete(String path, Handler h) {
        return add("DELETE", path, h);
    }

    @Override
    public Routes before(Handler h) {
        befores.add(new Before(null, h));
        return this;
    }

    @Override
    public Routes before(String path, Handler h) {
        befores.add(new Before(scopePattern(path), h));
        return this;
    }

    @Override
    public Routes after(Handler h) {
        afters.add(h);
        return this;
    }

    @Override
    public <E extends Exception> Routes exception(Class<E> type, ExceptionHandler<E> h) {
        mappers.register(type, h);
        return this;
    }

    @Override
    public Routes in(Group group) {
        return new VertxRoutes(mappers, mounts, befores, afters, registrations, group);
    }

    @Override
    public List<Registration> registrations() {
        return Collections.unmodifiableList(registrations);
    }

    ExceptionMappers mappers() {
        return mappers;
    }

    List<Mount> mounts() {
        return List.copyOf(mounts.values());
    }

    List<Before> befores() {
        return Collections.unmodifiableList(befores);
    }

    List<Handler> afters() {
        return Collections.unmodifiableList(afters);
    }

    /// Javalin path syntax → a regex over the request path: `{name}` one
    /// segment, `<name>` or a trailing `*` the rest.
    static Pattern scopePattern(String javalinPath) {
        StringBuilder re = new StringBuilder("^");
        int i = 0;
        while (i < javalinPath.length()) {
            char c = javalinPath.charAt(i);
            if (c == '{') {
                int end = javalinPath.indexOf('}', i);
                re.append("[^/]+");
                i = end + 1;
            } else if (c == '<') {
                int end = javalinPath.indexOf('>', i);
                re.append(".*");
                i = end + 1;
            } else if (c == '*') {
                re.append(".*");
                i++;
            } else {
                if ("\\.[]()+?^$|".indexOf(c) >= 0) re.append('\\');
                re.append(c);
                i++;
            }
        }
        return Pattern.compile(re.append('$').toString());
    }

    /// Javalin path syntax → Vert.x path syntax: `{name}` → `:name`,
    /// `<name>` → `*`.
    static String vertxPath(String javalinPath) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < javalinPath.length()) {
            char c = javalinPath.charAt(i);
            if (c == '{') {
                int end = javalinPath.indexOf('}', i);
                out.append(':').append(javalinPath, i + 1, end);
                i = end + 1;
            } else if (c == '<') {
                int end = javalinPath.indexOf('>', i);
                out.append('*');
                i = end + 1;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }
}
