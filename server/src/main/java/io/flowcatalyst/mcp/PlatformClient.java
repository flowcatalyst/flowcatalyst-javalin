package io.flowcatalyst.mcp;

import io.flowcatalyst.platform.shared.json.Json;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

/// A read-only HTTP client for the platform API, authenticating with a
/// bearer token (`docs/spec/mcp.md` §1/§2; Go `pkg/fcsdk/client` `Get`).
/// Every read comes back as a parsed [JsonNode]; [#getPretty] additionally
/// renders it the way every tool result and resource body does — Jackson's
/// default pretty printer, matching the platform's JSON verbatim
/// (`TestToolReturnsPrettyJSONNotGoMap`).
public final class PlatformClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final String baseUrl;
    private final HttpClient httpClient;
    private final AuthMode auth;

    public PlatformClient(String baseUrl, AuthMode auth) {
        this(baseUrl, HttpClient.newHttpClient(), auth);
    }

    public PlatformClient(String baseUrl, HttpClient httpClient, AuthMode auth) {
        this.baseUrl = trimTrailingSlash(Objects.requireNonNull(baseUrl, "baseUrl"));
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.auth = Objects.requireNonNull(auth, "auth");
    }

    /// How a call authenticates: [None] when neither a `client_credentials`
    /// pair nor the interim static bearer is configured — every call fails
    /// fast with [McpConfig#MISSING_CREDENTIALS_MESSAGE] rather than reaching
    /// the platform unauthenticated (`docs/spec/mcp.md` §1: "every tool
    /// answers the error"); [Dynamic] mints/caches via [TokenManager] when a
    /// client id and secret are configured; [Static] is the Java-only
    /// `FC_MCP_PLATFORM_AUTH_TOKEN` interim (§2), used only when no client
    /// id/secret is configured.
    public sealed interface AuthMode {
        record None() implements AuthMode {
        }

        record Static(String token) implements AuthMode {
        }

        record Dynamic(TokenManager tokenManager) implements AuthMode {
        }

        /// Chooses the mode: a configured `client_credentials` pair always
        /// wins — it is the durable answer once the Java platform has its
        /// own `/oauth/token`; the static token is the fallback while it
        /// does not; neither configured is [None].
        static AuthMode resolve(McpConfig config, TokenManager tokenManager, String staticToken) {
            if (config.hasCredentials()) {
                return new Dynamic(tokenManager);
            }
            if (staticToken != null && !staticToken.isBlank()) {
                return new Static(staticToken);
            }
            return new None();
        }
    }

    /// Fetches `path` and returns the parsed JSON body. Throws
    /// [McpConfig.NoCredentialsException] — with no HTTP call at all — when
    /// [AuthMode.None]; throws [PlatformException] on a non-2xx response.
    public JsonNode get(String path) {
        if (auth instanceof AuthMode.None) {
            throw new McpConfig.NoCredentialsException(McpConfig.MISSING_CREDENTIALS_MESSAGE);
        }
        var bearer = switch (auth) {
            case AuthMode.None ignored -> null;
            case AuthMode.Static(var token) -> token;
            case AuthMode.Dynamic(var tokenManager) -> tokenManager.token();
        };

        var builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .GET()
                .timeout(TIMEOUT)
                .header("Accept", "application/json");
        if (bearer != null && !bearer.isEmpty()) {
            builder.header("Authorization", "Bearer " + bearer);
        }

        HttpResponse<String> response;
        try {
            response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new PlatformException(0, "request failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PlatformException(0, "request interrupted", e);
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new PlatformException(response.statusCode(), describeError(response), null);
        }
        var body = response.body();
        if (body == null || body.isBlank()) {
            return JsonNodeFactory.instance.nullNode();
        }
        try {
            return Json.MAPPER.readTree(body);
        } catch (JacksonException e) {
            throw new PlatformException(response.statusCode(), "decode response: " + e.getMessage(), e);
        }
    }

    /// [#get] pretty-printed — every tool result and resource body's shape.
    public String getPretty(String path) {
        return pretty(get(path));
    }

    /// Fetches `path`, mapping a 404 to `null` instead of throwing — the
    /// tolerance `get_application_capabilities`'s bundle needs for its
    /// optional sub-resources (`docs/spec/mcp.md` §3). Any other error still
    /// propagates.
    public JsonNode getTolerating404(String path) {
        try {
            return get(path);
        } catch (PlatformException e) {
            if (e.status() == 404) {
                return null;
            }
            throw e;
        }
    }

    /// Jackson's default pretty printer — the shared rendering every tool
    /// result and resource body uses.
    public static String pretty(JsonNode node) {
        try {
            return Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (JacksonException e) {
            throw new IllegalStateException("pretty-print failed", e);
        }
    }

    /// The platform's error envelope (`{"error"/"code", "message"}`, or the
    /// OAuth-shaped `{"error","error_description"}`) rendered as text; falls
    /// back to the raw body, then to a bare status line.
    private static String describeError(HttpResponse<String> response) {
        var body = response.body();
        if (body == null || body.isBlank()) {
            return "platform returned " + response.statusCode();
        }
        try {
            var node = Json.MAPPER.readTree(body);
            var code = textField(node, "error");
            if (code == null) code = textField(node, "code");
            var message = textField(node, "message");
            if (message == null) message = textField(node, "error_description");
            if (code != null || message != null) {
                return "platform returned " + response.statusCode()
                        + (code != null ? " " + code : "")
                        + (message != null ? ": " + message : "");
            }
        } catch (JacksonException ignored) {
            // Not a structured envelope; fall through to the raw body.
        }
        return "platform returned " + response.statusCode() + ": " + body.trim();
    }

    private static String textField(JsonNode node, String field) {
        var value = node.get(field);
        return value != null && value.isString() && !value.asString().isEmpty() ? value.asString() : null;
    }

    private static String trimTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    /// Escapes one path segment (an id or code) for a platform URL — the
    /// shared helper [McpTools] and [McpResources] both build `/api/**`
    /// paths with. `URLEncoder` is form (`application/x-www-form-urlencoded`)
    /// encoding, whose one difference from RFC 3986 path encoding that
    /// matters here is the space: `+` instead of `%20`, fixed up below. Ids
    /// in practice are TSIDs/codes with no other reserved characters.
    public static String escapePathSegment(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /// Raised on a non-2xx platform response; its message carries the
    /// platform's error envelope text (`docs/spec/mcp.md` §3: "Platform
    /// errors (non-2xx) become tool errors carrying the platform's error
    /// envelope text").
    public static final class PlatformException extends RuntimeException {
        private final int status;

        public PlatformException(int status, String message, Throwable cause) {
            super(message, cause);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }
}
