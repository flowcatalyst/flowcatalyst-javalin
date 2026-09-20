package io.flowcatalyst.fnhost.http;

import io.flowcatalyst.platform.function.EndpointAuth;
import io.flowcatalyst.platform.function.HttpMethod;
import io.flowcatalyst.platform.function.Manifest;
import io.vertx.core.MultiMap;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/// CORS, shared by BOTH listeners, per endpoint (spec
/// `function-public-routes.md` §4). Only endpoints that declare `cors` are
/// touched at all — every method here is a no-op (or simply never called)
/// for an endpoint whose `cors()` is `null`. The host is the SOLE authority
/// for a `cors`-declaring endpoint's CORS response headers: it answers a
/// genuine preflight itself (no invoke, no permit, no auth — spec §4) and,
/// on an actual request, its own `-Allow-Origin`/`-Allow-Credentials`/`Vary`
/// REPLACE anything the function itself set ("one authority").
final class CorsPolicy {

    private CorsPolicy() {
    }

    private static final Set<String> CORS_MANAGED_RESPONSE_HEADERS =
            Set.of("access-control-allow-origin", "access-control-allow-credentials", "vary");

    /// spec §4: a genuine preflight is `OPTIONS` + `Origin` +
    /// `Access-Control-Request-Method` — all three, or it is an ordinary
    /// `OPTIONS` request that goes to the function like any other method
    /// (spec: "An `OPTIONS` request that is not a preflight goes to the
    /// function if the endpoint's methods allow it").
    static boolean isPreflight(String method, MultiMap headers) {
        return "OPTIONS".equalsIgnoreCase(method)
                && headers.get("Origin") != null
                && headers.get("Access-Control-Request-Method") != null;
    }

    /// The `204` the host answers a preflight with directly — CORS headers
    /// present only when BOTH the origin and the requested method are
    /// allowed; otherwise `204` with NO CORS headers at all (spec §4: "the
    /// browser blocks; the server does not reveal policy by status").
    static FnHttpServer.HttpAnswer preflight(Manifest.Endpoint endpoint, MultiMap headers) {
        Manifest.Cors cors = endpoint.cors();
        String origin = headers.get("Origin");
        String requestedMethod = headers.get("Access-Control-Request-Method");
        Set<String> allowedMethods = effectiveMethods(endpoint, cors, requestedMethod);
        boolean methodOk = requestedMethod != null
                && allowedMethods.contains(requestedMethod.trim().toUpperCase(Locale.ROOT));
        if (!originAllowed(cors, origin) || !methodOk) {
            return new FnHttpServer.HttpAnswer(204, Map.of(), new byte[0]);
        }

        Map<String, List<String>> respHeaders = new LinkedHashMap<>();
        boolean wildcard = cors.origins().contains("*");
        respHeaders.put("Access-Control-Allow-Origin", List.of(wildcard ? "*" : origin));
        respHeaders.put("Access-Control-Allow-Methods", List.of(String.join(", ", allowedMethods)));
        List<String> allowHeaders = allowedHeaders(cors, headers.get("Access-Control-Request-Headers"));
        if (!allowHeaders.isEmpty()) {
            respHeaders.put("Access-Control-Allow-Headers", List.of(String.join(", ", allowHeaders)));
        }
        if (cors.allowCredentials()) {
            respHeaders.put("Access-Control-Allow-Credentials", List.of("true"));
        }
        respHeaders.put("Access-Control-Max-Age", List.of("600"));
        // spec §4 / task brief: "*" ⇒ Access-Control-Allow-Origin: * WITHOUT Vary.
        if (!wildcard) {
            respHeaders.put("Vary", List.of("Origin"));
        }
        return new FnHttpServer.HttpAnswer(204, respHeaders, new byte[0]);
    }

