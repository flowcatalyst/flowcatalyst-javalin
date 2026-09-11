package io.flowcatalyst.platform.oauthclient;

import io.flowcatalyst.platform.shared.encryption.Encryption;
import io.flowcatalyst.sdk.usecase.UseCaseError;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiPredicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The aggregate's pure rules (spec `auth-core.md` §3.6, §6.3, §8.5; A-22
/// `docs/improvements.md`) — no database involved.
class OAuthClientTest {

    private static void assertUseCaseError(ThrowingCallable call, Class<? extends UseCaseError> kind, String code) {
        assertThatThrownBy(call)
                .isInstanceOf(UseCaseException.class)
                .extracting(t -> ((UseCaseException) t).error())
                .satisfies(err -> {
                    assertThat(err).as("error kind").isInstanceOf(kind);
                    assertThat(err.code()).as("error code").isEqualTo(code);
                });
    }

    private static OAuthClient confidential() {
        return OAuthClient.create("cli_1", "My Client", ClientType.CONFIDENTIAL).withSecretRef("encrypted:current-ref");
    }

    // ── Create ─────────────────────────────────────────────────────────────

    @Test
    void createIsActiveWithPkceRequiredAndNoSecretByDefault() {
        var c = OAuthClient.create("cli_1", "My Client", ClientType.PUBLIC);
        assertThat(c.id()).startsWith("oac_");
        assertThat(c.active()).isTrue();
        assertThat(c.pkceRequired()).isTrue();
        assertThat(c.secretRef()).isNull();
        assertThat(c.redirectUris()).isEmpty();
        assertThat(c.grantTypes()).isEmpty();
        assertThat(c.isPortal()).isFalse();
        assertThat(c.apiAccess()).isFalse();
    }

    // ── allowsGrant (ruling Q17/Q20: empty = no grant, fail closed) ─────────

    @Test
    void allowsGrantIsFalseOnAnEmptyList() {
        var c = OAuthClient.create("cli_1", "X", ClientType.PUBLIC);
        assertThat(c.grantTypes()).isEmpty();
        assertThat(c.allowsGrant("authorization_code")).as("empty grantTypes ⇒ no grant allowed").isFalse();
        assertThat(c.allowsGrant("client_credentials")).isFalse();
    }

    @Test
    void allowsGrantChecksMembershipOnANonEmptyList() {
        var c = OAuthClient.create("cli_1", "X", ClientType.PUBLIC).withGrantTypes(List.of("authorization_code"));
        assertThat(c.allowsGrant("authorization_code")).isTrue();
        assertThat(c.allowsGrant("client_credentials")).as("not in the list").isFalse();
    }

    // ── Lifecycle: idempotent, no error either way ──────────────────────────

    @Test
    void activateAndDeactivateAreIdempotent() {
        var c = OAuthClient.create("cli_1", "X", ClientType.PUBLIC);
        assertThat(c.deactivate().active()).isFalse();
        assertThat(c.deactivate().deactivate().active()).as("deactivating twice does not throw").isFalse();
        assertThat(c.deactivate().activate().active()).isTrue();
        assertThat(c.activate().activate().active()).as("activating twice does not throw").isTrue();
    }

    // ── Portal / apiAccess mutual exclusion ──────────────────────────────────

    @Test
    void portalAndApiAccessAreMutuallyExclusive() {
        var c = OAuthClient.create("cli_1", "X", ClientType.PUBLIC);
        assertUseCaseError(() -> c.withPortalAndApiAccess("cli_portal_owner", true),
                UseCaseError.Validation.class, "PORTAL_API_ACCESS_CONFLICT");

        // Either alone is fine.
        assertThat(c.withPortalAndApiAccess("cli_portal_owner", false).isPortal()).isTrue();
        assertThat(c.withPortalAndApiAccess(null, true).apiAccess()).isTrue();

        // Blank portalClientId clears / normalises to null, not "set".
        assertThat(c.withPortalAndApiAccess("   ", true).isPortal()).isFalse();
        assertThat(c.withPortalAndApiAccess("   ", true).apiAccess()).isTrue();
    }

    @Test
    void updateEnforcesThePlaneConflictToo() {
        var portalClient = OAuthClient.create("cli_1", "X", ClientType.PUBLIC).withPortalAndApiAccess("cli_owner", false);
        assertUseCaseError(() -> portalClient.update(new OAuthClient.Changes(
                        null, null, null, null, null, null, null, null, null, null, true)),
                UseCaseError.Validation.class, "PORTAL_API_ACCESS_CONFLICT");
    }

