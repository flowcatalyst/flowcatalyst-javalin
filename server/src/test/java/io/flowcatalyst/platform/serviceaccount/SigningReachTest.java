package io.flowcatalyst.platform.serviceaccount;

import io.flowcatalyst.platform.shared.auth.AuthContext;
import io.flowcatalyst.platform.shared.auth.Scope;
import io.flowcatalyst.sdk.result.Result;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/// [SigningReach]'s rule, case by case, with in-memory lookups
/// (security-fixes-2026-09-24 S3.1/S3.2). Each test names the condition it
/// pins; the operation and API tests cover the wiring.
class SigningReachTest {

    private static final String CLIENT_A = "cli_a";
    private static final String CLIENT_B = "cli_b";
    private static final String APP_X = "app_x";
    private static final String APP_Y = "app_y";

    /// The principal `prn_app_x` is application X's own service account.
    private static final ServiceAccount APP_X_OWN = account("sac_app_x", List.of(), APP_X);
    private static final ServiceAccount APP_Y_OWN = account("sac_app_y", List.of(), APP_Y);

    private static final SigningReach REACH = new SigningReach(
            id -> Optional.ofNullable(Map.of(APP_X_OWN.id(), APP_X_OWN, APP_Y_OWN.id(), APP_Y_OWN).get(id)),
            principalId -> Optional.ofNullable(Map.of("prn_app_x", APP_X_OWN.id(), "prn_app_y", APP_Y_OWN.id()).get(principalId)),
            code -> Optional.empty());

    private static ServiceAccount account(String id, List<String> clientIds, String applicationId) {
        Instant now = Instant.now();
        return new ServiceAccount(id, "code-" + id, "name", null, true, clientIds, null, applicationId,
                WebhookCredentials.none(), List.of(), null, now, now);
    }

    private static AuthContext caller(String principalId, Scope scope, List<String> clients, String... permissions) {
        return new AuthContext(principalId, scope, null, clients, List.of(), List.of(), true, List.of(permissions));
    }

    private static final AuthContext CLIENT_A_USER = caller("prn_user_a", Scope.CLIENT, List.of(CLIENT_A));
    private static final AuthContext PARTNER_AB_USER = caller("prn_user_ab", Scope.PARTNER, List.of(CLIENT_A, CLIENT_B));
    private static final AuthContext ANCHOR_OPERATOR = caller("prn_operator", Scope.ANCHOR, List.of());
    private static final AuthContext SUPER_ADMIN = caller("prn_super", Scope.CLIENT, List.of(), "platform:*:*:*");
    private static final AuthContext APP_X_CALLER = caller("prn_app_x", Scope.ANCHOR, List.of());
    private static final AuthContext APP_Y_CALLER = caller("prn_app_y", Scope.ANCHOR, List.of());

    private static boolean allowed(Result<?, SigningReach.Refusal> r) {
        return r instanceof Result.Ok<?, ?>;
    }

    private static SigningReach.Refusal refusal(Result<?, SigningReach.Refusal> r) {
        return switch (r) {
            case Result.Ok<?, SigningReach.Refusal> ok -> throw new AssertionError("expected a refusal, was allowed");
            case Result.Err<?, SigningReach.Refusal>(var why) -> why;
        };
    }

    // ── tenancy: the caller must cover every client the account reaches ────

    @Test
    void anAnchorTierAccountIsCoveredOnlyByAnAnchor() {
        ServiceAccount anchorTier = account("sac_anchor", List.of(), null);
        assertThat(refusal(REACH.mayUse(CLIENT_A_USER, anchorTier, null)))
                .isInstanceOf(SigningReach.Refusal.OutOfReach.class);
        assertThat(allowed(REACH.mayUse(ANCHOR_OPERATOR, anchorTier, null))).isTrue();
    }

