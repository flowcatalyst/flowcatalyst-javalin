package io.flowcatalyst.platform.portalidentity;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/// The portal-identity aggregate's pure rules (spec `auth-identity.md`
/// §3.3, §11.8): no database. `PortalIdentityRepositoryTest` covers the
/// SQL-level upsert guarantee; `PortalIdentityOperationsTest` covers the
/// use-case envelope.
class PortalIdentityTest {

    @Test
    void createNormalisesEmailAndBlankNameAndStartsActiveWithNoPassword() {
        var p = PortalIdentity.create("clt_1", "  Mixed.Case@Example.COM  ", "  ", PortalIdentitySource.INVITE);
        assertThat(p.email()).isEqualTo("mixed.case@example.com");
        assertThat(p.name()).as("blank name normalises to null").isNull();
        assertThat(p.status()).isEqualTo(PortalIdentityStatus.ACTIVE);
        assertThat(p.passwordHash()).isNull();
        assertThat(p.canSignInWithPassword()).as("no password yet").isFalse();
        assertThat(p.id()).startsWith("ptu_");
    }

    @Test
    void canSignInWithPasswordRequiresBothActiveAndANonEmptyHash() {
        var active = PortalIdentity.create("clt_1", "a@example.com", null, PortalIdentitySource.INVITE);
        assertThat(withHash(active, "hash").canSignInWithPassword()).isTrue();
        assertThat(withHash(active, "").canSignInWithPassword()).as("blank hash").isFalse();
        assertThat(withHash(active, null).canSignInWithPassword()).as("no hash").isFalse();
        assertThat(withHash(active.deactivate(), "hash").canSignInWithPassword()).as("disabled, even with a hash").isFalse();
    }

    @Test
    void ensureActiveReactivatesAndOverridesNameOnlyWhenNonBlankButNeverTouchesIdSourceCreatedAtOrPassword() {
        var seed = withHash(PortalIdentity.create("clt_1", "a@example.com", "Original", PortalIdentitySource.JIT), "secret-hash")
                .deactivate();
        assertThat(seed.status()).isEqualTo(PortalIdentityStatus.DISABLED);

        var blankName = seed.ensureActive("   ");
        assertThat(blankName.status()).as("re-ensure always reactivates").isEqualTo(PortalIdentityStatus.ACTIVE);
        assertThat(blankName.name()).as("blank candidate name leaves the existing name").isEqualTo("Original");
        assertThat(blankName.id()).isEqualTo(seed.id());
        assertThat(blankName.source()).as("source untouched by re-ensure").isEqualTo(PortalIdentitySource.JIT);
        assertThat(blankName.createdAt()).isEqualTo(seed.createdAt());
        assertThat(blankName.passwordHash()).as("password untouched by re-ensure").isEqualTo("secret-hash");

        var namedAgain = seed.ensureActive("  New Name  ");
        assertThat(namedAgain.name()).as("non-blank candidate name overrides, trimmed").isEqualTo("New Name");
    }

    @Test
    void activateAndDeactivateAreIdempotentAndNeverThrow() {
        var p = PortalIdentity.create("clt_1", "a@example.com", null, PortalIdentitySource.INVITE);
        assertThat(p.activate().status()).isEqualTo(PortalIdentityStatus.ACTIVE);
        var disabled = p.deactivate().deactivate();
        assertThat(disabled.status()).isEqualTo(PortalIdentityStatus.DISABLED);
        assertThat(disabled.activate().activate().status()).isEqualTo(PortalIdentityStatus.ACTIVE);
    }

    /// A test-only helper: the aggregate has no `withPasswordHash` (the
    /// reset unit will add the transition that needs it); this fixture pokes
    /// the record directly, since `PortalIdentityTest` is pure-entity and
    /// must not depend on the repository.
    private static PortalIdentity withHash(PortalIdentity p, String hash) {
        return new PortalIdentity(p.id(), p.clientId(), p.email(), p.name(), hash, p.status(), p.source(),
                p.lastLoginAt(), p.createdAt(), Instant.now());
    }
}
