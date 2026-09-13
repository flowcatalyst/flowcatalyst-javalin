package io.flowcatalyst.server;

import io.flowcatalyst.http.Group;
import io.flowcatalyst.http.RouteRegistry;
import io.flowcatalyst.testpg.TestPg;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// Pins `docs/spec/admission.md` §11.7's classification rule: every
/// registered `/api/**` route whose handler runs an `Operation`/`TxOperation`
/// (a use case in a transaction, `UnitOfWork#inTransaction` or one of its
/// `commit*`/`emitEvent` helpers) is declared `Group.API_WRITE`.
///
/// Grep-based, per the spec's own "cheapest honest way" — it scans the SAME
/// `io.flowcatalyst.platform.**.api` handler sources the routes are defined
/// in (not a hand-maintained list), so a new write route added later without
/// `Group.API_WRITE` fails this test the same day, and the check does not
/// hold either way if the handler wiring itself changes shape. It resolves
/// one level of local `Handler var = ...;` indirection and follows private
/// method calls transitively, matching how `ClientApi`/`PortalUserApi`/etc.
/// actually wire their routes.
///
/// **Scope**: only registrations whose literal path string appears directly
/// in a `routes.verb("/api/...", ...)` call are scanned — the small number
/// of routes mounted through a `registerAt(routes, prefix, state, Group)`
/// helper with a *variable* prefix (`DispatchJobApi`/`ProcessApi`, shared
/// between their `/api/` and `/bff/` mounts) are pinned explicitly below
/// instead (`explicitlyPinnedDynamicPrefixWriteRoutesAreApiWrite`), since
/// tracing a variable prefix back to its literal call site is not a grep.
///
/// Mutant: mark one write route `API_READ` in its `*Api.java` (e.g. change
/// `write.put("/api/clients/{id}", ...)` back to `routes.put(...)` in
/// `ClientApi#register`) → this test fails, naming the route.
class RouteGroupTest {

