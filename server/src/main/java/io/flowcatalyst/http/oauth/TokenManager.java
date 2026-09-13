package io.flowcatalyst.http.oauth;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.flowcatalyst.platform.shared.json.Json;
import tools.jackson.core.JacksonException;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/// Mints and caches an OAuth2 `client_credentials` access token for the MCP
/// server's calls to the platform API (`docs/spec/mcp.md` §2; Go
/// `internal/mcp/auth.go`). POSTs the standard form-encoded grant to
/// `{baseUrl}/oauth/token`, caches the access token in memory, and refreshes
/// it [#REFRESH_BUFFER] ahead of expiry. Thread-safe.
public final class TokenManager {

    /// How far ahead of expiry a cached token is refreshed — covers clock
    /// skew and in-flight request latency so the platform never sees an
    /// already-expired token. A token is reused only while
    /// `now + REFRESH_BUFFER < expiresAt`.
    public static final Duration REFRESH_BUFFER = Duration.ofSeconds(60);

    /// The platform's access-token TTL, used only when a token response
    /// omits (or zeroes) `expires_in` — without a default, such a token
    /// would be treated as already stale and refetched on every call.
    private static final Duration DEFAULT_TTL = Duration.ofHours(1);

    private final String tokenUrl;
    private final String clientId;
    private final String clientSecret;
    /// Optional requested-scope narrowing (Go's standalone-outbox
    /// `clientCredentialsTokenSource.scope`) — sent as the form field
    /// `scope` only when non-blank; blank/`null` means "client ceiling"
    /// (no narrowing requested).
    private final String scope;
    private final HttpClient httpClient;
    private final Clock clock;

    /// Guards `cached`: a refresh must not race itself into two concurrent
    /// fetches for the same expiring token.
    private final ReentrantLock lock = new ReentrantLock();
    private CachedToken cached;

    public TokenManager(String baseUrl, String clientId, String clientSecret) {
        this(baseUrl, clientId, clientSecret, null, HttpClient.newHttpClient(), Clock.systemUTC());
    }

    /// @param scope optional requested scope (Go's `--scope` /
    ///               `FC_OUTBOX_SCOPE`); `null`/blank omits the form field
    public TokenManager(String baseUrl, String clientId, String clientSecret, String scope) {
        this(baseUrl, clientId, clientSecret, scope, HttpClient.newHttpClient(), Clock.systemUTC());
    }

    public TokenManager(String baseUrl, String clientId, String clientSecret, HttpClient httpClient, Clock clock) {
        this(baseUrl, clientId, clientSecret, null, httpClient, clock);
    }

    public TokenManager(String baseUrl, String clientId, String clientSecret, String scope,
                         HttpClient httpClient, Clock clock) {
        this.tokenUrl = trimTrailingSlash(Objects.requireNonNull(baseUrl, "baseUrl")) + "/oauth/token";
        this.clientId = Objects.requireNonNull(clientId, "clientId");
        this.clientSecret = Objects.requireNonNull(clientSecret, "clientSecret");
        this.scope = scope;
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /// Returns a valid bearer token (no `Bearer ` prefix), fetching a fresh
    /// one when the cache is empty or within [#REFRESH_BUFFER] of expiry.
    public String token() {
        lock.lock();
        try {
            var now = clock.instant();
            if (cached != null && now.plus(REFRESH_BUFFER).isBefore(cached.expiresAt())) {
                return cached.accessToken();
            }
            var fresh = fetch();
            cached = fresh;
            return fresh.accessToken();
        } finally {
            lock.unlock();
        }
    }

    /// Drops the cached token so the next [#token()] re-mints — called by a
    /// caller (e.g. [io.flowcatalyst.outbox.HttpDispatcher.TokenSource#invalidate()])
    /// after a 401, mirroring Go's standalone-outbox token source.
    public void invalidate() {
        lock.lock();
        try {
            cached = null;
        } finally {
            lock.unlock();
        }
    }

    private CachedToken fetch() {
        var form = "grant_type=client_credentials"
                + "&client_id=" + encode(clientId)
                + "&client_secret=" + encode(clientSecret)
                + (scope != null && !scope.isBlank() ? "&scope=" + encode(scope) : "");
        var request = HttpRequest.newBuilder(URI.create(tokenUrl))
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new TokenException("token request: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TokenException("token request interrupted", e);
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            var body = response.body() == null ? "" : response.body().trim();
            throw new TokenException("token endpoint returned " + response.statusCode()
                    + (body.isEmpty() ? "" : ": " + body));
        }

        TokenResponse parsed;
        try {
            parsed = Json.read(response.body(), TokenResponse.class);
        } catch (JacksonException e) {
            throw new TokenException("decode token response: " + e.getMessage(), e);
        }
        if (parsed == null || parsed.accessToken() == null || parsed.accessToken().isBlank()) {
            throw new TokenException("token endpoint returned no access_token");
        }
        var ttl = parsed.expiresIn() != null && parsed.expiresIn() > 0
                ? Duration.ofSeconds(parsed.expiresIn())
                : DEFAULT_TTL;
        return new CachedToken(parsed.accessToken(), clock.instant().plus(ttl));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String trimTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private record CachedToken(String accessToken, Instant expiresAt) {
    }

    /// The subset of the RFC 6749 token response this reads.
    private record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("expires_in") Long expiresIn) {
    }

    /// Raised on any token-endpoint failure — surfaced to the tool call as
    /// its error text (`docs/spec/mcp.md` §2: "non-2xx → error surfaced").
    public static final class TokenException extends RuntimeException {
        public TokenException(String message) {
            super(message);
        }

        public TokenException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