    /// `portal-apps.md` §1, Part A J4: `portalAppId` links via [OAuthClient#withPortalAppId].
    /// Mutant: `withPortalAndApiAccess` drops the `portalAppId != null` check —
    /// killed by this test throwing where it otherwise would silently clear
    /// `portalClientId` and orphan the app link. Mutant: `update` applies
    /// `Changes#portalAppId` AFTER (rather than before) `withPortalAndApiAccess` —
    /// killed the same way, since the invariant would then see the stale
    /// pre-update `portalAppId` instead of the change's real target.
    @Test
    void updateEnforcesThePortalAppRequiresPortalClientInvariant() {
        var base = OAuthClient.create("cli_1", "X", ClientType.PUBLIC).withPortalAndApiAccess("cli_owner", false);
        var linked = base.withPortalAppId("pta_1");

        // portalAppId omitted (null ⇒ untouched, stays "pta_1") while portalClientId clears.
        assertUseCaseError(() -> linked.update(new OAuthClient.Changes(
                        null, null, null, null, null, null, null, null, "", null, null)),
                UseCaseError.Validation.class, "PORTAL_APP_REQUIRES_PORTAL_CLIENT");

        // Clearing portalAppId in the SAME update avoids the conflict (spec §4.5 "clears both").
        assertThat(linked.update(new OAuthClient.Changes(
                        null, null, null, null, null, null, null, null, "", "", null)).isPortal())
                .as("clearing both together is legal").isFalse();

        // Clearing portalClientId when no app is linked still works (existing behaviour).
        assertThat(base.update(new OAuthClient.Changes(null, null, null, null, null, null, null, null, "", null, null)).isPortal())
                .isFalse();
    }

    @Test
    void updateAppliesOnlyNonNullFieldsAndClearsPortalOnBlank() {
        var c = OAuthClient.create("cli_1", "Before", ClientType.PUBLIC)
                .withRedirectUris(List.of("https://a"))
                .withPortalAndApiAccess("cli_owner", false);

        var updated = c.update(new OAuthClient.Changes("After", null, null, List.of("client_credentials"),
                null, null, null, null, "", null, null));
        assertThat(updated.clientName()).isEqualTo("After");
        assertThat(updated.redirectUris()).as("untouched (null in Changes)").containsExactly("https://a");
        assertThat(updated.grantTypes()).containsExactly("client_credentials");
        assertThat(updated.isPortal()).as("blank portalClientId clears it").isFalse();
    }

    /// `Changes#portalAppId` follows the same three-state contract as
    /// `portalClientId` (spec §4.5): `null` = untouched, blank = unlink,
    /// non-blank = set. Mutant: `update` reads `portalAppId` straight off
    /// `Changes` without the null/blank distinction — killed by the middle
    /// assertion, where an explicit "" must clear a value a `null` just proved
    /// it leaves alone.
    @Test
    void updatePortalAppIdFollowsTheThreeStateContract() {
        var linked = OAuthClient.create("cli_1", "X", ClientType.PUBLIC)
                .withPortalAndApiAccess("cli_owner", false).withPortalAppId("pta_1");

        var untouched = linked.update(new OAuthClient.Changes(null, null, null, null, null, null, null, null, null, null, null));
        assertThat(untouched.portalAppId()).as("null Changes.portalAppId leaves it alone").isEqualTo("pta_1");

        var cleared = linked.update(new OAuthClient.Changes(null, null, null, null, null, null, null, null, null, "", null));
        assertThat(cleared.portalAppId()).as("blank Changes.portalAppId unlinks").isNull();

        var relinked = linked.update(new OAuthClient.Changes(null, null, null, null, null, null, null, null, null, "pta_2", null));
        assertThat(relinked.portalAppId()).as("non-blank Changes.portalAppId sets it").isEqualTo("pta_2");
    }

    // ── Secret at rest (A-22) ────────────────────────────────────────────────

    @Test
    void rotateWithPositiveGraceKeepsExactlyOnePreviousSecret() {
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        var c = confidential(); // secretRef = "encrypted:current-ref"

        var first = c.rotateSecret("encrypted:second-ref", Duration.ofHours(24), t0);
        assertThat(first.client().secretRef()).isEqualTo("encrypted:second-ref");
        assertThat(first.client().previousSecretRef()).isEqualTo("encrypted:current-ref");
        assertThat(first.previousSecretExpiresAt()).isEqualTo(t0.plus(Duration.ofHours(24)));

        // Rotating again retires the OLDER previous, keeping only the most recently superseded one.
        var second = first.client().rotateSecret("encrypted:third-ref", Duration.ofHours(24), t0.plusSeconds(60));
        assertThat(second.client().secretRef()).isEqualTo("encrypted:third-ref");
        assertThat(second.client().previousSecretRef())
                .as("exactly one previous secret is honoured at a time")
                .isEqualTo("encrypted:second-ref");
    }

