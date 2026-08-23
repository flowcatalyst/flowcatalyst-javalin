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

    // ── EmailDomain ────────────────────────────────────────────────────────

    @Test
    void domainIsTrimmedAndLowerCased() {
        assertThat(EmailDomain.parse("  Example.COM  ")).isEqualTo(new EmailDomain("example.com"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void domainRejectsBlank(String raw) {
        assertUseCaseError(() -> EmailDomain.parse(raw), UseCaseError.Validation.class, "EMAIL_DOMAIN_REQUIRED");
        assertUseCaseError(() -> EmailDomain.parse(null), UseCaseError.Validation.class, "EMAIL_DOMAIN_REQUIRED");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "nodot",
            "user@example.com",
            "exam ple.com",
            "example.com/path"})
    void domainRejectsNonDnsShapes(String raw) {
        assertUseCaseError(() -> EmailDomain.parse(raw), UseCaseError.Validation.class, "INVALID_EMAIL_DOMAIN");
        assertThatThrownBy(() -> EmailDomain.parse(raw)).hasMessageContaining(EmailDomain.FORMAT_MESSAGE);
    }

    // ── Enums ──────────────────────────────────────────────────────────────

    @ParameterizedTest(name = "stored ''{0}'' reads as {1}")
    @CsvSource({"ANCHOR,ANCHOR", "PARTNER,PARTNER", "CLIENT,CLIENT", "GLOBAL,ANCHOR", "'',ANCHOR"})
    void scopeTypeReadsStoredValuesLeniently(String stored, ScopeType expected) {
        assertThat(ScopeType.parse(stored)).isEqualTo(expected);
        assertThat(ScopeType.parse(null)).isEqualTo(ScopeType.ANCHOR);
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
    void mfaMethodRejectsUnknownValues(String raw) {
        assertUseCaseError(() -> MfaMethod.parse(raw), UseCaseError.Validation.class, "INVALID_2FA_METHOD");
        assertUseCaseError(() -> MfaMethod.parseAll(List.of("TOTP", raw)), UseCaseError.Validation.class, "INVALID_2FA_METHOD");
    }

    @Test
    void mfaMethodParsesTheClosedSet() {
        assertThat(MfaMethod.parseAll(List.of("TOTP", "EMAIL_PIN"))).containsExactly(MfaMethod.TOTP, MfaMethod.EMAIL_PIN);
        assertThat(MfaMethod.parseAll(null)).isEmpty();
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
        assertUseCaseError(() -> m.withTwoFactor(TwoFactorPolicy.OFF.withRequired(true)),
                UseCaseError.Validation.class, "2FA_METHOD_REQUIRED");

        var on = m.withTwoFactor(TwoFactorPolicy.OFF.withRequired(true).withAllowedMethods(List.of(MfaMethod.TOTP)));
        assertThat(on.twoFactor().required()).isTrue();
        assertThat(on.twoFactor().allowedMethods()).containsExactly(MfaMethod.TOTP);
        assertThat(m.twoFactor().required()).as("records are immutable").isFalse();

        var offWithNoMethods = on.withTwoFactor(on.twoFactor().withRequired(false).withAllowedMethods(List.of()));
        assertThat(offWithNoMethods.twoFactor().allowedMethods()).as("methods may be empty when not required").isEmpty();
    }

    @Test
    void policyRecordIsLenientSoStoredRowsAlwaysRead() {
        var inconsistent = new TwoFactorPolicy(true, List.of(), false, 30);
        assertThat(inconsistent.required()).isTrue();
        assertUseCaseError(inconsistent::checkConsistent, UseCaseError.Validation.class, "2FA_METHOD_REQUIRED");
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
