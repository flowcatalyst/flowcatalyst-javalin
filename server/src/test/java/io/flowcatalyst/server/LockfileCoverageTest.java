package io.flowcatalyst.server;

import io.flowcatalyst.http.RouteRegistry;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.shared.openapi.Lockfile;
import io.flowcatalyst.testpg.TestPg;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
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
///
/// Walks [RouteRegistry#registrations()] (`docs/spec/http-seam.md` §1) —
/// `before`/`after`/`exception` are not registrations, so there is nothing
/// to skip: a route (`get`/`post`/`put`/`patch`/`delete`) is the only thing
/// that can be enumerated.
class LockfileCoverageTest {

    /// The platform port is complete: every lockfile operation must be routed.
    private static final double REQUIRED_COVERAGE = 1.0;

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
    /// `revoke-previous-secret` is A-22 (`docs/improvements.md`, the
    /// OAuth-client secret-rotation grace window): the vendored lockfile
    /// predates that ruling, so the route — real behaviour, not drift —
    /// is named here rather than hand-edited into the lockfile
    /// (CONVENTIONS §7: never edit the lockfile by hand).
    private static final List<String> OUTSIDE_LOCKFILE_EXACT_ROUTES = List.of(
            "POST /api/dispatch-jobs", "POST /api/oauth-clients/{id}/revoke-previous-secret");

    @Test
    void registeredApiRoutesAreInTheLockfileAndCoverageIsReported() {
        Env env = Env.load(Map.of("FC_API_PORT", "0", "FC_METRICS_PORT", "0", "FC_PLATFORM_ENABLED", "true"));
        var server = new Server(env, new Server.Mode.Platform(TestPg.dataSource()), Server.Spa.none(), new PrometheusRegistry());
        RouteRegistry registry = server.buildApi().registry();

        Set<String> registered = new TreeSet<>();
        for (var reg : registry.registrations()) {
            registered.add(reg.method() + " " + reg.path());
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
