package io.flowcatalyst.fnhost.http;

import io.flowcatalyst.platform.shared.json.Json;
import tools.jackson.databind.JsonNode;

import java.math.BigInteger;
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

/// Caches the platform's `<platform>/.well-known/jwks.json` (spec
/// `function-host-listener.md` §3): an unknown `kid` refetches **at most
/// once per 30 s** — a flood of bad tokens must not become a flood of JWKS
/// requests. The JWK → [RSAPublicKey] decoding is the inverse of the
/// platform's own encoder ([io.flowcatalyst.platform.auth.oauth.OAuthDiscoveryApi#jwk]):
/// unpadded base64url big-endian `n`/`e`.
public final class JwksKeySource {

    private static final Duration REFETCH_FLOOR = Duration.ofSeconds(30);

    private final HttpClient http;
    private final String platformUrl;
    private final Clock clock;

    private volatile Map<String, RSAPublicKey> keysByKid = Map.of();
    private volatile Instant lastFetch = Instant.EPOCH;
    private final Object fetchLock = new Object();

    /// Test seam: how many times [#fetch] actually hit the network.
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

    private void fetch() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(platformUrl + "/.well-known/jwks.json"))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            fetchCount.incrementAndGet();
            if (response.statusCode() != 200) {
                return; // leave the previous cache in place — a JWKS outage must not lock every caller out
            }
            keysByKid = parse(response.body());
        } catch (Exception e) {
            // Network/parse failure: leave the previous cache in place.
        }
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