    @Test
    void rotateWithZeroGraceOrNoCurrentSecretIsAnImmediateCutover() {
        var c = confidential();
        var immediate = c.rotateSecret("encrypted:new-ref", Duration.ZERO, Instant.now());
        assertThat(immediate.previousSecretExpiresAt()).as("immediate cutover keeps nothing").isNull();
        assertThat(immediate.client().previousSecretRef()).isNull();
        assertThat(immediate.client().secretRef()).isEqualTo("encrypted:new-ref");

        var noCurrent = OAuthClient.create("cli_2", "X", ClientType.CONFIDENTIAL)
                .rotateSecret("encrypted:first-ref", Duration.ofHours(24), Instant.now());
        assertThat(noCurrent.previousSecretExpiresAt()).as("nothing to demote").isNull();
    }

    @Test
    void revokePreviousSecretIsIdempotent() {
        Instant t0 = Instant.now();
        var rotated = confidential().rotateSecret("encrypted:new-ref", Duration.ofHours(24), t0).client();
        assertThat(rotated.previousSecretRef()).isNotNull();

        var revoked = rotated.revokePreviousSecret();
        assertThat(revoked.dropped()).isTrue();
        assertThat(revoked.client().previousSecretRef()).isNull();
        assertThat(revoked.client().previousSecretExpiresAt()).isNull();

        // Calling it again — or on a client that never had an overlap — changes nothing and reports no drop.
        var revokedAgain = revoked.client().revokePreviousSecret();
        assertThat(revokedAgain.dropped()).as("idempotent: nothing left to revoke").isFalse();
        assertThat(revokedAgain.client()).isSameAs(revoked.client());
    }

    // ── usablePreviousSecretRef: expiry is enforced on read ─────────────────

    @Test
    void usablePreviousSecretRefEnforcesTheExpiry() {
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        var rotated = confidential().rotateSecret("encrypted:new-ref", Duration.ofHours(1), t0).client();

        assertThat(rotated.usablePreviousSecretRef(t0.plusSeconds(1))).as("well within the window").isPresent();
        assertThat(rotated.usablePreviousSecretRef(t0.plus(Duration.ofHours(1)).minusSeconds(1)))
                .as("just before the boundary").isPresent();
        assertThat(rotated.usablePreviousSecretRef(t0.plus(Duration.ofHours(1))))
                .as("at the boundary — no longer before expiry").isEmpty();
        assertThat(rotated.usablePreviousSecretRef(t0.plus(Duration.ofHours(2))))
                .as("well past expiry").isEmpty();
        assertThat(OAuthClient.create("cli_1", "X", ClientType.PUBLIC).usablePreviousSecretRef(t0))
                .as("no overlap at all").isEmpty();
    }

    // ── acceptsSecret ────────────────────────────────────────────────────────

    /// A matcher that records how many times, and for which refs, it was called.
    private static final class CountingMatcher implements BiPredicate<String, String> {
        final AtomicInteger calls = new AtomicInteger();
        private final java.util.Map<String, String> plaintextByRef;

        CountingMatcher(java.util.Map<String, String> plaintextByRef) {
            this.plaintextByRef = plaintextByRef;
        }

        @Override
        public boolean test(String ref, String providedPlaintext) {
            calls.incrementAndGet();
            return providedPlaintext.equals(plaintextByRef.get(ref));
        }
    }

    @Test
    void acceptsSecretMatchesTheCurrentSecret() {
        var c = confidential();
        var matcher = new CountingMatcher(java.util.Map.of("encrypted:current-ref", "the-secret"));
        assertThat(c.acceptsSecret("the-secret", Instant.now(), matcher)).isTrue();
        assertThat(c.acceptsSecret("wrong", Instant.now(), matcher)).isFalse();
    }

    @Test
    void acceptsSecretMatchesAnUnexpiredPreviousSecret() {
        Instant t0 = Instant.now();
        var rotated = confidential().rotateSecret("encrypted:new-ref", Duration.ofHours(24), t0).client();
        var matcher = new CountingMatcher(java.util.Map.of(
                "encrypted:new-ref", "new-secret",
                "encrypted:current-ref", "old-secret"));

        assertThat(rotated.acceptsSecret("old-secret", t0.plusSeconds(5), matcher))
                .as("the demoted secret still authenticates inside the grace window").isTrue();
    }

    @Test
    void acceptsSecretRejectsAnExpiredPreviousSecret() {
        Instant t0 = Instant.now();
        var rotated = confidential().rotateSecret("encrypted:new-ref", Duration.ofHours(1), t0).client();
        var matcher = new CountingMatcher(java.util.Map.of(
                "encrypted:new-ref", "new-secret",
                "encrypted:current-ref", "old-secret"));

        assertThat(rotated.acceptsSecret("old-secret", t0.plus(Duration.ofHours(2)), matcher))
                .as("the overlap window has lapsed").isFalse();
    }

