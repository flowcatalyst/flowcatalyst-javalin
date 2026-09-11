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
        return new PortalIdentity(p.id(), p.clientId(), p.email(), p.name(), hash, p.status(), p.source(), p.apps(),
                p.revokedAppIds(), p.lastLoginAt(), p.invitedAt(), p.inviteExpiresAt(), p.createdAt(), Instant.now());
    }

    /// A test-only helper poking `invitedAt` / `inviteExpiresAt` directly —
    /// the aggregate has no transition for them (`portal-apps.md` §2.2: the
    /// repository writes them outside the upsert, via `markInvited`).
    private static PortalIdentity withInvite(PortalIdentity p, Instant invitedAt, Instant expiresAtOrNull) {
        return new PortalIdentity(p.id(), p.clientId(), p.email(), p.name(), p.passwordHash(), p.status(), p.source(),
                p.apps(), p.revokedAppIds(), p.lastLoginAt(), invitedAt, expiresAtOrNull, p.createdAt(), Instant.now());
    }

    private static PortalIdentity withLastLogin(PortalIdentity p, Instant at) {
        return new PortalIdentity(p.id(), p.clientId(), p.email(), p.name(), p.passwordHash(), p.status(), p.source(),
                p.apps(), p.revokedAppIds(), at, p.invitedAt(), p.inviteExpiresAt(), p.createdAt(), Instant.now());
    }

    // ── Derived state (spec `portal-apps.md` §2.3, §9.1) ──────────────────────

    private static final Instant NOW = Instant.parse("2026-06-15T12:00:00Z");

    @Test
    void liveInviteIsInvited() {
        var p = withInvite(PortalIdentity.create("clt_1", "a@x.com", null, PortalIdentitySource.INVITE),
                NOW.minusSeconds(3600), NOW.plusSeconds(3600));
        assertThat(p.state(NOW)).isEqualTo(PortalUserState.INVITED);
    }

    @Test
    void lapsedInviteIsInviteExpired() {
        var p = withInvite(PortalIdentity.create("clt_1", "a@x.com", null, PortalIdentitySource.INVITE),
                NOW.minusSeconds(7200), NOW.minusSeconds(3600));
        assertThat(p.state(NOW)).isEqualTo(PortalUserState.INVITE_EXPIRED);
    }

    @Test
    void expiryExactlyAtNowCountsAsExpired() {
        var p = withInvite(PortalIdentity.create("clt_1", "a@x.com", null, PortalIdentitySource.INVITE),
                NOW.minusSeconds(3600), NOW);
        assertThat(p.state(NOW)).as("now >= inviteExpiresAt ⇒ expired, not the boundary held open").isEqualTo(PortalUserState.INVITE_EXPIRED);
    }

    @Test
    void passwordSetEvenAfterTheLinkExpiredIsActive() {
        var p = withHash(withInvite(PortalIdentity.create("clt_1", "a@x.com", null, PortalIdentitySource.INVITE),
                NOW.minusSeconds(7200), NOW.minusSeconds(3600)), "a-hash");
        assertThat(p.state(NOW)).as("a password beats a lapsed invite").isEqualTo(PortalUserState.ACTIVE);
    }

    @Test
    void ssoInviteWithNoExpiryNeverExpires() {
        var p = withInvite(PortalIdentity.create("clt_1", "a@x.com", null, PortalIdentitySource.INVITE),
                NOW.minusSeconds(999_999_999), null);
        assertThat(p.state(NOW)).as("invitedAt set, inviteExpiresAt null ⇒ no expiry").isEqualTo(PortalUserState.INVITED);
    }

    @Test
    void firstSsoSignInIsActive() {
        var p = withLastLogin(PortalIdentity.create("clt_1", "a@x.com", null, PortalIdentitySource.INVITE), NOW.minusSeconds(1));
        assertThat(p.state(NOW)).isEqualTo(PortalUserState.ACTIVE);
    }

    @Test
    void jitSourceIsActiveEvenWithNoPasswordOrLogin() {
        var p = PortalIdentity.create("clt_1", "a@x.com", null, PortalIdentitySource.JIT);
        assertThat(p.state(NOW)).isEqualTo(PortalUserState.ACTIVE);
    }

    @Test
    void disabledBeatsEverything() {
        var p = withHash(withInvite(PortalIdentity.create("clt_1", "a@x.com", null, PortalIdentitySource.JIT),
                NOW.minusSeconds(3600), NOW.plusSeconds(3600)), "a-hash").deactivate();
        assertThat(p.state(NOW)).as("DISABLED overrides an active password, JIT source and a live invite")
                .isEqualTo(PortalUserState.SUSPENDED);
    }

    @Test
    void neverInvitedAtAllIsInvited() {
        var p = PortalIdentity.create("clt_1", "a@x.com", null, PortalIdentitySource.INVITE);
        assertThat(p.state(NOW)).isEqualTo(PortalUserState.INVITED);
    }

    /// Rule 2 (a password/login/JIT ⇒ ACTIVE) must be checked before rule 3
    /// (lapsed invite ⇒ INVITE_EXPIRED) — mutant: swap them. A JIT identity
    /// with a lapsed invite would then read INVITE_EXPIRED instead of ACTIVE.
    @Test
    void rule2BeatsRule3WhenBothWouldMatch() {
        var p = withInvite(PortalIdentity.create("clt_1", "a@x.com", null, PortalIdentitySource.JIT),
                NOW.minusSeconds(7200), NOW.minusSeconds(3600));
        assertThat(p.state(NOW)).as("JIT (rule 2) must win over the lapsed invite (rule 3)").isEqualTo(PortalUserState.ACTIVE);
    }

    // ── Per-app grants (spec `portal-apps.md` §2.2) ────────────────────────────

    @Test
    void grantIsIdempotentAndOrdersByGrantedAt() throws InterruptedException {
        var p = PortalIdentity.create("clt_1", "a@x.com", null, PortalIdentitySource.INVITE);
        assertThat(p.hasApp("pta_1")).isFalse();

        var granted = p.grant("pta_1", PortalAppGrantSource.INVITE);
        assertThat(granted.hasApp("pta_1")).isTrue();
        assertThat(granted.apps()).extracting(PortalAppGrant::appId).containsExactly("pta_1");

        Thread.sleep(5);
        var grantedTwice = granted.grant("pta_1", PortalAppGrantSource.ADMIN);
        assertThat(grantedTwice).as("idempotent: same instance, second call ignored entirely").isSameAs(granted);

        Thread.sleep(5);
        var second = grantedTwice.grant("pta_2", PortalAppGrantSource.JIT);
        assertThat(second.apps()).extracting(PortalAppGrant::appId).containsExactly("pta_1", "pta_2");
    }

    @Test
    void revokeIsIdempotent() {
        var p = PortalIdentity.create("clt_1", "a@x.com", null, PortalIdentitySource.INVITE)
                .grant("pta_1", PortalAppGrantSource.INVITE);
        var revoked = p.revoke("pta_1");
        assertThat(revoked.hasApp("pta_1")).isFalse();

        var revokedAgain = revoked.revoke("pta_1");
        assertThat(revokedAgain).as("idempotent: no-op on an app that is not held").isSameAs(revoked);

        assertThat(p.revoke("pta_doesnotexist")).as("revoking a never-held app is a no-op").isSameAs(p);
    }

    // ── revokedAppIds bookkeeping (spec `portal-apps.md` §2.2, errata P6) ─────

    @Test
    void createAndAFreshLoadStartWithNoPendingRevokes() {
        var p = PortalIdentity.create("clt_1", "a@x.com", null, PortalIdentitySource.INVITE);
        assertThat(p.revokedAppIds()).isEmpty();
    }

    @Test
    void revokeRecordsThePendingDeletion() {
        var p = PortalIdentity.create("clt_1", "a@x.com", null, PortalIdentitySource.INVITE)
                .grant("pta_1", PortalAppGrantSource.INVITE)
                .revoke("pta_1");
        assertThat(p.revokedAppIds()).as("persist must delete exactly this id").containsExactly("pta_1");
    }

    /// Mutant: `grant` fails to un-record a pending revoke of the same app.
    /// If it didn't, `persist` would still delete the row this `grant` just
    /// re-added, because `revokedAppIds` still names it — the grant would be
    /// silently lost on the very next save.
    @Test
    void grantingAPendingRevokeCancelsItAndTheAppIsHeldAgain() {
        var revoked = PortalIdentity.create("clt_1", "a@x.com", null, PortalIdentitySource.INVITE)
                .grant("pta_1", PortalAppGrantSource.INVITE)
                .revoke("pta_1");
        assertThat(revoked.revokedAppIds()).containsExactly("pta_1");

        var reGranted = revoked.grant("pta_1", PortalAppGrantSource.ADMIN);
        assertThat(reGranted.hasApp("pta_1")).isTrue();
        assertThat(reGranted.revokedAppIds()).as("the pending revoke is cancelled, not carried into persist").isEmpty();
    }

    /// Revoking a DIFFERENT app than a pending revoke accumulates both —
    /// `withRevoked` must not overwrite the set.
    @Test
    void revokingTwoDifferentAppsRecordsBoth() {
        var p = PortalIdentity.create("clt_1", "a@x.com", null, PortalIdentitySource.INVITE)
                .grant("pta_1", PortalAppGrantSource.INVITE)
                .grant("pta_2", PortalAppGrantSource.INVITE)
                .revoke("pta_1")
                .revoke("pta_2");
        assertThat(p.revokedAppIds()).containsExactlyInAnyOrder("pta_1", "pta_2");
    }
}
