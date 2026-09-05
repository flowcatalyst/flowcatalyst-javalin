package io.flowcatalyst.platform.auth.oidc;

import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.SecurityContext;
import io.flowcatalyst.platform.emaildomainmapping.EmailDomainMapping;
import io.flowcatalyst.platform.emaildomainmapping.EmailDomainMappingRepository;
import io.flowcatalyst.platform.identityprovider.IdentityProvider;
import io.flowcatalyst.platform.identityprovider.IdentityProviderRepository;
import io.flowcatalyst.platform.identityprovider.IdentityProviderType;
import io.flowcatalyst.platform.shared.encryption.Decryption;
import io.flowcatalyst.platform.shared.encryption.Encryption;
import io.flowcatalyst.platform.shared.json.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/// Provider resolution and the per-process client cache
/// (`docs/spec/auth-identity.md` §4.1 with ruling Q1): one discovery
/// round-trip per `issuer|clientId` until either the TTL lapses or the
/// identity-provider row changes (`invalidate`). A client secret is
/// decrypted from its ref with the app key — a ref without an encryption
/// service, or one that will not decrypt, fails resolution rather than
/// falling back to a public client.
public final class OidcClients {

    private static final Logger LOG = LoggerFactory.getLogger(OidcClients.class);
    public static final Duration DEFAULT_TTL = Duration.ofMinutes(10);

    /// How an address resolves: `provider` empty means "no bridge needed"
    /// (an INTERNAL identity provider).
    public record Resolution(Optional<OidcProvider> provider, IdentityProvider identityProvider, EmailDomainMapping mapping) {
    }

    public static final class ResolutionException extends Exception {
        public ResolutionException(String message) {
            super(message);
        }

        public ResolutionException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private record Cached(OidcProvider provider, String identityProviderId, Instant expiresAt) {
    }

    private final IdentityProviderRepository identityProviders;
    private final EmailDomainMappingRepository mappings;
    private final Optional<Encryption> encryption;
    private final HttpClient http;
    private final Clock clock;
    private final Duration ttl;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public OidcClients(IdentityProviderRepository identityProviders, EmailDomainMappingRepository mappings,
                       Optional<Encryption> encryption, HttpClient http, Clock clock, Duration ttl) {
        this.identityProviders = Objects.requireNonNull(identityProviders, "identityProviders");
        this.mappings = Objects.requireNonNull(mappings, "mappings");
        this.encryption = Objects.requireNonNull(encryption, "encryption");
        this.http = Objects.requireNonNull(http, "http");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ttl = Objects.requireNonNull(ttl, "ttl");
    }

    public static OidcClients of(IdentityProviderRepository idps, EmailDomainMappingRepository mappings, Optional<Encryption> encryption) {
        return new OidcClients(idps, mappings, encryption,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build(),
                Clock.systemUTC(), DEFAULT_TTL);
    }

    // ── resolution ─────────────────────────────────────────────────────────

    /// The domain after the last `@`; no mapping or no provider is an error,
    /// an INTERNAL provider resolves with no client.
    public Resolution resolveForEmail(String email) throws ResolutionException {
        String domain = domainOf(email);
        if (domain.isEmpty()) {
            throw new ResolutionException("invalid email: no domain");
        }
        EmailDomainMapping mapping = mappings.findByEmailDomain(domain)
                .orElseThrow(() -> new ResolutionException("no email-domain mapping for " + domain));
        IdentityProvider idp = identityProviders.findById(mapping.identityProviderId())
                .orElseThrow(() -> new ResolutionException("identity provider " + mapping.identityProviderId() + " not found"));
        if (idp.type() != IdentityProviderType.OIDC) {
            return new Resolution(Optional.empty(), idp, mapping);
        }
        return new Resolution(Optional.of(client(idp)), idp, mapping);
    }

    /// Provider-direct: the provider must be OIDC; a multi-tenant provider
    /// with no routed domains is refused (nothing would bind the account).
    public Resolution resolveByProviderId(String id) throws ResolutionException {
        IdentityProvider idp = identityProviders.findById(id)
                .orElseThrow(() -> new ResolutionException("identity provider " + id + " not found"));
        if (idp.type() != IdentityProviderType.OIDC) {
            throw new ResolutionException("identity provider is not OIDC");
        }
        if (idp.oidcMultiTenant() && idp.allowedEmailDomains().isEmpty()) {
            throw new ResolutionException("multi-tenant IdP requires allowed_email_domains for provider-direct login");
        }
        return new Resolution(Optional.of(client(idp)), idp, null);
    }

    /// The identity-provider row changed: forget its client (ruling Q1).
    public void invalidate(String identityProviderId) {
        cache.values().removeIf(c -> c.identityProviderId().equals(identityProviderId));
    }

    // ── the cache ──────────────────────────────────────────────────────────

