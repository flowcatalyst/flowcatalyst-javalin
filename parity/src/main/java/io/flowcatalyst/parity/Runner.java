package io.flowcatalyst.parity;

import io.flowcatalyst.parity.model.Request;
import io.flowcatalyst.parity.model.Scenario;
import io.flowcatalyst.parity.model.Step;
import io.flowcatalyst.platform.shared.json.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/// Runs one [Scenario] against one side (parity-harness spec §3, §4): its
/// own `HttpClient`, its own cookie jar, `Redirect.NEVER`, 10 s per request.
/// Every exception raised while building or capturing a step (an undefined
/// `${…}`, a missing capture pointer, an `expect.status` mismatch) is caught
/// and turned into a [StepOutcome.Failed] for that step alone — the runner
/// keeps going, so a later step's own failure (or success) is reported on
/// its own merits rather than being swallowed by an early abort.
///
/// SPEC? §3 says the cookie jar is "automatic (JDK `CookieManager`)"; this
/// uses a small hand-rolled jar instead. `fc_session` is `Secure`
/// ([io.flowcatalyst.platform.auth.login.SessionCookie], correctly, since
/// spec §2 leaves `FC_AUTH_ALLOW_TEST_HEADERS` unset) and both sides serve
/// plain `http://127.0.0.1`, never `https`; `java.net.CookieManager.get`
/// filters a `Secure` cookie out of every non-`https` request by design
/// (RFC 6265), so with the JDK's own jar every request after login loses
/// the session — identically on both sides, since it is standard client
/// behaviour, not an implementation difference. A manual jar that carries
/// whatever `Set-Cookie` sends, ignoring the scheme, is the harness
/// simulating a trusted local client over its own loopback transport, which
/// is what running two real dev servers side by side actually is.
public final class Runner {

    private static final String SESSION_COOKIE = "fc_session";
    private static final String HARDENED_SESSION_COOKIE = "__Host-fc_session";

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient client;
    private final String baseUrl;
    /// This side's cookie jar for the scenario (see the class doc's SPEC?
    /// note): cookie name → value, last `Set-Cookie` wins; an empty value
    /// (how [io.flowcatalyst.platform.auth.login.SessionCookie#clear] logs
    /// out) removes the entry.
    private final Map<String, String> cookieJar = new LinkedHashMap<>();

