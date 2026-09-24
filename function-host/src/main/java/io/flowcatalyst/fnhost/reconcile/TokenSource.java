package io.flowcatalyst.fnhost.reconcile;

import io.flowcatalyst.platform.shared.json.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/// The host's bearer credential: `client_credentials` against
/// `<platform>/oauth/token` (spec `function-host-reconciler.md` §1.1), cached
/// until 60s before expiry. [HttpControlPlane] calls [#token] for the
/// `Authorization` header on every request and [#refresh] once, only after a
/// 401 from the control plane itself — this class never decides on its own
/// that a cached token might be stale beyond the 60s margin. The client
/// secret and every minted token are masked out of [#toString] — neither
/// ever appears in a log line or an exception message (spec §3, R9).
public final class TokenSource {

    private static final Logger LOG = LoggerFactory.getLogger(TokenSource.class);

    /// A token is refreshed once it is within this margin of its declared
    /// expiry (spec §1.1: "cached until 60 s before expiry").
    private static final Duration EXPIRY_MARGIN = Duration.ofSeconds(60);
    /// A platform that accepts the connection and never answers must not stall the
    /// reconcile loop (the token is minted inside it) — the same bound the other
    /// control-plane calls use.
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient client;
    private final String platformUrl;
    private final String clientId;
    private final String clientSecret;
    private final Clock clock;

    /// Guards `cachedToken`/`cachedExpiry` — a mint is a network round trip,
    /// so this is held only around the cache check/replace, never across
    /// [HttpControlPlane]'s own request.
    private final Object lock = new Object();
    private String cachedToken;
    private Instant cachedExpiry;

    public TokenSource(HttpClient client, String platformUrl, String clientId, String clientSecret) {
        this(client, platformUrl, clientId, clientSecret, Clock.systemUTC());
    }

    public TokenSource(HttpClient client, String platformUrl, String clientId, String clientSecret, Clock clock) {
        this.client = Objects.requireNonNull(client, "client");
        this.platformUrl = Objects.requireNonNull(platformUrl, "platformUrl");
        this.clientId = Objects.requireNonNull(clientId, "clientId");
        this.clientSecret = Objects.requireNonNull(clientSecret, "clientSecret");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /// The cached token, minting a fresh one when there is none or the
    /// cached one is within [#EXPIRY_MARGIN] of expiring.
    ///
    /// @throws ControlPlaneException minting failed — [ControlPlaneException.Reason#UNAVAILABLE]
    public String token() throws ControlPlaneException {
        synchronized (lock) {
            if (cachedToken != null && clock.instant().isBefore(cachedExpiry.minus(EXPIRY_MARGIN))) {
                return cachedToken;
            }
            return mint();
        }
    }

    /// Forces a fresh mint regardless of the cached token's age — called by
    /// [HttpControlPlane] exactly once, after the control plane itself
    /// answers 401 to a request that carried the cached token (spec §1.1).
    ///
    /// @throws ControlPlaneException minting failed
    public String refresh() throws ControlPlaneException {
        synchronized (lock) {
            return mint();
        }
    }

    /// Caller holds [#lock].
    private String mint() throws ControlPlaneException {
        // debug-only (CONVENTIONS §10 exempts debug from the structured-fields rule) —
        // still never the secret or the token itself (spec §1.1, R9).
        LOG.atDebug().setMessage("minting a control-plane token")
                .addKeyValue("platform_url", platformUrl)
                .addKeyValue("client_id", clientId)
                .log();
        String form = "grant_type=client_credentials&client_id=" + urlEncode(clientId)
                + "&client_secret=" + urlEncode(clientSecret);
        HttpRequest request = HttpRequest.newBuilder(URI.create(platformUrl + "/oauth/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (java.io.IOException e) {
            throw new ControlPlaneException(ControlPlaneException.Reason.UNAVAILABLE, "minting a token failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ControlPlaneException(ControlPlaneException.Reason.UNAVAILABLE, "minting a token was interrupted", e);
        }
        if (response.statusCode() != 200) {
            throw new ControlPlaneException(ControlPlaneException.Reason.UNAVAILABLE,
                    "token endpoint returned " + response.statusCode());
        }
        JsonNode body = Json.MAPPER.readTree(response.body());
        String accessToken = body.path("access_token").asString(null);
        if (accessToken == null || accessToken.isBlank()) {
            throw new ControlPlaneException(ControlPlaneException.Reason.UNAVAILABLE, "token endpoint returned no access_token");
        }
        long expiresIn = body.path("expires_in").asLong(0);
        cachedToken = accessToken;
        cachedExpiry = clock.instant().plusSeconds(Math.max(expiresIn, 0));
        LOG.atDebug().setMessage("minted a control-plane token")
                .addKeyValue("client_id", clientId)
                .addKeyValue("expires_in", expiresIn)
                .log();
        return cachedToken;
    }

    private static String urlEncode(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /// Masked: neither the client secret nor a minted token ever appears
    /// (spec §1.1, R9).
    @Override
    public String toString() {
        return "TokenSource[platformUrl=" + platformUrl + ", clientId=" + clientId + "]";
    }
}
