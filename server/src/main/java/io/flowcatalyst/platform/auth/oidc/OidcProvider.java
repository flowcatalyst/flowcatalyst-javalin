package io.flowcatalyst.platform.auth.oidc;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import io.flowcatalyst.platform.shared.json.Json;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/// One resolved OIDC relying-party client (`docs/spec/auth-identity.md`
/// §4.1–§4.2): the discovered endpoints, the JWKS source, the authorize
/// URL, the code exchange and id_token verification. Single-tenant
/// verification pins `iss` and `aud`; multi-tenant (a `{tenantid}`
/// template issuer) pins `aud` and matches `iss` against the configured
/// pattern instead — an absent or uncompilable pattern rejects.
public final class OidcProvider {

    /// What discovery answered.
    public record Endpoints(String issuer, String authorizationEndpoint, String tokenEndpoint, String jwksUri) {
        public Endpoints {
            Objects.requireNonNull(issuer, "issuer");
            Objects.requireNonNull(authorizationEndpoint, "authorizationEndpoint");
            Objects.requireNonNull(tokenEndpoint, "tokenEndpoint");
            Objects.requireNonNull(jwksUri, "jwksUri");
        }
    }

    /// The configuration a client is built from.
    public record Config(String issuerUrl, String clientId, Optional<String> clientSecret, boolean multiTenant,
                         String issuerPattern) {
        public Config {
            Objects.requireNonNull(issuerUrl, "issuerUrl");
            Objects.requireNonNull(clientId, "clientId");
            Objects.requireNonNull(clientSecret, "clientSecret");
        }
    }

    public static final String SCOPES = "openid profile email";

    public sealed interface Verification permits Verified, Rejected {
    }

    public record Verified(IdTokenClaims claims) implements Verification {
    }

    public record Rejected(String reason) implements Verification {
    }

    /// A token endpoint that did not hand back a usable response.
    public static final class ExchangeException extends Exception {
        public ExchangeException(String message, Throwable cause) {
            super(message, cause);
        }

        public ExchangeException(String message) {
            super(message);
        }
    }

    private final Config config;
    private final Endpoints endpoints;
    private final JWKSource<SecurityContext> jwks;
    private final HttpClient http;
    private final Clock clock;

