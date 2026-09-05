package io.flowcatalyst.parity;

import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.shared.openapi.Lockfile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/// Mirrors `LockfileCoverageTest` (parity-harness spec §7): the 245 lockfile
/// `operationId`s, plus the outside-lockfile surface named by hand in
/// `parity/surface.json`, each marked hit when some request the run actually
/// sent matches its `METHOD path` template.
public final class Coverage {

    private Coverage() {
    }

    /// One `METHOD /path` entry, whether from the lockfile or `surface.json`.
    public record Route(String method, String pathTemplate, String operationId) {
        @Override
        public String toString() {
            return method + " " + pathTemplate;
        }
    }

    public record Result(List<Route> lockfileRoutes, List<Route> hitLockfile,
                          List<Route> surfaceRoutes, List<Route> hitSurface) {

        public double lockfileCoverage() {
            return lockfileRoutes.isEmpty() ? 1.0 : (double) hitLockfile.size() / lockfileRoutes.size();
        }

        public double surfaceCoverage() {
            return surfaceRoutes.isEmpty() ? 1.0 : (double) hitSurface.size() / surfaceRoutes.size();
        }

        public List<Route> missingLockfile() {
            return lockfileRoutes.stream().filter(r -> !hitLockfile.contains(r)).toList();
        }

        public List<Route> missingSurface() {
            return surfaceRoutes.stream().filter(r -> !hitSurface.contains(r)).toList();
        }
    }

    /// `/api/event-types/{id}` matches `/api/event-types/abc` (a `{param}`
    /// segment matches any single path segment) but not `/api/event-types`
    /// (a different segment count is a different operation).
    public static boolean matchesTemplate(String template, String actualPath) {
        String[] t = template.split("/", -1);
        String[] a = actualPath.split("/", -1);
        if (t.length != a.length) return false;
        for (int i = 0; i < t.length; i++) {
            String seg = t[i];
            if (seg.startsWith("{") && seg.endsWith("}")) continue;
            if (!seg.equals(a[i])) return false;
        }
        return true;
    }

    private static boolean isHit(Route route, List<Runner.RequestedRoute> requested) {
        return requested.stream().anyMatch(r -> r.method().equalsIgnoreCase(route.method())
                && matchesTemplate(route.pathTemplate(), r.path()));
    }

    /// The lockfile's operations as [Route]s.
    public static List<Route> lockfileRoutes(Lockfile lockfile) {
        return lockfile.operations().stream().map(op -> new Route(op.method(), op.path(), op.operationId())).toList();
    }

    /// `parity/surface.json`: a JSON array of `"METHOD /path"` strings.
    public static List<Route> loadSurface(Path path) {
        if (!Files.exists(path)) return List.of();
        try {
            String[] lines = Json.MAPPER.readValue(Files.readAllBytes(path), String[].class);
            List<Route> out = new ArrayList<>();
            for (String line : lines) {
                int sp = line.indexOf(' ');
                if (sp < 0) throw new IllegalArgumentException("surface.json entry is not 'METHOD /path': " + line);
                out.add(new Route(line.substring(0, sp).toUpperCase(Locale.ROOT), line.substring(sp + 1), null));
            }
            return List.copyOf(out);
        } catch (IOException e) {
            throw new UncheckedIOException("read " + path, e);
        }
    }

    public static Result compute(List<Route> lockfileRoutes, List<Route> surfaceRoutes,
                                  List<Runner.RequestedRoute> requested) {
        List<Route> hitLockfile = lockfileRoutes.stream().filter(r -> isHit(r, requested)).toList();
        List<Route> hitSurface = surfaceRoutes.stream().filter(r -> isHit(r, requested)).toList();
        return new Result(lockfileRoutes, hitLockfile, surfaceRoutes, hitSurface);
    }

    /// A scenario's `covers` claims (spec §3/§7) checked against what it
    /// actually requested: every claimed `operationId` must template-match at
    /// least one request the scenario made on this side.
    ///
    /// @return the claimed ids that were never actually hit — a false claim, spec §6
    public static List<String> unmetClaims(List<String> claimedOperationIds, List<Route> lockfileRoutes,
                                            List<Runner.RequestedRoute> requested) {
        List<String> missing = new ArrayList<>();
        for (String claim : claimedOperationIds) {
            Route route = lockfileRoutes.stream().filter(r -> claim.equals(r.operationId())).findFirst().orElse(null);
            if (route == null) {
                missing.add(claim + " (not a lockfile operationId)");
            } else if (!isHit(route, requested)) {
                missing.add(claim);
            }
        }
        return missing;
    }
}
