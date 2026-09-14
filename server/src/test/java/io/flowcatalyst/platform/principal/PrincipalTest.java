package io.flowcatalyst.platform.principal;

import io.flowcatalyst.platform.emaildomainmapping.EmailDomainMapping;
import io.flowcatalyst.platform.emaildomainmapping.ScopeType;
import io.flowcatalyst.platform.emaildomainmapping.TwoFactorPolicy;
import io.flowcatalyst.platform.principal.operations.ClientAssociationMode;
import io.flowcatalyst.platform.principal.operations.CreateCommand;
import io.flowcatalyst.platform.principal.operations.ResetPasswordCommand;
import io.flowcatalyst.platform.principal.operations.SyncPrincipalInput;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.sdk.usecase.UseCaseError;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The aggregate's pure rules (spec §1–2, §4.1, §7): enum readers, the email
/// parser, the password policy, every transition with its error code, scope
/// derivation — no database involved.
class PrincipalTest {

    private static final EmailAddress EMAIL = EmailAddress.parse("Ada@Example.com");

    private static void assertUseCaseError(ThrowingCallable call, Class<? extends UseCaseError> kind, String code) {
        assertThatThrownBy(call)
                .isInstanceOf(UseCaseException.class)
                .extracting(t -> ((UseCaseException) t).error())
                .satisfies(err -> {
                    assertThat(err).as("error kind").isInstanceOf(kind);
                    assertThat(err.code()).as("error code").isEqualTo(code);
                });
    }

    private static EmailDomainMapping mapping(ScopeType scope, String primary, List<String> granted) {
        Instant now = Instant.now();
        return new EmailDomainMapping("edm_1", "corp.test", "idp_1", scope, primary, List.of(), granted, null, TwoFactorPolicy.OFF, now, now);
    }

    // ── Enum readers ───────────────────────────────────────────────────────

    // X-06 (ruled 2026-09-01): the STORED readers below used to default
    // silently to CLIENT / USER on an unrecognised or null value; they now
    // fail loudly (see PrincipalRepositoryTest for the corrupt-row wiring).
    // The WIRE reader (parseStrict) is untouched — out of scope for X-06.

    @ParameterizedTest
    @CsvSource({"ANCHOR,ANCHOR", "PARTNER,PARTNER", "CLIENT,CLIENT"})
    void scopeReadsStrictly(String stored, UserScope expected) {
        assertThat(UserScope.parse(stored)).isEqualTo(expected);
    }

    /// Mutation check: restoring the old `default -> CLIENT` branch makes
    /// this fail, since `parse("GLOBAL")`/`parse(null)` would return CLIENT
    /// instead of throwing.
    @ParameterizedTest
    @ValueSource(strings = {"GLOBAL", "anchor", ""})
    void scopeRejectsAnyUnrecognisedStoredValue(String stored) {
        assertThatThrownBy(() -> UserScope.parse(stored)).isInstanceOf(UserScope.UnrecognisedUserScopeException.class);
    }

