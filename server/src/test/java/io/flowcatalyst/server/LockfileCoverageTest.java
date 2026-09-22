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
/// Walks [RouteRegistry#registrations()] (`docs/spec/http-seam.md` §1) rather
/// than Javalin's `HandlerType` internals — `before`/`after`/`exception` are
/// not registrations, so there is nothing left to skip the way the old
/// `HandlerType.BEFORE`/`AFTER` filter did.
class LockfileCoverageTest {

    /// The platform port is complete: every lockfile operation must be routed.
    private static final double REQUIRED_COVERAGE = 1.0;

    /// Paths Go serves outside the lockfile (chi-mounted: auth/session/OAuth/BFF/public/SPA/spec).
    private static final List<String> OUTSIDE_LOCKFILE_PREFIXES = List.of(
            "/health", "/auth/", "/oauth/", "/.well-known/", "/portal/", "/bff/", "/api/me",
            "/api/public/", "/api/config/platform", "/api/dispatch/", "/api/dispatch-jobs/batch", "/api/audit-logs/batch",
            "/api/openapi.json", "/api/openapi.yaml", "/api/openapi-functions.json", "/q/openapi", "/swagger-ui", "/mcp", "/router/",
            // function-api.md §0: the function platform API is Java-first — there is no Go, so
            // there is no lockfile document for it. Covers /api/functions and /api/function-policies.
            "/api/function");

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

    /// The function surface is excluded from the lockfile above by PREFIX, so
    /// nothing there would notice a new function route. Its own document is
    /// the contract (`docs/spec/function-openapi.md` §3 O1) — and it is checked
    /// here against the COMPOSED server, because
    /// `FunctionOpenApiCoverageTest` registers the four function API classes
    /// itself and cannot see a route `Platform` adds through a fifth.
    @Test
    void everyFunctionRouteOfTheComposedServerIsInTheFunctionDocumentAndViceVersa() {
        Env env = Env.load(Map.of("FC_API_PORT", "0", "FC_METRICS_PORT", "0", "FC_PLATFORM_ENABLED", "true"));
        var server = new Server(env, new Server.Mode.Platform(io.flowcatalyst.platform.shared.database.Pools.ofSingle(TestPg.dataSource())), Server.Spa.none(), new PrometheusRegistry());
        Set<String> registered = new TreeSet<>();
        for (var reg : server.buildApi().registry().registrations()) {
            String path = reg.path();
            if (path.startsWith("/api/function") || path.startsWith("/control/functions")) {
                registered.add(reg.method() + " " + path);
            }
        }
        Set<String> documented = new TreeSet<>();
        Lockfile.load(Json.MAPPER, "openapi/functions.openapi.json").operations()
                .forEach(op -> documented.add(op.method() + " " + op.path()));

        assertThat(registered).as("function routes of the composed server vs functions.openapi.json")
                .isNotEmpty().isEqualTo(documented);
    }

    /// Lockfile operations that exist but are not routed yet, each owed to a
    /// named follow-up unit — carved OUT of [#REQUIRED_COVERAGE]'s
    /// denominator (so the 100% gate stays meaningful for everything else)
    /// and pinned exactly (below) so the gap can never silently grow to more
    /// than this one route.
    ///
    /// `POST /api/dispatch-jobs/{id}/sign` (catch-up-2026-09-22.md slice C3,
    /// not this slice): builds the delivery exactly as `/api/dispatch/process`
    /// would and returns it unsent, gated on `dispatch-job:view-raw`. Owed to
    /// C3, which brings the resolvers this route needs to share.
    private static final Set<String> KNOWN_MISSING = Set.of("POST /api/dispatch-jobs/{id}/sign");

    @Test
    void registeredApiRoutesAreInTheLockfileAndCoverageIsReported() {
        Env env = Env.load(Map.of("FC_API_PORT", "0", "FC_METRICS_PORT", "0", "FC_PLATFORM_ENABLED", "true"));
        var server = new Server(env, new Server.Mode.Platform(io.flowcatalyst.platform.shared.database.Pools.ofSingle(TestPg.dataSource())), Server.Spa.none(), new PrometheusRegistry());
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

        // 2. Coverage: how much of the contract is implemented, [#KNOWN_MISSING]
        // excepted — and that set must be EXACTLY what is still missing, so a
        // newly-unrouted operation cannot hide behind an already-forgiven one.
        var stillMissing = contract.stream().filter(op -> !registered.contains(op)).collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        assertThat(stillMissing).as("unrouted lockfile operations must be exactly the named, owed set")
                .isEqualTo(new TreeSet<>(KNOWN_MISSING));

        Set<String> coverageContract = contract.stream().filter(op -> !KNOWN_MISSING.contains(op)).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        long covered = coverageContract.stream().filter(registered::contains).count();
        double coverage = (double) covered / coverageContract.size();
        System.out.printf("lockfile coverage: %d / %d operations (%.1f%%), %d known-missing owed to a follow-up unit%n",
                covered, coverageContract.size(), coverage * 100, KNOWN_MISSING.size());
        assertThat(coverage).isGreaterThanOrEqualTo(REQUIRED_COVERAGE);
    }
}
