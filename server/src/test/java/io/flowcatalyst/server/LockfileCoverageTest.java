package io.flowcatalyst.server;

import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.shared.openapi.Lockfile;
import io.flowcatalyst.testpg.TestPg;
import io.javalin.Javalin;
import io.javalin.http.HandlerType;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/// The wire-contract gate: every `(method, path)` the Java server registers
/// under `/api/**` must exist in the OpenAPI lockfile (no drift — the Java
/// code is written *from* the lockfile, never the other way round), and every
/// lockfile operation should eventually have a route. The second half is
/// reported as a coverage figure until the port is complete, then flipped to
/// a hard assertion (see `REQUIRED_COVERAGE`).
class LockfileCoverageTest {

    /// Raise to 1.0 when the platform port is complete.
    private static final double REQUIRED_COVERAGE = 0.0;

    /// Paths Go serves outside the lockfile (chi-mounted: auth/session/OAuth/BFF/public/SPA/spec).
    private static final List<String> OUTSIDE_LOCKFILE_PREFIXES = List.of(
            "/health", "/auth/", "/oauth/", "/.well-known/", "/portal/", "/bff/", "/api/me",
            "/api/public/", "/api/config/platform", "/api/dispatch/", "/api/dispatch-jobs/batch", "/api/audit-logs/batch",
            "/api/openapi.json", "/api/openapi.yaml", "/q/openapi", "/swagger-ui", "/mcp", "/router/");

    /// Full `METHOD PATH` routes outside the lockfile that a path-prefix
    /// entry above can't name precisely without over-matching a sibling
    /// route that IS lockfiled (`GET /api/dispatch-jobs` and
    /// `POST /api/dispatch-jobs/requeue` share the `/api/dispatch-jobs`
    /// prefix with this singular SDK-ingest create, sdk-ingest spec §1).
    private static final List<String> OUTSIDE_LOCKFILE_EXACT_ROUTES = List.of("POST /api/dispatch-jobs");

    @Test
    void registeredApiRoutesAreInTheLockfileAndCoverageIsReported() {
        Env env = Env.load(Map.of("FC_API_PORT", "0", "FC_METRICS_PORT", "0", "FC_PLATFORM_ENABLED", "true"));
        var server = new Server(env, new Server.Mode.Platform(TestPg.dataSource()), Server.Spa.none(), new PrometheusRegistry());
        Javalin api = server.buildApi();

        Set<String> registered = new TreeSet<>();
        for (var parsed : api.unsafe.internalRouter.allHttpHandlers()) {
            var endpoint = parsed.endpoint;
            if (endpoint.method == HandlerType.BEFORE || endpoint.method == HandlerType.AFTER) continue;
            registered.add(endpoint.method.name().toUpperCase(Locale.ROOT) + " " + endpoint.path);
        }

        var lock = Lockfile.load(Json.MAPPER);
        Set<String> contract = new LinkedHashSet<>();
        lock.operations().forEach(op -> contract.add(op.method() + " " + op.path()));

        // 1. No drift: every registered /api/** route that is not a known chi-side route is in the lockfile.
        var drift = registered.stream()
                .filter(r -> r.contains(" /api/"))
                .filter(r -> OUTSIDE_LOCKFILE_PREFIXES.stream().noneMatch(p -> r.substring(r.indexOf(' ') + 1).startsWith(p)))
                .filter(r -> !OUTSIDE_LOCKFILE_EXACT_ROUTES.contains(r))
                .filter(r -> !contract.contains(r))
                .toList();
        assertThat(drift).as("routes registered but absent from openapi.lock.json").isEmpty();

        // 2. Coverage: how much of the contract is implemented.
        long covered = contract.stream().filter(registered::contains).count();
        double coverage = (double) covered / contract.size();
        System.out.printf("lockfile coverage: %d / %d operations (%.1f%%)%n", covered, contract.size(), coverage * 100);
        contract.stream().filter(op -> !registered.contains(op)).limit(15)
                .forEach(op -> System.out.println("  missing: " + op));
        assertThat(coverage).isGreaterThanOrEqualTo(REQUIRED_COVERAGE);
    }
}
