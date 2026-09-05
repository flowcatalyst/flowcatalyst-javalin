package io.flowcatalyst.server;

import io.flowcatalyst.platform.shared.json.Json;
import io.javalin.http.Context;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/// `GET /health`: `{"status":"UP","version":…}` and, when readiness
/// checks are registered, a `checks` map — every check `"ok"`, or the
/// problem text, in which case the status is `DOWN` and the answer 503 so
/// a load balancer stops routing here. The first such check (ruling
/// C-Q23) is the login-attempt partitions the backoff store needs.
final class Health {

    /// A named readiness probe: empty means healthy, else the problem.
    record Check(String name, Supplier<String> probe) {
        Check {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(probe, "probe");
        }
    }

    private final List<Check> checks;

    Health(List<Check> checks) {
        this.checks = List.copyOf(checks);
    }

    static Health noChecks() {
        return new Health(List.of());
    }

    void handle(Context ctx) {
        var body = new LinkedHashMap<String, Object>();
        boolean up = true;
        Map<String, String> results = new LinkedHashMap<>();
        for (Check c : checks) {
            String problem;
            try {
                problem = c.probe().get();
            } catch (RuntimeException e) {
                problem = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            if (problem == null || problem.isEmpty()) {
                results.put(c.name(), "ok");
            } else {
                results.put(c.name(), problem);
                up = false;
            }
        }
        body.put("status", up ? "UP" : "DOWN");
        body.put("version", Version.current());
        if (!checks.isEmpty()) {
            body.put("checks", results);
        }
        ctx.status(up ? 200 : 503).contentType("application/json").result(Json.writeLine(body));
    }
}
