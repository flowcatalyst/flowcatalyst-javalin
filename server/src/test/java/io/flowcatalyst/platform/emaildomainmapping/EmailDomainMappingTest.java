package io.flowcatalyst.platform.emaildomainmapping;

import io.flowcatalyst.sdk.usecase.UseCaseError;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The aggregate's pure rules (spec §1–2, §4): domain normalisation, the
/// scope and method readers, the 2FA invariant and the move transition —
/// no database involved.
class EmailDomainMappingTest {

    private static final String IDP = "idp_0123456789ABC";

    // ── EmailDomain (spec §4 — the pinned table login routing relies on) ───

    @ParameterizedTest(name = "[{0}] \"{1}\" → \"{2}\"")
    @CsvSource({
            // case: lower-cased before validation and storage
            "case,   Example.COM,                    example.com",
            "case,   ACME.IO,                        acme.io",
            // trim: leading/trailing whitespace dropped
            "trim,   '  example.com  ',              example.com",
            // shape: at least one '.', labels otherwise unchecked
            "shape,  a.b,                            a.b",
            "shape,  sub.domain.example.co.uk,       sub.domain.example.co.uk",
            "shape,  a-b.example.com,                a-b.example.com",
            "shape,  xn--bcher-kva.example,          xn--bcher-kva.example",
            // edge: only ' ', '/', '@' are banned — these pass (spec §4, accident?)
            "edge,   example.,                       example.",
            "edge,   .com,                           .com",
            "edge,   exa_mple.com,                   exa_mple.com",
            "edge,   .,                              ."})
    void domainAcceptsDottedNamesTrimmedAndLowerCased(String rule, String raw, String expected) {
        assertThat(EmailDomain.parse(raw)).as(rule).isEqualTo(new EmailDomain(expected));
    }