    public Runner(String baseUrl) {
        this.baseUrl = baseUrl;
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /// One step's fate on this side, in scenario order.
    public sealed interface StepOutcome permits StepOutcome.Ran, StepOutcome.Failed {
        String stepId();

        record Ran(String stepId, StepRecord record, String method, String path) implements StepOutcome {
        }

        /// `record` is the raw response when one was received but failed
        /// `expect.status`; `null` when the request could not even be built
        /// or sent (bad substitution, transport failure).
        record Failed(String stepId, String message, StepRecord record) implements StepOutcome {
        }
    }

    /// A full scenario run on this side: every step's outcome and the
    /// concrete `(method, path)` of every request actually sent — the raw
    /// material for the coverage check (spec §7) and the `covers` claim
    /// (spec §3).
    public record RunResult(List<StepOutcome> steps, List<RequestedRoute> requested) {
    }

    public record RequestedRoute(String method, String path) {
    }

    public RunResult run(Scenario scenario, Vars vars) {
        List<StepOutcome> outcomes = new ArrayList<>();
        List<RequestedRoute> requested = new ArrayList<>();
        SoftAuthenticator authenticator = null;
        JsonNode lastBody = null;

        for (Step step : scenario.steps()) {
            try {
                JsonNode requestBody = Substitution.resolve(step.request().body(), vars);
                if (step.authenticator() != null) {
                    if (authenticator == null) authenticator = new SoftAuthenticator();
                    // The previous response wraps the ceremony options as {stateId, options:{publicKey}};
                    // the authenticator wants the `options` node. Its output becomes the step body's
                    // `credential` member when the scenario gave a body ({stateId, name, …}), else
                    // the whole body.
                    JsonNode options = lastBody == null ? null : lastBody.has("options") ? lastBody.get("options") : lastBody;
                    JsonNode credential = runAuthenticator(step, authenticator, options, vars);
                    if (requestBody != null && requestBody.isObject()) {
                        ((ObjectNode) requestBody).set("credential", credential);
                    } else {
                        requestBody = credential;
                    }
                }
                Sent sent = send(step.request(), requestBody, vars);
                requested.add(new RequestedRoute(step.request().method(), sent.path()));
                StepRecord record = toRecord(sent.status(), sent.headers(), sent.bodyBytes());

                Step.Expect expect = step.expect();
                if (expect != null && expect.status() != null && expect.status() != sent.status()) {
                    outcomes.add(new StepOutcome.Failed(step.id(),
                            "expected status " + expect.status() + " but got " + sent.status(), record));
                    lastBody = sent.jsonBodyOrNull();
                    continue;
                }

                capture(step, sent, vars);
                autoCaptureId(step, sent, vars);
                outcomes.add(new StepOutcome.Ran(step.id(), record, step.request().method(), sent.path()));
                lastBody = sent.jsonBodyOrNull();
            } catch (RuntimeException e) {
                outcomes.add(new StepOutcome.Failed(step.id(), e.getMessage(), null));
            }
        }
        return new RunResult(List.copyOf(outcomes), List.copyOf(requested));
    }

    /// `"register"` mints a fresh credential over the previous step's
    /// options; `"assert"` signs an assertion over them. SPEC?
    /// parity-harness.md §3 does not say how the assertion's `userHandle`
    /// principal id is chosen for a step written in scenario JSON — this
    /// resolves `${admin.id}`, the only principal identity every scenario
    /// already carries; a scenario asserting as a non-admin principal (S2)
    /// will need a named capture instead.
    private JsonNode runAuthenticator(Step step, SoftAuthenticator authenticator, JsonNode options, Vars vars) {
        if (options == null) {
            throw new IllegalStateException("authenticator step '" + step.id() + "' has no prior response to act on");
        }
        try {
            return switch (step.authenticator()) {
                case "register" -> authenticator.register(options, baseUrl);
                case "assert" -> authenticator.assertion(options, baseUrl, vars.resolve("admin.id"));
                default -> throw new IllegalArgumentException("unknown authenticator step: " + step.authenticator());
            };
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("authenticator step '" + step.id() + "' failed: " + e.getMessage(), e);
        }
    }

    private record Sent(int status, HttpHeaders headers, byte[] bodyBytes, String path) {
        JsonNode jsonBodyOrNull() {
            String ct = headers.firstValue("Content-Type").orElse("");
            if (!ct.toLowerCase(Locale.ROOT).contains("json") || bodyBytes.length == 0) return null;
            try {
                return Json.MAPPER.readTree(bodyBytes);
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    private Sent send(Request request, JsonNode overrideBody, Vars vars) {
        String path = Substitution.resolve(request.path(), vars);
        String query = buildQuery(Substitution.resolve(request.query(), vars));
        URI uri = URI.create(baseUrl + path + (query.isEmpty() ? "" : "?" + query));

        HttpRequest.BodyPublisher publisher;
        String impliedContentType = null;
        if (!request.form().isEmpty()) {
            publisher = HttpRequest.BodyPublishers.ofString(buildForm(Substitution.resolve(request.form(), vars)));
            impliedContentType = "application/x-www-form-urlencoded";
        } else if (overrideBody != null) {
            publisher = HttpRequest.BodyPublishers.ofString(Json.write(overrideBody));
            impliedContentType = "application/json";
        } else {
            publisher = HttpRequest.BodyPublishers.noBody();
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .method(request.method(), publisher)
                .timeout(REQUEST_TIMEOUT);
        if (impliedContentType != null) builder.header("Content-Type", impliedContentType);
        if (!cookieJar.isEmpty()) builder.header("Cookie", cookieHeader());
        Substitution.resolve(request.headers(), vars).forEach(builder::header);
        if (request.auth() != null) builder.header("Authorization", "Bearer " + Substitution.resolve(request.auth(), vars));

        try {
            HttpResponse<byte[]> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            updateCookieJar(response.headers());
            return new Sent(response.statusCode(), response.headers(), response.body(), path);
        } catch (IOException e) {
            throw new IllegalStateException(request.method() + " " + path + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(request.method() + " " + path + " interrupted", e);
        }
    }

    private String cookieHeader() {
        StringBuilder sb = new StringBuilder();
        cookieJar.forEach((name, value) -> {
            if (!sb.isEmpty()) sb.append("; ");
            sb.append(name).append('=').append(value);
        });
        return sb.toString();
    }

    /// Every `Set-Cookie` on the response updates the jar: an empty value
    /// (how [io.flowcatalyst.platform.auth.login.SessionCookie#clear] logs
    /// out) removes the cookie, anything else (over)writes it — attributes
    /// (`Path`, `Secure`, `SameSite`, …) are not tracked, since this jar
    /// exists only to keep the session usable over the harness's own
    /// loopback HTTP transport (class doc SPEC? note), not to model a
    /// browser's full cookie-jar semantics.
    private void updateCookieJar(HttpHeaders headers) {
        for (String setCookie : headers.allValues("Set-Cookie")) {
            String pair = setCookie.split(";", 2)[0];
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            String name = pair.substring(0, eq).trim();
            String value = pair.substring(eq + 1).trim();
            if (value.isEmpty()) {
                cookieJar.remove(name);
            } else {
                cookieJar.put(name, value);
            }
        }
    }

    private static String buildQuery(Map<String, String> query) {
        if (query.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        query.forEach((k, v) -> {
            if (!sb.isEmpty()) sb.append('&');
            sb.append(URLEncoder.encode(k, StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(v, StandardCharsets.UTF_8));
        });
        return sb.toString();
    }

    private static String buildForm(Map<String, String> form) {
        return buildQuery(form);
    }

    /// The step's [StepRecord]: status, the [ComparedHeaders] subset, and the
    /// body classified per spec §4 — JSON when the content type says so,
    /// SHA-256 for a known binary body (the QR PNG, the OpenAPI document),
    /// raw text otherwise.
    static StepRecord toRecord(int status, HttpHeaders headers, byte[] bodyBytes) {
        Map<String, String> compared = new LinkedHashMap<>();
        for (String name : ComparedHeaders.NAMES) {
            headers.firstValue(name).ifPresent(v -> compared.put(name, v));
        }
        String contentType = headers.firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
        if (contentType.contains("json")) {
            JsonNode body = bodyBytes.length == 0 ? Json.MAPPER.getNodeFactory().nullNode() : Json.MAPPER.readTree(bodyBytes);
            return StepRecord.json(status, compared, body);
        }
        if (contentType.startsWith("image/png") || contentType.contains("application/openapi")) {
            return StepRecord.hashed(status, compared, sha256Hex(bodyBytes));
        }
        return StepRecord.text(status, compared, new String(bodyBytes, StandardCharsets.UTF_8));
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /// A 2xx body whose root carries a string `id` is a created (or fetched)
    /// row; its id is captured as `<step>.id` even when the scenario did not
    /// ask, so a later unfiltered list on either side masks it (rule 1) instead
    /// of showing two different TSIDs for the same row. An explicit capture of
    /// the same value keeps its own name (rule 1 prefers the scenario's names).
    static void autoCaptureId(Step step, Sent sent, Vars vars) {
        if (sent.status() < 200 || sent.status() >= 300) return;
        JsonNode body = sent.jsonBodyOrNull();
        if (body == null) return;
        autoCapture(body, "", step.id(), vars);
    }

    /// Members that are per-side by construction — ids, secrets, tokens,
    /// cursors that encode an id, links that carry a token — anywhere in a 2xx
    /// body. Captured quietly under `auto:<member>` so a later response
    /// carrying the same value masks it (rule 1) with a label both sides share.
    private static final java.util.regex.Pattern AUTO_CAPTURE_NAME =
            java.util.regex.Pattern.compile("^(id|.*Id|.*Secret|.*SecretRef|.*Cursor|.*Token|.*Url|.*Link|challenge)$");

    private static void autoCapture(JsonNode node, String pointer, String stepId, Vars vars) {
        if (node.isObject()) {
            node.properties().forEach(e -> {
                JsonNode v = e.getValue();
                if (v.isString() && AUTO_CAPTURE_NAME.matcher(e.getKey()).matches() && v.asString().length() >= Normaliser.MIN_SUBSTRING_CAPTURE) {
                    // Labelled by member name only: the same row can surface at different
                    // list positions on the two sides, and the label must still agree.
                    vars.captureQuietly("auto:" + e.getKey(), v.asString());
                } else {
                    autoCapture(v, pointer + "/" + e.getKey(), stepId, vars);
                }
            });
        } else if (node.isArray()) {
            int i = 0;
            for (JsonNode child : node) autoCapture(child, pointer + "/" + i++, stepId, vars);
        }
    }

    /// Applies `step.capture()` against the raw (unnormalised) response.
    ///
    /// @throws IllegalStateException a named header/cookie is absent, or a
    ///                                JSON Pointer resolves to nothing (a
    ///                                scenario error, spec §3)
    private static void capture(Step step, Sent sent, Vars vars) {
        step.capture().forEach((name, spec) -> {
            String value;
            if (spec.startsWith("header:")) {
                String header = spec.substring("header:".length());
                value = sent.headers().firstValue(header)
                        .orElseThrow(() -> new IllegalStateException("capture '" + name + "': header " + header + " not present"));
            } else if (spec.startsWith("location-param:")) {
                String param = spec.substring("location-param:".length());
                String location = sent.headers().firstValue("Location")
                        .orElseThrow(() -> new IllegalStateException("capture '" + name + "': Location header not present"));
                value = queryParam(location, param)
                        .orElseThrow(() -> new IllegalStateException("capture '" + name + "': Location has no query parameter " + param));
            } else if (spec.startsWith("param:")) {
                // `param:<pointer>?<name>` — a query parameter of a URL held in a body member
                // (the invite link's token). The pointer is RFC 6901 into the JSON body.
                String rest = spec.substring("param:".length());
                int q = rest.lastIndexOf('?');
                if (q < 0) throw new IllegalStateException("capture '" + name + "': param:<pointer>?<name> expected, got " + spec);
                JsonNode body = sent.jsonBodyOrNull();
                JsonNode at = body == null ? null : body.at(rest.substring(0, q));
                if (at == null || at.isMissingNode() || !at.isString()) {
                    throw new IllegalStateException("capture '" + name + "': pointer " + rest.substring(0, q) + " is not a string in the response body");
                }
                String param = rest.substring(q + 1);
                value = queryParam(at.asString(), param)
                        .orElseThrow(() -> new IllegalStateException("capture '" + name + "': " + rest.substring(0, q) + " has no query parameter " + param));
            } else if (spec.startsWith("cookie:")) {
                String cookie = spec.substring("cookie:".length());
                // The session cookie is `__Host-fc_session` on the Java side since the cookie
                // hardening (docs/spec/cookie-hardening.md, owner-approved 2026-09-24) and still
                // `fc_session` on Go's: the same cookie by its hardened name, not a difference to
                // capture around.
                value = cookieValue(sent.headers(), cookie)
                        .or(() -> SESSION_COOKIE.equals(cookie)
                                ? cookieValue(sent.headers(), HARDENED_SESSION_COOKIE) : Optional.empty())
                        .orElseThrow(() -> new IllegalStateException("capture '" + name + "': cookie " + cookie + " not present"));
            } else {
                JsonNode body = sent.jsonBodyOrNull();
                JsonNode at = body == null ? null : body.at(spec);
                if (at == null || at.isMissingNode()) {
                    throw new IllegalStateException("capture '" + name + "': pointer " + spec + " not present in the response body");
                }
                value = at.isString() ? at.asString() : Json.write(at);
            }
            // An empty capture would later mask every empty string in every body on that side
            // (rule 1's whole-value form matched "" == "" on 2026-09-06 and painted 90 steps red).
            if (value.isEmpty()) {
                throw new IllegalStateException("capture '" + name + "': " + spec + " resolved to an empty value");
            }
            vars.capture(name, value);
        });
    }

    /// One decoded query parameter of a URL (`location-param:<name>` — the
    /// authorization code an `/oauth/authorize` redirect carries).
    static java.util.Optional<String> queryParam(String url, String param) {
        int q = url.indexOf('?');
        if (q < 0) return java.util.Optional.empty();
        String query = url.substring(q + 1);
        int hash = query.indexOf('#');
        if (hash >= 0) query = query.substring(0, hash);
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            if (java.net.URLDecoder.decode(k, StandardCharsets.UTF_8).equals(param)) {
                return java.util.Optional.of(eq < 0 ? "" : java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return java.util.Optional.empty();
    }

    /// The value of one cookie out of every `Set-Cookie` response header.
    private static java.util.Optional<String> cookieValue(HttpHeaders headers, String cookieName) {
        for (String setCookie : headers.allValues("Set-Cookie")) {
            String pair = setCookie.split(";", 2)[0];
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).trim().equals(cookieName)) {
                return java.util.Optional.of(pair.substring(eq + 1).trim());
            }
        }
        return java.util.Optional.empty();
    }
}
