package io.flowcatalyst.fcdev.fn;

import io.flowcatalyst.http.oauth.TokenManager;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.flowcatalyst.platform.shared.json.Json;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/// The ONE class that talks HTTP to the platform or the function host
/// (`docs/spec/function-developer-surface.md` §2: "An HTTP client class
/// (`FnClient`) holds every platform call; commands are thin"). Every `fn`
/// subcommand goes through this — never builds its own [HttpClient] call.
///
/// Two shapes of call:
///   - {@link #get}/{@link #post}/{@link #put}/{@link #delete} — JSON calls
///     against the PLATFORM (`baseUrl`), bearer-authenticated with a
///     client-credentials token cached for the process ([TokenManager]);
///     a non-2xx response is thrown as [FnClientException], the platform's
///     `{error, message, details}` envelope carried verbatim.
///   - {@link #raw} — an unauthenticated-by-default call to an ARBITRARY URL
///     (the function host's `/functions/…`, `fn invoke`'s job): never
///     throws on a non-2xx status — the response (status, headers, body) IS
///     the answer a developer asked to see.
public final class FnClient {

    private final String baseUrl;
    private final HttpClient http;
    private final TokenManager tokenManager;

    public FnClient(String baseUrl, String clientId, String clientSecret) {
        this(baseUrl, clientId, clientSecret, HttpClient.newHttpClient());
    }

    public FnClient(String baseUrl, String clientId, String clientSecret, HttpClient http) {
        this.baseUrl = trimTrailingSlash(Objects.requireNonNull(baseUrl, "baseUrl"));
        this.http = Objects.requireNonNull(http, "http");
        this.tokenManager = new TokenManager(this.baseUrl, clientId, clientSecret, null, http, Clock.systemUTC());
    }

    private FnClient(HttpClient http) {
        this.baseUrl = "";
        this.http = Objects.requireNonNull(http, "http");
        this.tokenManager = null;
    }

    /// A client with no resolvable credentials — for `fn invoke`'s
    /// {@link #raw} calls, which target the function HOST directly and need
    /// no platform token at all unless the call is versioned (spec
    /// §2/§4 E7: an unversioned `--webhook`/`auth: none` invoke must not be
    /// forced to configure `FLOWCATALYST_CLIENT_ID`/`_SECRET` just to reach a
    /// host that needs none). {@link #bearerToken()} on this instance throws.
    public static FnClient rawOnly() {
        return new FnClient(HttpClient.newHttpClient());
    }

    /// The cached client-credentials bearer token — used directly by `fn
    /// invoke` for a versioned call (`function-invocation.md` §2: "a
    /// versioned call gets the CLI's bearer token automatically").
    public String bearerToken() {
        if (tokenManager == null) {
            throw new IllegalStateException("no platform credentials configured for this call");
        }
        return tokenManager.token();
    }

    // ── platform JSON calls ─────────────────────────────────────────────

    public JsonNode get(String path) {
        return send("GET", path, null);
    }

    public JsonNode post(String path, JsonNode body) {
        return send("POST", path, body);
    }

    public JsonNode put(String path, JsonNode body) {
        return send("PUT", path, body);
    }

    public void delete(String path) {
        send("DELETE", path, null);
    }

    /// Streams `file`'s bytes as the request body (`function-artifact-upload.md`
    /// §3/§5: `fn publish`/`fn deploy` without `--artifact-ref` upload the jar
    /// this way) — `HttpRequest.BodyPublishers.ofFile`, never read into a byte
    /// array first, `Content-Type: application/octet-stream`, the same
    /// bearer auth as every other platform call. A non-2xx response is the
    /// same [FnClientException] mapping as {@link #send} (the platform's
    /// `{error, message, details}` envelope carried verbatim — a 413/422/503
    /// surfaces exactly like any other platform error).
    public JsonNode putFile(String path, Path file) {
        HttpRequest.BodyPublisher publisher;
        try {
            publisher = HttpRequest.BodyPublishers.ofFile(file);
        } catch (java.io.FileNotFoundException e) {
            throw new FnClientException("IO_ERROR", "could not read " + file + ": " + e.getMessage(), 0);
        }
        var builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Authorization", "Bearer " + tokenManager.token())
                .header("Content-Type", "application/octet-stream")
                .PUT(publisher);
        HttpResponse<String> resp = execute(builder.build(), HttpResponse.BodyHandlers.ofString(), baseUrl + path);
        int status = resp.statusCode();
        if (status >= 200 && status < 300) {
            String b = resp.body();
            return (b == null || b.isBlank()) ? null : Json.MAPPER.readTree(b);
        }
        throw toException(status, resp.body());
    }

    private JsonNode send(String method, String path, JsonNode body) {
        var builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Authorization", "Bearer " + tokenManager.token());
        if (body != null) {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<String> resp = execute(builder.build(), HttpResponse.BodyHandlers.ofString(), baseUrl + path);
        int status = resp.statusCode();
        if (status >= 200 && status < 300) {
            String b = resp.body();
            return (b == null || b.isBlank()) ? null : Json.MAPPER.readTree(b);
        }
        throw toException(status, resp.body());
    }

    private static FnClientException toException(int status, String body) {
        if (body != null && !body.isBlank()) {
            try {
                HttpError err = Json.MAPPER.readValue(body, HttpError.class);
                if (err.code() != null && !err.code().isBlank()) {
                    return new FnClientException(err.code(), err.message(), status, err.details());
                }
            } catch (RuntimeException ignored) {
                // not the platform's envelope — fall through to the generic form
            }
        }
        return new FnClientException("HTTP_" + status, "request failed with status " + status, status);
    }

    // ── arbitrary-URL raw calls (the function host) ─────────────────────

    /// One HTTP call to `url` with `headers` and `body` (may be empty), never
    /// throwing on the response status — `fn invoke` decides what a non-2xx
    /// means (a failing function is a failing command, but the HTTP call
    /// itself succeeded).
    public RawResponse raw(String method, String url, Map<String, String> headers, byte[] body) {
        var builder = HttpRequest.newBuilder(URI.create(url));
        headers.forEach(builder::header);
        HttpRequest.BodyPublisher publisher = (body == null || body.length == 0)
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(body);
        builder.method(method, publisher);
        HttpResponse<byte[]> resp = execute(builder.build(), HttpResponse.BodyHandlers.ofByteArray(), url);
        return new RawResponse(resp.statusCode(), resp.headers().map(), resp.body());
    }

    private <T> HttpResponse<T> execute(HttpRequest request, HttpResponse.BodyHandler<T> handler, String target) {
        try {
            return http.send(request, handler);
        } catch (IOException e) {
            throw new FnClientException("NETWORK_ERROR", "could not reach " + target + ": " + e.getMessage(), 0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FnClientException("INTERRUPTED", "request interrupted", 0);
        }
    }

    private static String trimTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    /// The answer to a {@link #raw} call.
    public record RawResponse(int status, Map<String, List<String>> headers, byte[] body) {
        public String bodyAsString() {
            return body == null ? "" : new String(body, StandardCharsets.UTF_8);
        }
    }
}