    @ParameterizedTest(name = "[{0}] \"{1}\" is rejected")
    @CsvSource({
            // dot: a domain needs at least one '.'
            "dot,    nodot",
            "dot,    localhost",
            // space: interior whitespace (trim only removes the ends)
            "space,  'exam ple.com'",
            // slash: no paths or schemes
            "slash,  example.com/path",
            "slash,  https://example.com",
            // at: a domain, not an address
            "at,     user@example.com",
            "at,     @example.com"})
    void domainRejectsAnythingButADottedName(String rule, String raw) {
        assertUseCaseError(() -> EmailDomain.parse(raw), UseCaseError.Validation.class, "INVALID_EMAIL_DOMAIN");
        assertThatThrownBy(() -> EmailDomain.parse(raw)).as(rule).hasMessageContaining(EmailDomain.FORMAT_MESSAGE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void domainRejectsBlank(String raw) {
        assertUseCaseError(() -> EmailDomain.parse(raw), UseCaseError.Validation.class, "EMAIL_DOMAIN_REQUIRED");
    }

    @Test
    void domainRejectsNull() {
        assertUseCaseError(() -> EmailDomain.parse(null), UseCaseError.Validation.class, "EMAIL_DOMAIN_REQUIRED");
    }

    // ── Enums ──────────────────────────────────────────────────────────────

    @ParameterizedTest(name = "stored ''{0}'' reads as {1}")
    @CsvSource({"ANCHOR,ANCHOR", "PARTNER,PARTNER", "CLIENT,CLIENT"})
    void storedScopeTypeParsesTheThreeRecognisedValues(String stored, ScopeType expected) {
        assertThat(ScopeType.parse(stored)).isEqualTo(expected);
    }

    /// X-06: unknown used to default to `ANCHOR`, the MOST privileged scope —
    /// a privilege-escalation bug on a corrupted column. There is no default now.
    @ParameterizedTest
    @ValueSource(strings = {"GLOBAL", ""})
    void storedScopeTypeRejectsAnythingElseInsteadOfDefaultingToAnchor(String stored) {
        assertThatThrownBy(() -> ScopeType.parse(stored)).isInstanceOf(ScopeType.UnrecognisedScopeTypeException.class);
    }

    @Test
    void storedScopeTypeRejectsNull() {
        assertThatThrownBy(() -> ScopeType.parse(null)).isInstanceOf(ScopeType.UnrecognisedScopeTypeException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GLOBAL", "anchor", "", "Client"})
    void scopeTypeRejectsUnknownWireValues(String wire) {
        assertUseCaseError(() -> ScopeType.parseStrict(wire), UseCaseError.Validation.class, "INVALID_SCOPE_TYPE");
        assertUseCaseError(() -> ScopeType.parseStrict(null), UseCaseError.Validation.class, "INVALID_SCOPE_TYPE");
    }

    @Test
    void partnerAndClientScopesRequireAPrimaryClient() {
        assertThat(ScopeType.ANCHOR.requiresPrimaryClient()).isFalse();
        assertThat(ScopeType.PARTNER.requiresPrimaryClient()).isTrue();
        assertThat(ScopeType.CLIENT.requiresPrimaryClient()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"SMS", "totp", "", "PUSH"})
    void mfaMethodRejectsUnknownWireValues(String raw) {
        assertUseCaseError(() -> MfaMethod.parseStrict(raw), UseCaseError.Validation.class, "INVALID_2FA_METHOD");
        assertUseCaseError(() -> MfaMethod.parseAllStrict(List.of("TOTP", raw)), UseCaseError.Validation.class, "INVALID_2FA_METHOD");
    }

    @Test
    void mfaMethodParsesTheClosedSet() {
        assertThat(MfaMethod.parseAllStrict(List.of("TOTP", "EMAIL_PIN"))).containsExactly(MfaMethod.TOTP, MfaMethod.EMAIL_PIN);
        assertThat(MfaMethod.parseAllStrict(null)).isEmpty();
        assertUseCaseError(() -> MfaMethod.parseStrict(null), UseCaseError.Validation.class, "INVALID_2FA_METHOD");
    }

    @Test
    void mfaMethodReadsStoredValuesLenientlyByDroppingUnknownOnes() {
        assertThat(MfaMethod.readStored(List.of("TOTP", "SMS", "EMAIL_PIN", ""))).containsExactly(MfaMethod.TOTP, MfaMethod.EMAIL_PIN);
        assertThat(MfaMethod.readStored(null)).isEmpty();
    }

    // ── Create ─────────────────────────────────────────────────────────────

    @Test
    void createIsAnEmptyMappingWithTwoFactorOffAndThirtyDayDefault() {
        var m = EmailDomainMapping.create(EmailDomain.parse("Acme.IO"), IDP, ScopeType.ANCHOR);
        assertThat(m.id()).startsWith("edm_");
        assertThat(m.emailDomain()).isEqualTo("acme.io");
        assertThat(m.identityProviderId()).isEqualTo(IDP);
        assertThat(m.scopeType()).isEqualTo(ScopeType.ANCHOR);
        assertThat(m.primaryClientId()).isNull();
        assertThat(m.additionalClientIds()).isEmpty();
        assertThat(m.grantedClientIds()).isEmpty();
        assertThat(m.requiredOidcTenantId()).isNull();
        assertThat(m.twoFactor()).isEqualTo(TwoFactorPolicy.OFF);
        assertThat(m.twoFactor().rememberDeviceDays()).isEqualTo(TwoFactorPolicy.DEFAULT_REMEMBER_DEVICE_DAYS).isEqualTo(30);
        assertThat(m.createdAt()).isEqualTo(m.updatedAt());
    }

    // ── Two-factor policy ──────────────────────────────────────────────────

    @Test
    void requiredTwoFactorNeedsAtLeastOneMethod() {
        var m = EmailDomainMapping.create(EmailDomain.parse("acme.io"), IDP, ScopeType.ANCHOR);
        assertUseCaseError(() -> m.withTwoFactor(new TwoFactorPolicy(true, List.of(), false, 30)),
                UseCaseError.Validation.class, "2FA_METHOD_REQUIRED");

        var on = m.withTwoFactor(new TwoFactorPolicy(true, List.of(MfaMethod.TOTP), false, 30));
        assertThat(on.twoFactor().required()).isTrue();
        assertThat(on.twoFactor().allowedMethods()).containsExactly(MfaMethod.TOTP);
        assertThat(m.twoFactor().required()).as("records are immutable").isFalse();

        var offWithNoMethods = on.withTwoFactor(new TwoFactorPolicy(false, List.of(), false, 30));
        assertThat(offWithNoMethods.twoFactor().allowedMethods()).as("methods may be empty when not required").isEmpty();
    }

    @Test
    void policyRecordIsLenientSoStoredRowsAlwaysRead() {
        var inconsistent = new TwoFactorPolicy(true, List.of(), false, 30);
        assertThat(inconsistent.required()).isTrue();
        assertUseCaseError(inconsistent::checkConsistent, UseCaseError.Validation.class, "2FA_METHOD_REQUIRED");
    }

    // ── Update (spec §1 absent-value rules) ────────────────────────────────

    private static final EmailDomainMapping.Changes NO_CHANGES =
            new EmailDomainMapping.Changes(null, null, null, null, null, null, null, null);

    @Test
    void updateReplacesScalarsWholesaleAndLeavesAbsentListsAndBooleansUntouched() {
        var m = EmailDomainMapping.create(EmailDomain.parse("acme.io"), IDP, ScopeType.CLIENT)
                .withPrimaryClientId("clt_primary")
                .withAdditionalClientIds(List.of("clt_a"))
                .withGrantedClientIds(List.of("clt_g"))
                .withRequiredOidcTenantId("tenant-1")
                .withTwoFactor(new TwoFactorPolicy(true, List.of(MfaMethod.TOTP), true, 7));

        var untouched = m.update(NO_CHANGES);
        assertThat(untouched.primaryClientId()).as("absent primaryClientId clears (spec §1, open question 3)").isNull();
        assertThat(untouched.requiredOidcTenantId()).as("absent requiredOidcTenantId clears").isNull();
        assertThat(untouched.additionalClientIds()).as("absent list unchanged").containsExactly("clt_a");
        assertThat(untouched.grantedClientIds()).containsExactly("clt_g");
        assertThat(untouched.twoFactor()).as("absent 2FA fields unchanged").isEqualTo(new TwoFactorPolicy(true, List.of(MfaMethod.TOTP), true, 7));
        assertThat(untouched.emailDomain()).isEqualTo("acme.io");
        assertThat(untouched.identityProviderId()).isEqualTo(IDP);
        assertThat(untouched.scopeType()).isEqualTo(ScopeType.CLIENT);
        assertThat(untouched.createdAt()).isEqualTo(m.createdAt());
        assertThat(m.primaryClientId()).as("records are immutable").isEqualTo("clt_primary");

        var replaced = m.update(new EmailDomainMapping.Changes("clt_p2", List.of(), List.of("clt_g2"), "tenant-2",
                null, List.of(MfaMethod.EMAIL_PIN), false, 0));
        assertThat(replaced.primaryClientId()).isEqualTo("clt_p2");
        assertThat(replaced.additionalClientIds()).as("empty list clears").isEmpty();
        assertThat(replaced.grantedClientIds()).containsExactly("clt_g2");
        assertThat(replaced.requiredOidcTenantId()).isEqualTo("tenant-2");
        assertThat(replaced.twoFactor().required()).as("null keeps").isTrue();
        assertThat(replaced.twoFactor().allowedMethods()).containsExactly(MfaMethod.EMAIL_PIN);
        assertThat(replaced.twoFactor().rememberDeviceEnabled()).isFalse();
        assertThat(replaced.twoFactor().rememberDeviceDays()).as("any value stored, including 0 (open question 4)").isZero();
    }

    @Test
    void updateRejectsAMergedPolicyThatRequiresTwoFactorWithoutAMethod() {
        var m = EmailDomainMapping.create(EmailDomain.parse("acme.io"), IDP, ScopeType.ANCHOR);
        assertUseCaseError(() -> m.update(new EmailDomainMapping.Changes(null, null, null, null, true, null, null, null)),
                UseCaseError.Validation.class, "2FA_METHOD_REQUIRED");
        var on = m.update(new EmailDomainMapping.Changes(null, null, null, null, true, List.of(MfaMethod.TOTP), null, null));
        assertUseCaseError(() -> on.update(new EmailDomainMapping.Changes(null, null, null, null, null, List.of(), null, null)),
                UseCaseError.Validation.class, "2FA_METHOD_REQUIRED");
        assertThat(on.update(new EmailDomainMapping.Changes(null, null, null, null, false, List.of(), null, null)).twoFactor().allowedMethods())
                .as("methods may be cleared together with require2fa").isEmpty();
    }

    // ── Move ───────────────────────────────────────────────────────────────

    @Test
    void moveRePointsTheProviderAndRefusesTheSameOne() {
        var m = EmailDomainMapping.create(EmailDomain.parse("acme.io"), IDP, ScopeType.ANCHOR);
        var moved = m.moveToProvider("idp_other00000000");
        assertThat(moved.identityProviderId()).isEqualTo("idp_other00000000");
        assertThat(moved.emailDomain()).isEqualTo("acme.io");
        assertThat(m.identityProviderId()).as("records are immutable").isEqualTo(IDP);
        assertUseCaseError(() -> m.moveToProvider(IDP), UseCaseError.Conflict.class, "ALREADY_ON_PROVIDER");
        assertThatThrownBy(() -> m.moveToProvider(IDP))
                .hasMessageContaining("Email domain 'acme.io' is already mapped to that identity provider");
    }

    // ── Copies ─────────────────────────────────────────────────────────────

    @Test
    void copiesReplaceOneFieldAndDefensivelyCopyLists() {
        var m = EmailDomainMapping.create(EmailDomain.parse("acme.io"), IDP, ScopeType.CLIENT)
                .withPrimaryClientId("clt_primary")
                .withAdditionalClientIds(List.of("clt_a", "clt_b"))
                .withGrantedClientIds(List.of("clt_g"))
                .withRequiredOidcTenantId("tenant-1");
        assertThat(m.primaryClientId()).isEqualTo("clt_primary");
        assertThat(m.additionalClientIds()).containsExactly("clt_a", "clt_b");
        assertThat(m.grantedClientIds()).containsExactly("clt_g");
        assertThat(m.requiredOidcTenantId()).isEqualTo("tenant-1");
        assertThat(m.withPrimaryClientId(null).primaryClientId()).isNull();
        assertThat(m.withAdditionalClientIds(null).additionalClientIds()).isEmpty();
    }

    private static void assertUseCaseError(ThrowingCallable call, Class<? extends UseCaseError> kind, String code) {
        assertThatThrownBy(call)
                .isInstanceOf(UseCaseException.class)
                .extracting(t -> ((UseCaseException) t).error())
                .satisfies(err -> {
                    assertThat(err).as("error kind").isInstanceOf(kind);
                    assertThat(err.code()).as("error code").isEqualTo(code);
                });
    }
}