    @Test
    void acceptsSecretRejectsAWrongSecretWithNoUsablePrevious() {
        var c = confidential();
        var matcher = new CountingMatcher(java.util.Map.of("encrypted:current-ref", "the-secret"));
        assertThat(c.acceptsSecret("not-it", Instant.now(), matcher)).isFalse();
    }

    /// Mutant: `acceptsSecret` short-circuits on a current-secret match and
    /// never decrypts the previous ref. Pinned by asserting the call COUNT,
    /// not just the boolean result — a lazy `||` over two calls would still
    /// return `true` here (current matches) while leaving `calls` at 1.
    @Test
    void acceptsSecretDecryptsBothRefsEvenWhenTheCurrentOneAlreadyMatches() {
        Instant t0 = Instant.now();
        var rotated = confidential().rotateSecret("encrypted:new-ref", Duration.ofHours(24), t0).client();
        var matcher = new CountingMatcher(java.util.Map.of(
                "encrypted:new-ref", "new-secret",       // matches — this is "current"
                "encrypted:current-ref", "old-secret"));  // does NOT match "new-secret" but the ref IS usable

        boolean ok = rotated.acceptsSecret("new-secret", t0.plusSeconds(1), matcher);
        assertThat(ok).as("matches on the current secret").isTrue();
        assertThat(matcher.calls.get())
                .as("both the current AND the usable previous ref must be decrypted, never short-circuited")
                .isEqualTo(2);
    }

    /// The mirror case named in the improvements spec: a WRONG current
    /// secret plus a VALID previous one must still authenticate, and both
    /// refs must have been decrypted.
    @Test
    void acceptsSecretAuthenticatesOnAWrongCurrentPlusAValidPrevious() {
        Instant t0 = Instant.now();
        var rotated = confidential().rotateSecret("encrypted:new-ref", Duration.ofHours(24), t0).client();
        var matcher = new CountingMatcher(java.util.Map.of(
                "encrypted:new-ref", "new-secret",
                "encrypted:current-ref", "old-secret"));

        boolean ok = rotated.acceptsSecret("old-secret", t0.plusSeconds(1), matcher);
        assertThat(ok).as("a still-valid previous secret must authenticate").isTrue();
        assertThat(matcher.calls.get()).isEqualTo(2);
    }

    @Test
    void acceptsSecretOnAPublicClientWithNoSecretNeverMatches() {
        var c = OAuthClient.create("cli_1", "X", ClientType.PUBLIC);
        var matcher = new CountingMatcher(java.util.Map.of());
        assertThat(c.acceptsSecret("anything", Instant.now(), matcher)).isFalse();
        assertThat(matcher.calls.get()).as("nothing to decrypt when there is no secretRef").isZero();
    }

    /// `acceptsSecret` is shape-agnostic (`docs/spec/encryption.md` §3): a real
    /// [Encryption]'s `verifySecret` accepts a current ref already migrated to
    /// `hashed:v1:` alongside a previous ref still in its legacy `encrypted:`
    /// form — the client-migrates-independently case a matcher-level fake
    /// cannot exercise.
    @Test
    void acceptsSecretWorksAcrossMixedHashedAndEncryptedShapes() {
        var enc = Encryption.withKey(Encryption.generateKey());
        Instant t0 = Instant.now();
        var c = OAuthClient.create("cli_1", "My Client", ClientType.CONFIDENTIAL);
        var rotated = new OAuthClient(c.id(), c.clientId(), c.clientName(), c.clientType(),
                enc.hashSecretRef("new-secret"), enc.encryptSecretRef("old-secret"), t0.plusSeconds(3600), null,
                c.redirectUris(), c.postLogoutRedirectUris(), c.grantTypes(), c.defaultScopes(), c.allowedOrigins(),
                c.applicationIds(), c.pkceRequired(), c.active(), c.principalId(), c.portalClientId(), c.portalAppId(),
                c.apiAccess(), c.createdAt(), c.updatedAt());

        BiPredicate<String, String> matches = (ref, provided) ->
                enc.verifySecret(ref, provided) instanceof Encryption.SecretVerification.Matched;

        assertThat(rotated.acceptsSecret("new-secret", t0, matches))
                .as("current ref, already hashed").isTrue();
        assertThat(rotated.acceptsSecret("old-secret", t0, matches))
                .as("previous ref, still legacy-encrypted").isTrue();
        assertThat(rotated.acceptsSecret("wrong", t0, matches)).isFalse();
    }
}
