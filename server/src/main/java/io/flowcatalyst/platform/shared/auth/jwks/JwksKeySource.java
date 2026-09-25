package io.flowcatalyst.platform.shared.auth.jwks;

import io.flowcatalyst.platform.shared.json.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

import java.math.BigInteger;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.RSAPublicKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/// Caches the platform's JWKS for a process that verifies platform tokens
/// without being the platform: the function host (spec
/// `function-host-listener.md` §3) and the router (`router-api-auth.md`). An
/// unknown `kid` refetches **at most once per 30 s** — a flood of bad tokens
/// must not become a flood of JWKS requests. The JWK → [RSAPublicKey]
/// decoding is the inverse of the platform's own encoder
/// ([io.flowcatalyst.platform.auth.oauth.OAuthDiscoveryApi#jwk]): unpadded
/// base64url big-endian `n`/`e`.
///
/// **Issuer discovery (defect fix).** `platformUrl` is the address the HOST
/// uses to reach the platform (`FC_FN_PLATFORM_URL` — a Service Connect
/// alias in production, `http://127.0.0.1:<port>` in fcdev); it is NOT the
/// value a token's `iss` carries, which is always the platform's own
/// external base URL. So this class first fetches
/// `<platformUrl>/.well-known/openid-configuration` and uses ITS `issuer` —
/// lazily, cached alongside the JWKS, subject to the same 30 s floor on
/// failure. `jwks_uri` from that document is followed only when it names the
/// SAME origin as `platformUrl`; a `jwks_uri` on another origin is the
/// platform's own EXTERNAL address, which the host may not be able to reach
/// at all (it is behind the alias/loopback `platformUrl` names instead) — in
/// that case keys still come from `<platformUrl>/.well-known/jwks.json`.
/// Until discovery has succeeded at least once, [#issuer] returns `null` and
/// [BearerAuthenticator] answers every `platform`-auth call `401`.
public final class JwksKeySource {

    private static final Logger LOG = LoggerFactory.getLogger(JwksKeySource.class);
    private static final Duration REFETCH_FLOOR = Duration.ofSeconds(30);

    private final HttpClient http;
    private final String platformUrl;
    private final Clock clock;

    private volatile Map<String, RSAPublicKey> keysByKid = Map.of();
    private volatile Instant lastFetch = Instant.EPOCH;
    private final Object fetchLock = new Object();

    /// `null` until discovery (`<platformUrl>/.well-known/openid-configuration`)
    /// has succeeded at least once; cached indefinitely once set (spec: "cached
    /// with the JWKS" — never re-discovered just because a kid is unknown).
    private volatile String discoveredIssuer;

    /// Where the JWKS is actually fetched from — `<platformUrl>/.well-known/jwks.json`
    /// unless discovery's own `jwks_uri` is on the SAME origin as `platformUrl`, in
    /// which case that exact URL is used instead. Set together with [#discoveredIssuer].
    private volatile String jwksFetchUrl;

    /// The discovery document's `authorization_endpoint`: the platform's
    /// EXTERNAL authorize URL, which a browser follows (the router dashboard's
    /// sign-in, `router-api-auth.md` rule 6). `null` until discovery succeeds
    /// or when the document names none. Set together with [#discoveredIssuer].
    private volatile String discoveredAuthorizationEndpoint;

    /// When discovery was last attempted through [#ensureDiscovered]: its own
    /// 30 s floor, separate from the JWKS one, so an on-demand discovery never
    /// delays the key fetch a following token needs.
    private volatile Instant lastDiscoveryAttempt = Instant.EPOCH;

    /// Test seam: how many times [#fetch] actually hit the JWKS endpoint
    /// (discovery fetches are not counted here).
    private final AtomicInteger fetchCount = new AtomicInteger();

    public JwksKeySource(HttpClient http, String platformUrl) {
        this(http, platformUrl, Clock.systemUTC());
    }

