package io.flowcatalyst.platform.auth.oidc;

import io.flowcatalyst.platform.emaildomainmapping.EmailDomainMappingRepository;
import io.flowcatalyst.platform.identityprovider.IdentityProvider;
import io.flowcatalyst.platform.identityprovider.IdentityProviderRepository;
import io.flowcatalyst.platform.identityprovider.IdentityProviderType;
import io.flowcatalyst.platform.shared.TestHttp;
import io.flowcatalyst.platform.shared.encryption.Encryption;
import io.flowcatalyst.testpg.TestPg;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/// The client cache turns over when an identity provider is edited — the
/// 2026-09-23 prod incident (Go `7ab071c`): a secret saved after the first
/// login never reached the process. Counted in discovery round-trips, so a
/// stale hit and a fresh build cannot be confused.
class OidcClientsCacheTest {

    private static final Encryption ENC = Encryption.withKey(Encryption.generateKey());
    private static final AtomicInteger DISCOVERIES = new AtomicInteger();
    private static TestHttp idp;
    private static String base;
    private OidcClients clients;

    @BeforeAll
    static void start() {
        idp = TestHttp.routes(routes -> {
            routes.get("/.well-known/openid-configuration", ctx -> {
                DISCOVERIES.incrementAndGet();
                ctx.json(Map.of("issuer", base, "authorization_endpoint", base + "/authorize",
                        "token_endpoint", base + "/token", "jwks_uri", base + "/jwks"));
            });
        });
        base = "http://localhost:" + idp.port();
    }

    @AfterAll
    static void stop() {
        idp.close();
    }

    @BeforeEach
    void fresh() {
        var ds = TestPg.dataSource();
        clients = new OidcClients(new IdentityProviderRepository(ds), new EmailDomainMappingRepository(ds), Optional.of(ENC),
                HttpClient.newHttpClient(), Clock.systemUTC(), Duration.ofMinutes(10));
        DISCOVERIES.set(0);
    }

    private static IdentityProvider provider() {
        return IdentityProvider.create("cache-idp", "Cache", IdentityProviderType.OIDC).withOidc(base, "app-1", null, false, null);
    }

    private static IdentityProvider.Changes changes(String secretRef, Boolean multiTenant, String pattern, String name) {
        return new IdentityProvider.Changes(name, null, null, secretRef, multiTenant, pattern, null, null);
    }

    @Test
    void anUnchangedProviderIsDiscoveredOnce() throws Exception {
        var p = provider();
        var first = clients.client(p);
        assertThat(clients.client(p)).isSameAs(first);
        assertThat(clients.client(p.update(changes(null, null, null, "Renamed"))))
                .as("a field that does not shape the client keeps the entry").isSameAs(first);
        assertThat(DISCOVERIES).hasValue(1);
    }

    @Test
    void aSecretEnteredAfterTheFirstLoginIsUsedOnTheNextOne() throws Exception {
        var p = provider();
        assertThat(clients.client(p).config().clientSecret()).isEmpty();

        var withSecret = p.update(changes(ENC.encryptSecretRef("s1"), null, null, null));
        assertThat(clients.client(withSecret).config().clientSecret()).contains("s1");
        assertThat(DISCOVERIES).hasValue(2);
    }

    @Test
    void aRotatedSecretIsUsedOnTheNextLogin() throws Exception {
        var p = provider().update(changes(ENC.encryptSecretRef("s1"), null, null, null));
        assertThat(clients.client(p).config().clientSecret()).contains("s1");

        var rotated = p.update(changes(ENC.encryptSecretRef("s2"), null, null, null));
        assertThat(clients.client(rotated).config().clientSecret())
                .as("secret present before and after — presence alone must not decide a hit").contains("s2");
        assertThat(DISCOVERIES).hasValue(2);
    }

    @Test
    void aMultiTenantFlipOrAnIssuerPatternEditIsUsedOnTheNextLogin() throws Exception {
        var p = provider();
        clients.client(p);

        var multi = p.update(changes(null, true, null, null));
        assertThat(clients.client(multi).config().multiTenant()).isTrue();

        var patterned = multi.update(changes(null, null, "^http://t-[a-z]+$", null));
        assertThat(clients.client(patterned).config().issuerPattern()).isEqualTo("^http://t-[a-z]+$");
        assertThat(DISCOVERIES).hasValue(3);
    }

    @Test
    void invalidateForgetsTheProvidersClient() throws Exception {
        var p = provider();
        var first = clients.client(p);
        clients.invalidate(p.id());
        assertThat(clients.client(p)).isNotSameAs(first);
        assertThat(DISCOVERIES).hasValue(2);
    }
}
