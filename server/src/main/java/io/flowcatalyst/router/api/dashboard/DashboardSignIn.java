package io.flowcatalyst.router.api.dashboard;

import io.flowcatalyst.http.Exchange;
import io.flowcatalyst.http.Routes;
import io.flowcatalyst.platform.shared.auth.jwks.JwksKeySource;
import io.flowcatalyst.platform.shared.json.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/// The router dashboard's sign-in helpers (`docs/spec/router-api-auth.md`
/// rule 6): authorization code with PKCE against the platform, through a public
/// OAuth client.
///
/// - `GET {prefix}/dashboard/auth-config` tells the page where to send the
///   browser. The authorize URL comes from the platform's discovery document,
///   so it is the platform's external address, which the browser can reach.
/// - `POST {prefix}/dashboard/token` exchanges the code at the platform's
///   token endpoint and answers only the access token and its lifetime. The
///   router proxies because the page is on the router's origin: a direct
///   browser call would need CORS on the platform's token endpoint. The
///   refresh token and id token are dropped, and the page keeps the access
///   token in memory only.
///
/// Both routes are public ([io.flowcatalyst.router.api.auth.PlatformTokenFilter]),
/// since a signed-out browser needs them to sign in. Neither carries router data.
public final class DashboardSignIn {

    private static final Logger LOG = LoggerFactory.getLogger(DashboardSignIn.class);

    /// The permissions the dashboard asks for, and no more: a token held in a
    /// browser page carries only what the router needs.
    public static final String SCOPE = "platform:messaging:router:view platform:messaging:router:operate";

    public record AuthConfig(boolean enabled, String authorizationEndpoint, String clientId, String scope) {
    }

    public record TokenRequest(String code, String codeVerifier, String redirectUri) {
    }

    public record TokenResponse(String accessToken, long expiresIn) {
    }

    private final Optional<JwksKeySource> discovery;
    private final String platformUrl;
    private final String clientId;
    private final HttpClient http;

    /// @param discovery   the platform's discovery (shared with the token filter); empty when
    ///                    the router has no platform to talk to, or in dev mode
    /// @param platformUrl where the router reaches the platform's token endpoint
    /// @param clientId    `FC_ROUTER_DASHBOARD_CLIENT_ID`; blank leaves sign-in off
    public DashboardSignIn(Optional<JwksKeySource> discovery, String platformUrl, String clientId, HttpClient http) {
        this.discovery = discovery;
        this.platformUrl = platformUrl == null ? "" : platformUrl.strip();
        this.clientId = clientId == null ? "" : clientId.strip();
        this.http = http;
    }

    public static void register(Routes routes, String prefix, DashboardSignIn signIn) {
        String p = prefix == null || prefix.isBlank() ? "" : prefix;
        routes.get(p + "/dashboard/auth-config", signIn::authConfig);
        routes.post(p + "/dashboard/token", signIn::token);
    }

    public AuthConfig config() {
        if (clientId.isEmpty() || discovery.isEmpty()) {
            return new AuthConfig(false, null, null, null);
        }
        return discovery.get().authorizationEndpoint()
                .map(authorize -> new AuthConfig(true, authorize, clientId, SCOPE))
                .orElse(new AuthConfig(false, null, null, null));
    }

    private void authConfig(Exchange ctx) {
        ctx.json(config());
    }

    private void token(Exchange ctx) {
        if (!config().enabled()) {
            ctx.status(404).json(Map.of("error", "dashboard sign-in is not configured"));
            return;
        }
        TokenRequest req;
        try {
            req = ctx.bodyAsClass(TokenRequest.class);
        } catch (RuntimeException e) {
            ctx.status(400).json(Map.of("error", "body must be {code, codeVerifier, redirectUri}"));
            return;
        }
        if (blank(req.code()) || blank(req.codeVerifier()) || blank(req.redirectUri())) {
            ctx.status(400).json(Map.of("error", "code, codeVerifier and redirectUri are required"));
            return;
        }
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", req.code());
        form.put("code_verifier", req.codeVerifier());
        form.put("redirect_uri", req.redirectUri());
        form.put("client_id", clientId);
        HttpResponse<String> response;
        try {
            response = http.send(HttpRequest.newBuilder(URI.create(platformUrl + "/oauth/token"))
                            .timeout(Duration.ofSeconds(10))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString(encode(form)))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ctx.status(502).json(Map.of("error", "token exchange interrupted"));
            return;
        } catch (IOException | RuntimeException e) {
            LOG.atWarn().setMessage("router dashboard: the platform's token endpoint could not be reached")
                    .addKeyValue("platformUrl", platformUrl)
                    .setCause(e)
                    .log();
            ctx.status(502).json(Map.of("error", "the platform's token endpoint could not be reached"));
            return;
        }
        JsonNode body;
        try {
            body = Json.MAPPER.readTree(response.body());
        } catch (RuntimeException e) {
            body = null;
        }
        if (response.statusCode() != 200 || body == null || body.path("access_token").asString("").isEmpty()) {
            // The platform's OAuth error code (invalid_grant, …) is safe to pass on; its
            // description is not needed by the page.
            String error = body == null ? "token exchange failed" : body.path("error").asString("token exchange failed");
            int status = response.statusCode() >= 400 && response.statusCode() < 500 ? response.statusCode() : 502;
            ctx.status(status).json(Map.of("error", error));
            return;
        }
        ctx.json(new TokenResponse(body.path("access_token").asString(), body.path("expires_in").asLong(0)));
    }

    private static String encode(Map<String, String> form) {
        return form.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