    /// @param clock injectable so a test can pin the 30 s refetch floor without sleeping
    public JwksKeySource(HttpClient http, String platformUrl, Clock clock) {
        this.http = Objects.requireNonNull(http, "http");
        this.platformUrl = Objects.requireNonNull(platformUrl, "platformUrl");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /// Every key currently cached, current-then-previous order as the
    /// platform's own JWKS lists them.
    public List<RSAPublicKey> keys() {
        return List.copyOf(keysByKid.values());
    }

    /// The issuer discovered from `<platformUrl>/.well-known/openid-configuration`,
    /// or `null` if discovery has never yet succeeded — the caller
    /// ([BearerAuthenticator]) must treat `null` as "not authenticated", never
    /// fall back to `platformUrl` itself (that was the original defect).
    public String issuer() {
        return discoveredIssuer;
    }

    /// The platform's external authorize URL from its discovery document,
    /// discovering first if that has never succeeded, at most once per 30 s.
    public java.util.Optional<String> authorizationEndpoint() {
        ensureDiscovered();
        return java.util.Optional.ofNullable(discoveredAuthorizationEndpoint);
    }

    /// Runs discovery if it has never succeeded, subject to its own 30 s floor.
    ///
    /// @return whether discovery has (now) succeeded
    public boolean ensureDiscovered() {
        if (discoveredIssuer != null) {
            return true;
        }
        synchronized (fetchLock) {
            if (discoveredIssuer != null) {
                return true;
            }
            Instant now = clock.instant();
            if (Duration.between(lastDiscoveryAttempt, now).compareTo(REFETCH_FLOOR) < 0) {
                return false;
            }
            lastDiscoveryAttempt = now;
            return discover();
        }
    }

    /// Refetches — subject to the 30 s floor — only when `kid` is not
    /// already cached; a `null`/unreadable `kid` is treated as unknown, same
    /// as a `kid` genuinely absent from the JWKS document.
    ///
    /// @return `true` if `kid` is (now) cached, `false` if it still is not
    ///         (a genuinely unknown key, or the floor blocked a refetch)
    public boolean ensureKnown(String kid) {
        if (kid != null && keysByKid.containsKey(kid)) {
            return true;
        }
        synchronized (fetchLock) {
            if (kid != null && keysByKid.containsKey(kid)) {
                return true;
            }
            Instant now = clock.instant();
            if (Duration.between(lastFetch, now).compareTo(REFETCH_FLOOR) < 0) {
                return false; // floor not elapsed since the last fetch
            }
            lastFetch = now;
            fetch();
            return kid != null && keysByKid.containsKey(kid);
        }
    }

    /// Test-only seam: how many real JWKS fetches have happened.
    int fetchCountForTest() {
        return fetchCount.get();
    }

    /// Called only from inside [#ensureKnown]'s floor-gated section — so a
    /// discovery failure logs at most once per [#REFETCH_FLOOR] interval,
    /// never once per request.
    private void fetch() {
        if (discoveredIssuer == null && !discover()) {
            return; // no issuer yet: nothing to verify a token against (caller answers 401)
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(jwksFetchUrl))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            fetchCount.incrementAndGet();
            if (response.statusCode() != 200) {
                // Leave the previous cache in place — a JWKS outage must not lock every caller out.
                // Logged (at most once per floor interval, see above): after a key rotation this is
                // the operator's only trace of why bearer calls answer 401.
                LOG.atWarn().setMessage("could not fetch the platform's JWKS: unexpected status; keeping the cached keys")
                        .addKeyValue("jwksUrl", jwksFetchUrl)
                        .addKeyValue("status", response.statusCode())
                        .log();
                return;
            }
            keysByKid = parse(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException e) {
            // Network or parse failure: leave the previous cache in place.
            LOG.atWarn().setMessage("could not fetch the platform's JWKS; keeping the cached keys")
                    .addKeyValue("jwksUrl", jwksFetchUrl)
                    .setCause(e)
                    .log();
        }
    }

    /// Fetches `<platformUrl>/.well-known/openid-configuration` and, on
    /// success, sets [#discoveredIssuer] and [#jwksFetchUrl]. `jwks_uri` is
    /// followed only when it names the same origin as `platformUrl` — see the
    /// class doc: a foreign origin is the platform's own EXTERNAL address,
    /// which the host may not be able to reach, so keys still come from
    /// `<platformUrl>/.well-known/jwks.json` in that case.
    ///
    /// @return whether discovery succeeded
    private boolean discover() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(platformUrl + "/.well-known/openid-configuration"))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                LOG.atWarn().setMessage("could not discover the platform's issuer: unexpected status")
                        .addKeyValue("platformUrl", platformUrl)
                        .addKeyValue("status", response.statusCode())
                        .log();
                return false;
            }
            JsonNode root = Json.MAPPER.readTree(response.body());
            String issuer = root.path("issuer").asString(null);
            if (issuer == null || issuer.isBlank()) {
                LOG.atWarn().setMessage("platform discovery document has no issuer")
                        .addKeyValue("platformUrl", platformUrl)
                        .log();
                return false;
            }
            String defaultJwksUrl = platformUrl + "/.well-known/jwks.json";
            String discoveredJwksUri = root.path("jwks_uri").asString(null);
            jwksFetchUrl = (discoveredJwksUri != null && sameOrigin(discoveredJwksUri, platformUrl))
                    ? discoveredJwksUri
                    : defaultJwksUrl;
            String authorize = root.path("authorization_endpoint").asString(null);
            discoveredAuthorizationEndpoint = authorize == null || authorize.isBlank() ? null : authorize;
            discoveredIssuer = issuer;
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException | RuntimeException e) {
            LOG.atWarn().setMessage("could not discover the platform's issuer via /.well-known/openid-configuration; "
                            + "bearer auth will reject platform tokens until this succeeds")
                    .addKeyValue("platformUrl", platformUrl)
                    .setCause(e)
                    .log();
            return false;
        }
    }

    /// Scheme + host + port match — the same-origin test that decides
    /// whether a discovered `jwks_uri` is safe to follow (see [#discover]).
    private static boolean sameOrigin(String a, String b) {
        try {
            URI ua = URI.create(a);
            URI ub = URI.create(b);
            return Objects.equals(ua.getScheme(), ub.getScheme())
                    && Objects.equals(ua.getHost(), ub.getHost())
                    && normalizedPort(ua) == normalizedPort(ub);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static int normalizedPort(URI u) {
        if (u.getPort() != -1) {
            return u.getPort();
        }
        return "https".equalsIgnoreCase(u.getScheme()) ? 443 : 80;
    }

    private static Map<String, RSAPublicKey> parse(String body) {
        JsonNode root = Json.MAPPER.readTree(body);
        Map<String, RSAPublicKey> out = new LinkedHashMap<>();
        for (JsonNode key : root.path("keys")) {
            String kid = key.path("kid").asString(null);
            String n = key.path("n").asString(null);
            String e = key.path("e").asString(null);
            if (kid == null || n == null || e == null) {
                continue; // an unreadable key entry is dropped, not fatal to the rest
            }
            try {
                out.put(kid, toRsaPublicKey(n, e));
            } catch (RuntimeException ignored) {
                // dropped, same reasoning
            }
        }
        return Map.copyOf(out);
    }

    private static RSAPublicKey toRsaPublicKey(String n, String e) {
        BigInteger modulus = new BigInteger(1, Base64.getUrlDecoder().decode(n));
        BigInteger exponent = new BigInteger(1, Base64.getUrlDecoder().decode(e));
        try {
            KeyFactory kf = KeyFactory.getInstance("RSA");
            return (RSAPublicKey) kf.generatePublic(new RSAPublicKeySpec(modulus, exponent));
        } catch (NoSuchAlgorithmException | InvalidKeySpecException ex) {
            throw new IllegalStateException("could not build an RSA public key from JWKS", ex);
        }
    }
}