    // The receiver is any identifier (`routes`, or the local `write`/`bff`/etc.
    // view `Routes.in(Group)` returns) — the group a registration carries is
    // exactly what this test is checking, so the scan must not assume the
    // receiver is named `routes`.
    private static final Pattern REGISTRATION = Pattern.compile(
            "[A-Za-z_][A-Za-z0-9_]*\\.(get|post|put|patch|delete)\\(\\s*\"([^\"]+)\"\\s*,\\s*(.+)\\)\\s*;\\s*(//.*)?$");
    private static final Pattern VAR_DEF = Pattern.compile(
            "Handler\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(.+);\\s*(//.*)?$");
    private static final Pattern HANDLER_LAMBDA = Pattern.compile("->\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\(");
    private static final Pattern HANDLER_METHOD_REF = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*::([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern METHOD_DEF = Pattern.compile(
            "(?:private|public|static)[^\\n{;]*\\s([A-Za-z_][A-Za-z0-9_]*)\\s*\\([^;{]*\\)\\s*\\{");
    private static final Pattern WRITE_CALL = Pattern.compile(
            "\\.run\\(\\s*s?\\.?uow\\(\\)|\\.run\\(\\s*[a-zA-Z_]*[uU]ow\\b|uow\\(\\)\\.inTransaction|\\.inTransaction\\("
                    + "|\\bs\\.uow\\(\\)\\.(commit|commitDelete|commitAll|commitSync|emitEvent)\\("
                    + "|\\buow\\.(commit|commitDelete|commitAll|commitSync|emitEvent)\\(");

    /// `registerAt(routes, "/api/dispatch-jobs", s, Group.API_WRITE)` /
    /// `registerAt(routes, "/api/processes", s, Group.API_WRITE)` mount these
    /// through a variable `prefix`, so [#REGISTRATION] never sees them (see
    /// the class doc's Scope note). Pinned by hand, verified against
    /// `DispatchJobApi`/`ProcessApi` source at the time of writing.
    private static final List<String> DYNAMIC_PREFIX_WRITE_ROUTES = List.of(
            "POST /api/dispatch-jobs/requeue", "POST /api/dispatch-jobs/{id}/cancel",
            "POST /api/processes", "PUT /api/processes/{id}",
            "POST /api/processes/{id}/archive", "DELETE /api/processes/{id}");

    @Test
    void everyWriteRouteFoundBySourceScanIsDeclaredApiWrite() throws IOException {
        Env env = Env.load(Map.of("FC_API_PORT", "0", "FC_METRICS_PORT", "0", "FC_PLATFORM_ENABLED", "true"));
        var server = new Server(env,
                new Server.Mode.Platform(io.flowcatalyst.platform.shared.database.Pools.ofSingle(TestPg.dataSource())),
                Server.Spa.none(), new PrometheusRegistry());
        RouteRegistry registry = server.buildApi().registry();

        Map<String, Group> declared = new HashMap<>();
        for (var reg : registry.registrations()) {
            if (reg.path().startsWith("/api/")) {
                declared.put(reg.method() + " " + reg.path(), reg.group());
            }
        }

        Set<String> writeRoutes = scanForApiWriteRoutes();
        assertThat(writeRoutes).as("the source scan itself found nothing — a regression in the scan, not the wiring")
                .isNotEmpty();

        List<String> mismatches = new ArrayList<>();
        for (String route : writeRoutes) {
            Group g = declared.get(route);
            if (g != Group.API_WRITE) {
                mismatches.add(route + ": expected API_WRITE, registry has " + g);
            }
        }
        assertThat(mismatches).as("routes whose handler opens a transaction but are not declared Group.API_WRITE")
                .isEmpty();

        // The other direction, over the same literal-scan-covered routes: a route the scan
        // did NOT find a transaction for must not be declared API_WRITE either — together with
        // the assertion above this pins the exact classification, not just a one-way floor.
        List<String> overclassified = new ArrayList<>();
        for (var entry : declared.entrySet()) {
            if (entry.getValue() == Group.API_WRITE && !writeRoutes.contains(entry.getKey())
                    && !DYNAMIC_PREFIX_WRITE_ROUTES.contains(entry.getKey())) {
                overclassified.add(entry.getKey());
            }
        }
        assertThat(overclassified)
                .as("routes declared API_WRITE that the source scan found no Operation/TxOperation for")
                .isEmpty();
    }

    @Test
    void explicitlyPinnedDynamicPrefixWriteRoutesAreApiWrite() {
        Env env = Env.load(Map.of("FC_API_PORT", "0", "FC_METRICS_PORT", "0", "FC_PLATFORM_ENABLED", "true"));
        var server = new Server(env,
                new Server.Mode.Platform(io.flowcatalyst.platform.shared.database.Pools.ofSingle(TestPg.dataSource())),
                Server.Spa.none(), new PrometheusRegistry());
        RouteRegistry registry = server.buildApi().registry();

        Map<String, Group> declared = new HashMap<>();
        for (var reg : registry.registrations()) {
            declared.put(reg.method() + " " + reg.path(), reg.group());
        }

        for (String route : DYNAMIC_PREFIX_WRITE_ROUTES) {
            assertThat(declared.get(route)).as(route).isEqualTo(Group.API_WRITE);
        }
    }

    // ── the scan ─────────────────────────────────────────────────────────

    private static Set<String> scanForApiWriteRoutes() throws IOException {
        Set<String> writeRoutes = new HashSet<>();
        Path root = Path.of("src/main/java");
        try (Stream<Path> files = Files.walk(root)) {
            for (Path path : files.filter(RouteGroupTest::isApiSourceFile).toList()) {
                scanFile(path, writeRoutes);
            }
        }
        return writeRoutes;
    }

    private static boolean isApiSourceFile(Path p) {
        if (!p.toString().endsWith(".java")) return false;
        Path parent = p.getParent();
        return parent != null && "api".equals(parent.getFileName().toString());
    }

    private static void scanFile(Path path, Set<String> writeRoutes) {
        String src;
        try {
            src = Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String[] lines = src.split("\n", -1);

        Map<String, String> varToHandler = new HashMap<>();
        for (String line : lines) {
            Matcher m = VAR_DEF.matcher(line.strip());
            if (m.find()) {
                String handler = extractHandler(m.group(2));
                if (handler != null) varToHandler.put(m.group(1), handler);
            }
        }

        record Reg(String verb, String path, String handler) {
        }
        List<Reg> apiRegs = new ArrayList<>();
        for (String line : lines) {
            Matcher m = REGISTRATION.matcher(line.strip());
            if (!m.find()) continue;
            String verb = m.group(1);
            String urlPath = m.group(2);
            if (!urlPath.startsWith("/api/")) continue;
            String expr = m.group(3);
            String handler = extractHandler(expr);
            if (handler == null) {
                String varName = expr.strip();
                handler = varToHandler.get(varName);
            }
            apiRegs.add(new Reg(verb, urlPath, handler));
        }
        if (apiRegs.isEmpty()) return;

        Map<String, String> bodies = allMethodBodies(src);
        for (Reg reg : apiRegs) {
            if (reg.handler() != null && isWrite(reg.handler(), bodies, new HashSet<>())) {
                writeRoutes.add(reg.verb().toUpperCase(java.util.Locale.ROOT) + " " + reg.path());
            }
        }
    }

    private static String extractHandler(String expr) {
        Matcher m = HANDLER_LAMBDA.matcher(expr);
        if (m.find()) return m.group(1);
        m = HANDLER_METHOD_REF.matcher(expr);
        if (m.find()) return m.group(1);
        return null;
    }

    private static Map<String, String> allMethodBodies(String src) {
        Map<String, String> bodies = new HashMap<>();
        Matcher m = METHOD_DEF.matcher(src);
        while (m.find()) {
            String name = m.group(1);
            int start = m.end();
            int depth = 1;
            int j = start;
            while (j < src.length() && depth > 0) {
                char c = src.charAt(j);
                if (c == '{') depth++;
                else if (c == '}') depth--;
                j++;
            }
            bodies.put(name, src.substring(start, j));
        }
        return bodies;
    }

    private static boolean isWrite(String name, Map<String, String> bodies, Set<String> seen) {
        if (!seen.add(name)) return false;
        String body = bodies.get(name);
        if (body == null) return false;
        if (WRITE_CALL.matcher(body).find()) return true;
        Matcher calls = Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)\\s*\\(").matcher(body);
        while (calls.find()) {
            String callee = calls.group(1);
            if (!callee.equals(name) && bodies.containsKey(callee) && !seen.contains(callee)) {
                if (isWrite(callee, bodies, seen)) return true;
            }
        }
        return false;
    }
}