    @Test
    void aClientLinkedAccountNeedsEveryOneOfItsClients() {
        ServiceAccount shared = account("sac_ab", List.of(CLIENT_A, CLIENT_B), null);
        assertThat(refusal(REACH.mayUse(CLIENT_A_USER, shared, null)))
                .as("confined to A, the account also signs for B")
                .isInstanceOf(SigningReach.Refusal.OutOfReach.class);
        assertThat(allowed(REACH.mayUse(PARTNER_AB_USER, shared, null))).isTrue();
        assertThat(allowed(REACH.mayUse(CLIENT_A_USER, account("sac_a", List.of(CLIENT_A), null), null))).isTrue();
    }

    @Test
    void aSuperAdminMayUseAnyAccount() {
        assertThat(allowed(REACH.mayUse(SUPER_ADMIN, account("sac_anchor", List.of(), null), null))).isTrue();
        assertThat(allowed(REACH.mayUse(SUPER_ADMIN, APP_X_OWN, null))).isTrue();
        assertThat(allowed(REACH.mayUseApplication(SUPER_ADMIN, APP_X, "x"))).isTrue();
    }

    // ── application-owned accounts ──────────────────────────────────────────

    @Test
    void anApplicationsAccountIsUsableByThatApplicationOnly() {
        assertThat(allowed(REACH.mayUse(APP_X_CALLER, APP_X_OWN, null))).isTrue();
        assertThat(refusal(REACH.mayUse(APP_Y_CALLER, APP_X_OWN, null)))
                .isInstanceOf(SigningReach.Refusal.OtherApplication.class);
        assertThat(refusal(REACH.mayUse(ANCHOR_OPERATOR, APP_X_OWN, null)))
                .as("anchor reach is not being the application")
                .isInstanceOf(SigningReach.Refusal.OtherApplication.class);
    }

    @Test
    void aConfigurationTheApplicationOwnsMayUseItsAccount() {
        assertThat(allowed(REACH.mayUse(CLIENT_A_USER, APP_X_OWN, APP_X))).isTrue();
        assertThat(refusal(REACH.mayUse(CLIENT_A_USER, APP_X_OWN, APP_Y)))
                .isInstanceOf(SigningReach.Refusal.OtherApplication.class);
    }

    @Test
    void anApplicationMayNotBorrowAnAccountThatIsNotItsOwn() {
        // Application credentials are anchor-tier, so tenancy alone would allow this.
        assertThat(refusal(REACH.mayUse(APP_X_CALLER, account("sac_anchor", List.of(), null), null)))
                .isInstanceOf(SigningReach.Refusal.ApplicationCaller.class);
        assertThat(refusal(REACH.mayUse(APP_X_CALLER, account("sac_a", List.of(CLIENT_A), null), null)))
                .isInstanceOf(SigningReach.Refusal.ApplicationCaller.class);
    }

    // ── the resolver's step 3: an application's own account ─────────────────

    @Test
    void onlyTheApplicationItselfMayCauseItsOwnSignature() {
        assertThat(allowed(REACH.mayUseApplication(APP_X_CALLER, APP_X, "x"))).isTrue();
        assertThat(refusal(REACH.mayUseApplication(APP_Y_CALLER, APP_X, "x")))
                .isInstanceOf(SigningReach.Refusal.NotTheApplication.class);
        assertThat(refusal(REACH.mayUseApplication(ANCHOR_OPERATOR, APP_X, "x")))
                .isInstanceOf(SigningReach.Refusal.NotTheApplication.class);
        assertThat(refusal(REACH.mayUseApplication(CLIENT_A_USER, APP_X, "x")))
                .isInstanceOf(SigningReach.Refusal.NotTheApplication.class);
    }

    @Test
    void perRequestLooksEachThingUpOnce() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var counting = new SigningReach(id -> {
            calls.incrementAndGet();
            return Optional.of(APP_X_OWN);
        }, principalId -> Optional.of(APP_X_OWN.id()), code -> Optional.of(APP_X));
        var memo = counting.perRequest();
        for (int i = 0; i < 5; i++) {
            memo.mayUse(APP_X_CALLER, APP_X_OWN, null);
            memo.account(APP_X_OWN.id());
        }
        assertThat(calls).as("the caller's account and the named account, once each — same id here").hasValue(1);
    }
}