    OidcProvider client(IdentityProvider idp) throws ResolutionException {
        if (idp.oidcIssuerUrl() == null || idp.oidcIssuerUrl().isBlank() || idp.oidcClientId() == null || idp.oidcClientId().isBlank()) {
            throw new ResolutionException("identity provider " + idp.id() + " has no OIDC issuer url / client id");
        }
        String key = idp.oidcIssuerUrl() + "|" + idp.oidcClientId();
        Instant now = clock.instant();
        Cached hit = cache.get(key);
        if (hit != null && hit.expiresAt().isAfter(now) && sameSecret(hit.provider(), idp)) {
            return hit.provider();
        }
        // Discovery outside any lock: two racing callers may both discover
        // once, which is harmless; the invariant is only "at most one
        // client per key survives".
        OidcProvider built = build(idp);
        cache.put(key, new Cached(built, idp.id(), now.plus(ttl)));
        return built;
    }

    private boolean sameSecret(OidcProvider p, IdentityProvider idp) {
        boolean hasRef = idp.oidcClientSecretRef() != null && !idp.oidcClientSecretRef().isBlank();
        return p.config().clientSecret().isPresent() == hasRef;
    }

    private OidcProvider build(IdentityProvider idp) throws ResolutionException {
        Optional<String> secret = secret(idp);
        OidcProvider.Endpoints endpoints = discover(idp.oidcIssuerUrl(), idp.oidcMultiTenant());
        JWKSource<SecurityContext> jwks;
        try {
            jwks = JWKSourceBuilder.create(URI.create(endpoints.jwksUri()).toURL()).build();
        } catch (MalformedURLException | IllegalArgumentException e) {
            throw new ResolutionException("jwks_uri is not a URL: " + endpoints.jwksUri(), e);
        }
        var config = new OidcProvider.Config(idp.oidcIssuerUrl(), idp.oidcClientId(), secret, idp.oidcMultiTenant(), idp.oidcIssuerPattern());
        return new OidcProvider(config, endpoints, jwks, http, clock);
    }

    private Optional<String> secret(IdentityProvider idp) throws ResolutionException {
        String ref = idp.oidcClientSecretRef();
        if (ref == null || ref.isBlank()) {
            return Optional.empty();
        }
        Encryption enc = encryption.orElseThrow(() -> new ResolutionException(
                "OIDC client_secret_ref present but no encryption service configured (set FLOWCATALYST_APP_KEY)"));
        return switch (enc.decrypt(ref)) {
            case Decryption.Plaintext p -> Optional.of(p.value());
            case Decryption.External _ -> throw new ResolutionException("OIDC client_secret_ref is an external reference the server cannot resolve");
            case Decryption.Failed f -> throw new ResolutionException("OIDC client_secret_ref cannot be decrypted: " + f.reason());
        };
    }

    /// `<issuer>/.well-known/openid-configuration`. A single-tenant document
    /// must report our issuer back; a multi-tenant one reports a
    /// `{tenantid}` template and is taken as is.
    OidcProvider.Endpoints discover(String issuerUrl, boolean multiTenant) throws ResolutionException {
        String base = issuerUrl.endsWith("/") ? issuerUrl.substring(0, issuerUrl.length() - 1) : issuerUrl;
        String url = base + "/.well-known/openid-configuration";
        HttpResponse<String> r;
        try {
            r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (IOException | IllegalArgumentException e) {
            throw new ResolutionException("OIDC discovery failed for " + url + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResolutionException("OIDC discovery interrupted", e);
        }
        if (r.statusCode() != 200) {
            throw new ResolutionException("OIDC discovery for " + url + " answered " + r.statusCode());
        }
        JsonNode doc;
        try {
            doc = Json.MAPPER.readTree(r.body());
        } catch (RuntimeException e) {
            throw new ResolutionException("OIDC discovery document is not JSON", e);
        }
        String issuer = text(doc, "issuer");
        String authz = text(doc, "authorization_endpoint");
        String token = text(doc, "token_endpoint");
        String jwks = text(doc, "jwks_uri");
        if (issuer == null || authz == null || token == null || jwks == null) {
            throw new ResolutionException("OIDC discovery document is missing issuer / authorization_endpoint / token_endpoint / jwks_uri");
        }
        if (!multiTenant && !issuer.equals(issuerUrl) && !issuer.equals(base) && !(issuer + "/").equals(issuerUrl)) {
            throw new ResolutionException("OIDC discovery issuer " + issuer + " does not match the configured " + issuerUrl);
        }
        LOG.info("oidc client discovered issuer={} token_endpoint={}", issuer, token);
        return new OidcProvider.Endpoints(issuer, authz, token, jwks);
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || !v.isString() || v.asString().isEmpty() ? null : v.asString();
    }

    static String domainOf(String email) {
        if (email == null) {
            return "";
        }
        int at = email.lastIndexOf('@');
        return at < 0 || at == email.length() - 1 ? "" : email.substring(at + 1).toLowerCase(Locale.ROOT);
    }
}