    /// spec §4's "actual request" rule applied to whatever the function (or
    /// a host-generated error) answered. A no-op when the endpoint declares
    /// no `cors` at all — every other response header passes through
    /// verbatim (H10's own rule, untouched by this class).
    static FnHttpServer.HttpAnswer applyToActualResponse(Manifest.Endpoint endpoint, String origin,
                                                           FnHttpServer.HttpAnswer answer) {
        if (endpoint.cors() == null) {
            return answer;
        }
        Manifest.Cors cors = endpoint.cors();
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (var e : answer.headers().entrySet()) {
            if (!CORS_MANAGED_RESPONSE_HEADERS.contains(e.getKey().toLowerCase(Locale.ROOT))) {
                headers.put(e.getKey(), e.getValue());
            }
        }
        // "REPLACING any CORS headers the function set itself" above; disallowed (or no
        // Origin at all — not a CORS request) ⇒ nothing added back: "gets no CORS headers".
        if (originAllowed(cors, origin)) {
            boolean wildcard = cors.origins().contains("*");
            headers.put("Access-Control-Allow-Origin", List.of(wildcard ? "*" : origin));
            if (cors.allowCredentials()) {
                headers.put("Access-Control-Allow-Credentials", List.of("true"));
            }
            if (!wildcard) {
                headers.put("Vary", List.of("Origin"));
            }
        }
        return new FnHttpServer.HttpAnswer(answer.status(), headers, answer.body());
    }

    // ── origin comparison: scheme+host+port, case-insensitive host (spec §4) ──

    private static boolean originAllowed(Manifest.Cors cors, String origin) {
        if (origin == null) {
            return false;
        }
        if (cors.origins().contains("*")) {
            return true;
        }
        for (String configured : cors.origins()) {
            if (originEquals(configured, origin)) {
                return true;
            }
        }
        return false;
    }

    private record ParsedOrigin(String scheme, String host, int port) {
    }

    private static ParsedOrigin parseOrigin(String raw) {
        try {
            URI uri = new URI(raw);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null) {
                return null;
            }
            return new ParsedOrigin(scheme.toLowerCase(Locale.ROOT), host.toLowerCase(Locale.ROOT), uri.getPort());
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private static boolean originEquals(String configured, String requestOrigin) {
        ParsedOrigin a = parseOrigin(configured);
        ParsedOrigin b = parseOrigin(requestOrigin);
        return a != null && a.equals(b);
    }

    // ── methods / headers ────────────────────────────────────────────────

    /// spec §4: allowed = `cors.methods` ∪ the endpoint's own methods
    /// (`webhook` forced `POST`, same override [FnHttpServer]'s own endpoint
    /// match applies; an empty endpoint `methods` means "every method" and
    /// cannot itself be enumerated for an `-Allow-Methods` header, so the
    /// requested method — already known allowed by construction in that
    /// case — is what gets echoed).
    private static Set<String> effectiveMethods(Manifest.Endpoint endpoint, Manifest.Cors cors, String requestedMethod) {
        Set<String> methods = new LinkedHashSet<>();
        for (String m : cors.methods()) {
            methods.add(m.trim().toUpperCase(Locale.ROOT));
        }
        List<HttpMethod> declared = endpoint.auth() == EndpointAuth.WEBHOOK
                ? List.of(HttpMethod.POST)
                : endpoint.methods();
        if (declared.isEmpty()) {
            if (requestedMethod != null && !requestedMethod.isBlank()) {
                methods.add(requestedMethod.trim().toUpperCase(Locale.ROOT));
            }
        } else {
            declared.forEach(m -> methods.add(m.name()));
        }
        return methods;
    }

    /// spec §4: `cors.headers ∩ requested, case-insensitive` — the entries
    /// echoed back carry the manifest's OWN declared casing.
    private static List<String> allowedHeaders(Manifest.Cors cors, String requestedHeadersRaw) {
        if (requestedHeadersRaw == null || requestedHeadersRaw.isBlank()) {
            return List.of();
        }
        List<String> requested = new ArrayList<>();
        for (String h : requestedHeadersRaw.split(",")) {
            String trimmed = h.trim();
            if (!trimmed.isEmpty()) {
                requested.add(trimmed);
            }
        }
        List<String> out = new ArrayList<>();
        for (String configured : cors.headers()) {
            if (requested.stream().anyMatch(r -> r.equalsIgnoreCase(configured))) {
                out.add(configured);
            }
        }
        return out;
    }
}