    @Test
    void scopeRejectsNullStoredValue() {
        assertThatThrownBy(() -> UserScope.parse(null)).isInstanceOf(UserScope.UnrecognisedUserScopeException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GLOBAL", "anchor", ""})
    void scopeRejectsUnknownWireValues(String wire) {
        assertUseCaseError(() -> UserScope.parseStrict(wire), UseCaseError.Validation.class, "INVALID_SCOPE");
    }

    @Test
    void typeReadsStrictly() {
        assertThat(PrincipalType.parse("USER")).isEqualTo(PrincipalType.USER);
        assertThat(PrincipalType.parse("SERVICE")).isEqualTo(PrincipalType.SERVICE);
    }

    /// Mutation check: restoring the old `"SERVICE".equals(s) ? SERVICE :
    /// USER` body makes this fail, since both cases would return USER
    /// instead of throwing.
    @ParameterizedTest
    @ValueSource(strings = {"BOT", "user", ""})
    void typeRejectsAnyUnrecognisedStoredValue(String stored) {
        assertThatThrownBy(() -> PrincipalType.parse(stored)).isInstanceOf(PrincipalType.UnrecognisedPrincipalTypeException.class);
    }

    @Test
    void typeRejectsNullStoredValue() {
        assertThatThrownBy(() -> PrincipalType.parse(null)).isInstanceOf(PrincipalType.UnrecognisedPrincipalTypeException.class);
    }

    @Test
    void clientAssociationModeReadsCaseInsensitivelyAndUnknownAsNull() {
        assertThat(ClientAssociationMode.parse(" to_partner ")).isEqualTo(ClientAssociationMode.TO_PARTNER);
        assertThat(ClientAssociationMode.parse("CHANGE_CLIENT")).isEqualTo(ClientAssociationMode.CHANGE_CLIENT);
        assertThat(ClientAssociationMode.parse("MERGE")).isNull();
        assertThat(ClientAssociationMode.parse(null)).isNull();
    }

    // ── Email ──────────────────────────────────────────────────────────────

    @Test
    void emailNormalisesAndSplits() {
        assertThat(EMAIL.value()).isEqualTo("ada@example.com");
        assertThat(EMAIL.localPart()).isEqualTo("ada");
        assertThat(EMAIL.domain()).isEqualTo("example.com");
        assertThat(EmailAddress.parse("  BOB@X.IO ").value()).isEqualTo("bob@x.io");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void emailRequired(String raw) {
        assertUseCaseError(() -> EmailAddress.parse(raw), UseCaseError.Validation.class, "EMAIL_REQUIRED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"plainaddress", "user@host", "@x.io", "a b@x.io"})
    void emailRejectsMalformed(String raw) {
        assertUseCaseError(() -> EmailAddress.parse(raw), UseCaseError.Validation.class, "INVALID_EMAIL");
    }

    @ParameterizedTest
    @CsvSource({"ada@example.com,example.com", "a@b,b", "nodomain,", "trailing@,", ","})
    void domainOfIsTheLooseHandlerRule(String email, String domain) {
        assertThat(EmailAddress.domainOf(email)).isEqualTo(domain);
    }

    // ── Password policy (spec §4.1) ────────────────────────────────────────

    @ParameterizedTest
    @CsvSource({
            "correct-horse-battery,ACCEPTED",
            "sûrement-pas-devinable-9,ACCEPTED",
            "a1b2c3!,PASSWORD_TOO_SHORT",
            "aaaaaaaaaa,PASSWORD_TOO_WEAK",
            "andrew@belac.io,PASSWORD_CONTAINS_IDENTITY",
            "Andrew@Belac.IO,PASSWORD_CONTAINS_IDENTITY",
            "xx-andrew@belac.io-99,PASSWORD_CONTAINS_IDENTITY",
            "andrew2026!,PASSWORD_CONTAINS_IDENTITY",
            "!6202werdna,PASSWORD_CONTAINS_IDENTITY",
            "graaff-rules-1,PASSWORD_CONTAINS_IDENTITY",
            "sunshine,PASSWORD_TOO_COMMON",
            "Passw0rd,PASSWORD_TOO_COMMON",
            "FlowCatalyst#2026,PASSWORD_TOO_COMMON"})
    void passwordPolicyTable(String password, String expected) {
        var verdict = PasswordPolicy.check(password, "andrew@belac.io", "Andrew Graaff");
        String got = verdict instanceof PasswordPolicy.Rejected r ? r.code() : "ACCEPTED";
        assertThat(got).isEqualTo(expected);
    }

    @Test
    void passwordPolicyBoundsAndEdgeCases() {
        assertThat(PasswordPolicy.check("x1".repeat(65), "", "")).isInstanceOf(PasswordPolicy.Rejected.class)
                .extracting(v -> ((PasswordPolicy.Rejected) v).code()).isEqualTo("PASSWORD_TOO_LONG");
        assertThat(PasswordPolicy.check("majority-vote-42", "jo@x.com", "")).as("short local part only rejects on equality")
                .isInstanceOf(PasswordPolicy.Accepted.class);
        assertThat(PasswordPolicy.check("perfectly-fine-pass", null, null)).isInstanceOf(PasswordPolicy.Accepted.class);
        assertUseCaseError(() -> PasswordPolicy.check("sunshine", "", "").require(), UseCaseError.Validation.class, "PASSWORD_TOO_COMMON");
    }

    // ── Factories ──────────────────────────────────────────────────────────

    @Test
    void newUserIsActiveNamedAfterItsEmailWithAllApplications() {
        var p = Principal.newUser(EMAIL, UserScope.CLIENT);
        assertThat(p.id()).startsWith("prn_");
        assertThat(p.isUser()).isTrue();
        assertThat(p.name()).isEqualTo("ada@example.com");
        assertThat(p.email()).isEqualTo("ada@example.com");
        assertThat(p.active()).isTrue();
        assertThat(p.allApplications()).isTrue();
        assertThat(p.roles()).isEmpty();
        assertThat(p.userIdentity().providerOrInternal()).isEqualTo("INTERNAL");
        assertThat(p.userIdentity().hasPassword()).isFalse();
    }

    @Test
    void newPortalUserIsInert() {
        var p = Principal.newPortalUser(EMAIL).withProvider("OIDC");
        assertThat(p.scope()).isEqualTo(UserScope.CLIENT);
        assertThat(p.clientId()).isNull();
        assertThat(p.allApplications()).isFalse();
        assertThat(p.userIdentity().provider()).isEqualTo("OIDC");
        assertThat(p.isFederated()).isTrue();
    }

    @Test
    void newServiceIsAnchorTier() {
        var p = Principal.newService("sa_1", "Billing bot");
        assertThat(p.isService()).isTrue();
        assertThat(p.scope()).isEqualTo(UserScope.ANCHOR);
        assertThat(p.email()).isNull();
        assertThat(p.userIdentity()).isNull();
    }

    // ── Transitions ────────────────────────────────────────────────────────

    @Test
    void activateAndDeactivateAreIdempotentFlips() {
        var p = Principal.newUser(EMAIL, UserScope.CLIENT);
        assertThat(p.deactivate().active()).isFalse();
        assertThat(p.deactivate().deactivate().active()).isFalse();
        assertThat(p.deactivate().activate().active()).isTrue();
    }

    @Test
    void updateReplacesPresentFieldsAndAssertsTheEmail() {
        var p = Principal.newUser(EMAIL, UserScope.CLIENT);
        var updated = p.update(new Principal.Changes("  Ada Lovelace ", false, " ADA@example.com "));
        assertThat(updated.name()).isEqualTo("Ada Lovelace");
        assertThat(updated.active()).isFalse();
        assertThat(p.update(new Principal.Changes(null, null, null)).name()).isEqualTo("ada@example.com");
        assertUseCaseError(() -> p.update(new Principal.Changes(null, null, "other@example.com")),
                UseCaseError.Validation.class, "EMAIL_IMMUTABLE");
    }

    @Test
    void passwordAndOidcTransitions() {
        var p = Principal.newUser(EMAIL, UserScope.ANCHOR).withPasswordHash("$argon2id$hash");
        assertThat(p.userIdentity().hasPassword()).isTrue();
        assertThat(p.asOidcUser().userIdentity().hasPassword()).isFalse();
        assertThat(p.asOidcUser().userIdentity().provider()).isEqualTo("OIDC");
        assertUseCaseError(() -> Principal.newService("sa_1", "bot").withPasswordHash("h"), UseCaseError.Conflict.class, "NOT_A_USER");
    }

    @Test
    void developerSecretSetAndClear() {
        var p = Principal.newUser(EMAIL, UserScope.ANCHOR);
        assertThat(p.hasDeveloperSecret()).isFalse();
        var set = p.withDeveloperSecret("enc:ref");
        assertThat(set.hasDeveloperSecret()).isTrue();
        assertThat(set.userIdentity().devClientSecretUpdatedAt()).isNotNull();
        var cleared = set.clearDeveloperSecret();
        assertThat(cleared.hasDeveloperSecret()).isFalse();
        assertThat(cleared.userIdentity().devClientSecretUpdatedAt()).isNull();
        assertThat(Principal.newService("sa_1", "bot").clearDeveloperSecret().userIdentity()).isNull();
    }

    @Test
    void assignRolesReplacesEverythingAsAdminAssigned() {
        var p = Principal.newUser(EMAIL, UserScope.ANCHOR)
                .syncSourcedRoles(RoleAssignment.IDP_SYNC, List.of("app:idp")).principal();
        var change = p.assignRoles(List.of("app:idp", "app:admin"));
        assertThat(change.roles()).containsExactly("app:idp", "app:admin");
        assertThat(change.added()).containsExactly("app:admin");
        assertThat(change.removed()).isEmpty();
        assertThat(change.principal().roles()).extracting(RoleAssignment::assignmentSource).containsOnly(RoleAssignment.ADMIN_ASSIGNED);
        var removed = change.principal().assignRoles(List.of());
        assertThat(removed.removed()).containsExactlyInAnyOrder("app:idp", "app:admin");
    }

    @Test
    void syncSourcedRolesKeepsOtherSourcesAndDedupes() {
        var p = Principal.newUser(EMAIL, UserScope.CLIENT).assignRoles(List.of("app:admin")).principal()
                .syncSourcedRoles(RoleAssignment.SDK_SYNC, List.of("app:old")).principal();
        var change = p.syncSourcedRoles(RoleAssignment.SDK_SYNC, List.of("app:admin", "app:new"));
        assertThat(change.roles()).containsExactly("app:admin", "app:new");
        assertThat(change.added()).containsExactly("app:new");
        assertThat(change.removed()).containsExactly("app:old");
        assertThat(change.principal().roles()).extracting(RoleAssignment::role, RoleAssignment::assignmentSource)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("app:admin", RoleAssignment.ADMIN_ASSIGNED),
                        org.assertj.core.groups.Tuple.tuple("app:new", RoleAssignment.SDK_SYNC));
        assertThat(change.principal().hasRolesFrom(RoleAssignment.SDK_SYNC)).isTrue();
        assertThat(change.principal().stripSourcedRoles(RoleAssignment.SDK_SYNC).roleNames()).containsExactly("app:admin");
    }

    @Test
    void applicationAccessReportsTheDifference() {
        var p = Principal.newUser(EMAIL, UserScope.CLIENT);
        var first = p.assignApplicationAccess(List.of("app_a", "app_b"), false);
        assertThat(first.added()).containsExactly("app_a", "app_b");
        assertThat(first.principal().allApplications()).isFalse();
        var second = first.principal().assignApplicationAccess(List.of("app_b", "app_c"), null);
        assertThat(second.added()).containsExactly("app_c");
        assertThat(second.removed()).containsExactly("app_a");
        assertThat(second.principal().allApplications()).as("null leaves the flag").isFalse();
    }

    @Test
    void clientAssociationTransitions() {
        var client = Principal.newUser(EMAIL, UserScope.CLIENT).withClientId("clt_old");
        assertThat(client.toAnchor().principal().scope()).isEqualTo(UserScope.ANCHOR);
        assertThat(client.toAnchor().principal().clientId()).isNull();
        assertThat(client.changeClient("clt_new").principal().clientId()).isEqualTo("clt_new");
        var promoted = client.toPartner("clt_new");
        assertThat(promoted.principal().scope()).isEqualTo(UserScope.PARTNER);
        assertThat(promoted.principal().clientId()).isNull();
        assertThat(promoted.grantClientIds()).as("old home client kept as a grant").containsExactly("clt_old", "clt_new");
        assertThat(client.toPartner("clt_old").grantClientIds()).containsExactly("clt_old");
        assertThat(Principal.newUser(EMAIL, UserScope.ANCHOR).toPartner("clt_x").grantClientIds()).containsExactly("clt_x");
    }

    // ── awaitingPasswordSetup (spec app-managed-invitations.md §2) ──────────

    private static Principal awaitingCase(boolean active, PrincipalType type, String passwordHash, String provider, ExternalIdentity external) {
        Instant now = Instant.now();
        UserIdentity identity = type == PrincipalType.SERVICE ? null
                : new UserIdentity("a@b.io", provider, null, passwordHash, null, null, null);
        return new Principal("prn_x", type, UserScope.CLIENT, null, null, "n", active, identity,
                type == PrincipalType.SERVICE ? "sa_1" : null, List.of(), List.of(), List.of(), true, external, now, now);
    }

    /// The nine Go cases (`Principal.awaitingPasswordSetup`, spec §2/§3):
    /// every axis of the predicate flipped independently, plus the two cases
    /// the spec calls out explicitly — a non-OIDC provider label is still
    /// eligible, and the check is the exact-null test on `passwordHash`, not
    /// blank-or-null.
    @Test
    void awaitingPasswordSetupTable() {
        assertThat(awaitingCase(true, PrincipalType.USER, null, null, null).awaitingPasswordSetup())
                .as("baseline: active, passwordless, internal, not federated").isTrue();
        assertThat(awaitingCase(false, PrincipalType.USER, null, null, null).awaitingPasswordSetup())
                .as("mutant: active dropped from the predicate — inactive account").isFalse();
        assertThat(awaitingCase(true, PrincipalType.SERVICE, null, null, null).awaitingPasswordSetup())
                .as("mutant: isUser()/userIdentity!=null dropped — a service principal").isFalse();
        assertThat(awaitingCase(true, PrincipalType.USER, "$argon2id$hash", null, null).awaitingPasswordSetup())
                .as("mutant: passwordHash==null dropped — a password is already set").isFalse();
        assertThat(awaitingCase(true, PrincipalType.USER, null, "OIDC", null).awaitingPasswordSetup())
                .as("mutant: isFederated() dropped — an OIDC-provider user").isFalse();
        assertThat(awaitingCase(true, PrincipalType.USER, null, null, new ExternalIdentity("okta", "sub1")).awaitingPasswordSetup())
                .as("mutant: isFederated() dropped — an external identity regardless of provider label").isFalse();
        assertThat(awaitingCase(true, PrincipalType.USER, null, "LEGACY_IMPORT", null).awaitingPasswordSetup())
                .as("spec: a non-OIDC provider label is still eligible — mutant: any non-null provider treated as federated").isTrue();
        assertThat(awaitingCase(false, PrincipalType.USER, null, "OIDC", null).awaitingPasswordSetup())
                .as("inactive AND federated together").isFalse();
        assertThat(awaitingCase(true, PrincipalType.USER, "", null, null).awaitingPasswordSetup())
                .as("mutant: passwordHash.isBlank() used instead of == null — an empty (not null) hash").isFalse();
    }

    @Test
    void reachesClientIsHomeOrGrant() {
        var p = new Principal("prn_1", PrincipalType.USER, UserScope.PARTNER, null, null, "n", true, UserIdentity.of("a@b.io"), null,
                List.of(), List.of("clt_g"), List.of(), true, null, Instant.now(), Instant.now());
        assertThat(p.reachesClient("clt_g")).isTrue();
        assertThat(p.reachesClient("clt_x")).isFalse();
        assertThat(p.withClientId("clt_x").reachesClient("clt_x")).isTrue();
    }

    // ── Secrets never print ────────────────────────────────────────────────

    @Test
    void secretCarriersMaskToStringAndAuditJson() throws Exception {
        var identity = new UserIdentity("a@b.io", null, null, "$argon2id$secret", null, "enc:devsecret", null);
        assertThat(identity.toString()).doesNotContain("secret").contains("***");
        assertThat(Principal.newUser(EMAIL, UserScope.CLIENT).withPasswordHash("$argon2id$secret").toString()).doesNotContain("secret");
        var create = new CreateCommand("a@b.io", null, "CLIENT", "clt_1", "hunter22-plain", null);
        assertThat(create.toString()).doesNotContain("hunter22");
        assertThat(Json.MAPPER.writeValueAsString(create)).doesNotContain("hunter22");
        var reset = new ResetPasswordCommand("prn_1", "hunter22-plain", null);
        assertThat(reset.toString()).doesNotContain("hunter22");
        assertThat(Json.MAPPER.writeValueAsString(reset)).doesNotContain("hunter22");
        var sync = new SyncPrincipalInput("a@b.io", "A", List.of(), true, "$2y$hash");
        assertThat(sync.toString()).doesNotContain("$2y$");
        assertThat(Json.MAPPER.writeValueAsString(sync)).doesNotContain("$2y$");
    }

    // ── Scope derivation (spec §7) ─────────────────────────────────────────

    @Test
    void requestedScopeWinsAndDomainOnlyConfirms() {
        assertThat(UserScopeDerivation.derive(null, false, null, "clt_x"))
                .isEqualTo(new UserScopeDerivation.Derived(UserScope.CLIENT, "clt_x"));
        assertThat(UserScopeDerivation.derive(null, true, null, "clt_x").scope()).as("anchor domain never promotes").isEqualTo(UserScope.CLIENT);
        assertThat(UserScopeDerivation.derive(null, false, mapping(ScopeType.ANCHOR, null, List.of()), "clt_x").scope()).isEqualTo(UserScope.CLIENT);
        assertThat(UserScopeDerivation.derive(null, false, mapping(ScopeType.PARTNER, null, List.of("clt_x")), "clt_x").scope()).isEqualTo(UserScope.CLIENT);
        assertThat(UserScopeDerivation.derive(null, false, mapping(ScopeType.CLIENT, "clt_primary", List.of()), null).clientId()).isEqualTo("clt_primary");
        assertThat(UserScopeDerivation.derive(null, false, null, null).clientId()).isNull();
        assertThat(UserScopeDerivation.derive("CLIENT", false, mapping(ScopeType.CLIENT, "clt_primary", List.of()), "clt_req").clientId()).isEqualTo("clt_req");
        assertThat(UserScopeDerivation.derive("client", true, null, "clt_x").scope()).as("explicit downgrade allowed").isEqualTo(UserScope.CLIENT);
        assertThat(UserScopeDerivation.derive("ANCHOR", true, null, "clt_x")).isEqualTo(new UserScopeDerivation.Derived(UserScope.ANCHOR, null));
        assertThat(UserScopeDerivation.derive("ANCHOR", false, mapping(ScopeType.ANCHOR, null, List.of()), null).scope()).isEqualTo(UserScope.ANCHOR);
        assertThat(UserScopeDerivation.derive("PARTNER", false, mapping(ScopeType.PARTNER, null, List.of("clt_a", "clt_b")), "clt_b"))
                .isEqualTo(new UserScopeDerivation.Derived(UserScope.PARTNER, "clt_b"));
        assertThat(UserScopeDerivation.derive("PARTNER", false, mapping(ScopeType.PARTNER, "clt_p", List.of()), "clt_p").scope()).isEqualTo(UserScope.PARTNER);
    }

    @Test
    void scopeDerivationRejections() {
        assertUseCaseError(() -> UserScopeDerivation.derive("ANCHOR", false, null, null), UseCaseError.Validation.class, "ANCHOR_DOMAIN_REQUIRED");
        assertUseCaseError(() -> UserScopeDerivation.derive("ANCHOR", false, mapping(ScopeType.CLIENT, null, List.of()), null), UseCaseError.Validation.class, "ANCHOR_DOMAIN_REQUIRED");
        assertUseCaseError(() -> UserScopeDerivation.derive("PARTNER", false, null, "clt_x"), UseCaseError.Validation.class, "PARTNER_DOMAIN_REQUIRED");
        assertUseCaseError(() -> UserScopeDerivation.derive("PARTNER", false, mapping(ScopeType.PARTNER, null, List.of()), null), UseCaseError.Validation.class, "CLIENT_REQUIRED");
        assertUseCaseError(() -> UserScopeDerivation.derive("PARTNER", false, mapping(ScopeType.PARTNER, null, List.of("clt_a")), "clt_b"), UseCaseError.Validation.class, "CLIENT_NOT_ALLOWED");
        assertUseCaseError(() -> UserScopeDerivation.derive("GLOBAL", false, null, null), UseCaseError.Validation.class, "INVALID_SCOPE");
    }

    @Test
    void domainScopeAndAllowedClients() {
        assertThat(UserScopeDerivation.forDomain(true, mapping(ScopeType.CLIENT, "c", List.of()))).isEqualTo(UserScope.ANCHOR);
        assertThat(UserScopeDerivation.forDomain(false, null)).isEqualTo(UserScope.CLIENT);
        assertThat(UserScopeDerivation.forDomain(false, mapping(ScopeType.PARTNER, null, List.of()))).isEqualTo(UserScope.PARTNER);
        assertThat(UserScopeDerivation.allowedClientIds(mapping(ScopeType.PARTNER, "clt_p", List.of("clt_p", "clt_g")))).containsExactly("clt_p", "clt_g");
        assertThat(UserScopeDerivation.allowedClientIds(mapping(ScopeType.CLIENT, "clt_p", List.of("ignored")))).containsExactly("clt_p");
        assertThat(UserScopeDerivation.allowedClientIds(mapping(ScopeType.ANCHOR, "clt_p", List.of()))).isEmpty();
        assertThat(UserScopeDerivation.allowedClientIds(null)).isEmpty();
        assertThat(UserScopeDerivation.ownerClientIds(mapping(ScopeType.CLIENT, "clt_p", List.of("clt_g")))).as("grants are not ownership").containsExactly("clt_p");
    }
}