    public OidcProvider(Config config, Endpoints endpoints, JWKSource<SecurityContext> jwks, HttpClient http, Clock clock) {
        this.config = Objects.requireNonNull(config, "config");
        this.endpoints = Objects.requireNonNull(endpoints, "endpoints");
        this.jwks = Objects.requireNonNull(jwks, "jwks");
        this.http = Objects.requireNonNull(http, "http");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Config config() {
        return config;
    }

    public Endpoints endpoints() {
        return endpoints;
    }

    // ── start ──────────────────────────────────────────────────────────────

    /// `<authorize>?client_id&redirect_uri&response_type=code&scope&state&nonce&code_challenge&code_challenge_method=S256`.
    public String authorizeUrl(String redirectUri, LoginState state) {
        String sep = endpoints.authorizationEndpoint().contains("?") ? "&" : "?";
        return endpoints.authorizationEndpoint() + sep
                + "client_id=" + enc(config.clientId())
                + "&redirect_uri=" + enc(redirectUri)
                + "&response_type=code"
                + "&scope=" + enc(SCOPES)
                + "&state=" + enc(state.state())
                + "&nonce=" + enc(state.nonce())
                + "&code_challenge=" + s256(state.codeVerifier())
                + "&code_challenge_method=S256";
    }

    // ── finish ─────────────────────────────────────────────────────────────

    /// The `id_token` from the code exchange; empty when the IdP answered
    /// without one. Client authentication is `client_secret_basic`, with
    /// one retry as `client_secret_post` when the IdP refuses the header
    /// (what `golang.org/x/oauth2`'s auto-detection did in Go).
    public Optional<String> exchange(String code, String codeVerifier, String redirectUri) throws ExchangeException {
        String form = "grant_type=authorization_code&code=" + enc(code) + "&redirect_uri=" + enc(redirectUri)
                + "&code_verifier=" + enc(codeVerifier) + "&client_id=" + enc(config.clientId());
        HttpResponse<String> r = post(form, true);
        if (r.statusCode() >= 400 && r.statusCode() < 500 && config.clientSecret().isPresent()) {
            r = post(form + "&client_secret=" + enc(config.clientSecret().get()), false);
        }
        if (r.statusCode() != 200) {
            throw new ExchangeException("token endpoint answered " + r.statusCode());
        }
        try {
            JsonNode body = Json.MAPPER.readTree(r.body());
            JsonNode id = body.get("id_token");
            return id == null || !id.isString() || id.asString().isEmpty() ? Optional.empty() : Optional.of(id.asString());
        } catch (RuntimeException e) {
            throw new ExchangeException("token endpoint answered a body that is not JSON", e);
        }
    }

    private HttpResponse<String> post(String form, boolean basic) throws ExchangeException {
        var b = HttpRequest.newBuilder(URI.create(endpoints.tokenEndpoint()))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(form));
        if (basic && config.clientSecret().isPresent()) {
            String raw = enc(config.clientId()) + ":" + enc(config.clientSecret().get());
            b.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8)));
        }
        try {
            return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new ExchangeException("token endpoint unreachable: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExchangeException("interrupted", e);
        }
    }

    /// Signature against the JWKS, `exp`/`nbf`, `aud` contains our client id,
    /// and `iss` exact (single-tenant) or pattern-matched (multi-tenant).
    public Verification verifyIdToken(String idToken) {
        var processor = new DefaultJWTProcessor<SecurityContext>();
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(Set.of(JWSAlgorithm.RS256, JWSAlgorithm.RS384,
                JWSAlgorithm.RS512, JWSAlgorithm.ES256, JWSAlgorithm.ES384, JWSAlgorithm.ES512, JWSAlgorithm.PS256), jwks));
        var exact = new JWTClaimsSet.Builder();
        if (!config.multiTenant()) {
            exact.issuer(config.issuerUrl());
        }
        var verifier = new DefaultJWTClaimsVerifier<SecurityContext>(config.clientId(), exact.build(), Set.of("exp"));
        verifier.setMaxClockSkew(60);
        processor.setJWTClaimsSetVerifier(verifier);
        JWTClaimsSet cs;
        try {
            cs = processor.process(idToken, null);
        } catch (ParseException e) {
            return new Rejected("malformed id_token: " + e.getMessage());
        } catch (BadJOSEException e) {
            return new Rejected("id_token rejected: " + e.getMessage());
        } catch (com.nimbusds.jose.JOSEException e) {
            return new Rejected("id_token signature could not be checked: " + e.getMessage());
        }
        List<String> aud = cs.getAudience();
        if (aud == null || !aud.contains(config.clientId())) {
            return new Rejected("audience does not contain the client id");
        }
        if (config.multiTenant()) {
            String iss = cs.getIssuer();
            if (iss == null || !issuerMatches(iss)) {
                return new Rejected("issuer does not match the configured issuer or pattern");
            }
        }
        try {
            return new Verified(IdTokenClaims.from(cs));
        } catch (ParseException e) {
            return new Rejected("claims malformed: " + e.getMessage());
        }
    }

    /// Exact equality with the configured issuer first; else the pattern,
    /// unanchored unless it anchors itself; no or bad pattern ⇒ false.
    boolean issuerMatches(String iss) {
        if (iss.equals(config.issuerUrl())) {
            return true;
        }
        String p = config.issuerPattern();
        if (p == null || p.isEmpty()) {
            return false;
        }
        try {
            return Pattern.compile(p).matcher(iss).find();
        } catch (PatternSyntaxException e) {
            return false;
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────

    static String s256(String verifier) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }

    Clock clock() {
        return clock;
    }
}
