package io.flowcatalyst.platform.authadmin;

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

/// The three aggregates' pure rules (spec §1–2, §4): domain normalisation
/// for anchor domains and auth configs, the strict `configType` /
/// `authProvider` readers, the login-routing matcher, and the update's
/// absent-value semantics — no database involved.
class AuthAdminConfigTest {

    // ── AnchorDomainValue (spec §4.1) ────────────────────────────────────────

    @ParameterizedTest(name = "[{0}] \"{1}\" → \"{2}\"")
    @CsvSource({
            "case,   Example.COM,                example.com",
            "trim,   '  example.com  ',           example.com",
            "shape,  a.b,                         a.b",
            "shape,  sub.domain.example.co.uk,    sub.domain.example.co.uk"})
    void parseNormalisesCaseAndWhitespace(String rule, String raw, String expected) {
        assertThat(AnchorDomainValue.parse(raw)).as(rule).isEqualTo(new AnchorDomainValue(expected));
    }

    @ParameterizedTest(name = "[{0}] \"{1}\" is rejected")
    @CsvSource({
            "dot,    nodot",
            "space,  'exam ple.com'",
            "slash,  example.com/path",
            "at,     user@example.com"})
    void parseRejectsMissingDotOrBannedCharacters(String rule, String raw) {
        assertUseCaseError(() -> AnchorDomainValue.parse(raw), UseCaseError.Validation.class, "INVALID_DOMAIN");
        assertThatThrownBy(() -> AnchorDomainValue.parse(raw)).as(rule).hasMessageContaining(AnchorDomainValue.FORMAT_MESSAGE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void parseFoldsBlankIntoInvalidDomain(String raw) {
        assertUseCaseError(() -> AnchorDomainValue.parse(raw), UseCaseError.Validation.class, "INVALID_DOMAIN");
    }

    @Test
    void parseRejectsNullAsInvalidDomain() {
        assertUseCaseError(() -> AnchorDomainValue.parse(null), UseCaseError.Validation.class, "INVALID_DOMAIN");
    }

    // ── AnchorDomain ─────────────────────────────────────────────────────────

    @Test
    void createGeneratesAPrefixedIdAndStampsCreatedAtEqualToUpdatedAt() {
        var a = AnchorDomain.create(AnchorDomainValue.parse("Acme.IO"));
        assertThat(a.id()).startsWith("anc_");
        assertThat(a.domain()).isEqualTo("acme.io");
        assertThat(a.createdAt()).isEqualTo(a.updatedAt());
    }

    @Test
    void changeDomainReplacesTheDomainAndBumpsUpdatedAtButKeepsIdAndCreatedAt() {
        var a = AnchorDomain.create(AnchorDomainValue.parse("acme.io"));
        var changed = a.changeDomain(AnchorDomainValue.parse("other.io"));
        assertThat(changed.id()).isEqualTo(a.id());
        assertThat(changed.domain()).isEqualTo("other.io");
        assertThat(changed.createdAt()).isEqualTo(a.createdAt());
        assertThat(a.domain()).as("records are immutable").isEqualTo("acme.io");
    }

    @ParameterizedTest(name = "[{0}] \"{1}\" against domain \"{2}\" → {3}")
    @CsvSource({
            "match,        user@acme.io,        acme.io,   true",
            "case,         user@ACME.IO,        acme.io,   true",
            "subdomain,    user@sub.acme.io,    acme.io,   false",
            "suffix-only,  user@notacme.io,     acme.io,   false",
            "other-domain, user@other.io,       acme.io,   false",
            "no-at,        acme.io,             acme.io,   false"})
    void matchesEmailComparesTheLowerCasedSuffix(String rule, String email, String domain, boolean expected) {
        var a = AnchorDomain.create(AnchorDomainValue.parse(domain));
        assertThat(a.matchesEmail(email)).as(rule).isEqualTo(expected);
    }

    @Test
    void matchesEmailRejectsNull() {
        var a = AnchorDomain.create(AnchorDomainValue.parse("acme.io"));
        assertThat(a.matchesEmail(null)).isFalse();
    }

    // ── ClientAuthConfigEmailDomain (spec §4.2) ──────────────────────────────

    @ParameterizedTest(name = "[{0}] \"{1}\" → \"{2}\"")
    @CsvSource({
            "case,   Acme.COM,       acme.com",
            "trim,   '  acme.com  ', acme.com"})
    void emailDomainNormalisesCaseAndWhitespace(String rule, String raw, String expected) {
        assertThat(ClientAuthConfigEmailDomain.parse(raw)).as(rule).isEqualTo(new ClientAuthConfigEmailDomain(expected));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "nodot"})
    void emailDomainRejectsBlankOrMissingDotWithOneCode(String raw) {
        assertUseCaseError(() -> ClientAuthConfigEmailDomain.parse(raw), UseCaseError.Validation.class, "INVALID_EMAIL_DOMAIN");
    }

    @Test
    void emailDomainAllowsCharactersAnchorDomainWouldBan() {
        // Unlike AnchorDomainValue, ClientAuthConfigEmailDomain's only rule is "contains a dot" (spec §4.2).
        assertThat(ClientAuthConfigEmailDomain.parse("a b.com").value()).isEqualTo("a b.com");
    }

    // ── ConfigType / AuthProvider (spec §2, §4.2, X-06) ──────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"ANCHOR", "PARTNER", "CLIENT"})
    void configTypeParsesTheClosedSet(String s) {
        assertThat(ConfigType.parse(s)).isEqualTo(ConfigType.valueOf(s));
        assertThat(ConfigType.parseStrict(s)).isEqualTo(ConfigType.valueOf(s));
    }

    @ParameterizedTest
    @ValueSource(strings = {"anchor", "GLOBAL", ""})
    void configTypeParseThrowsTheBareInternalExceptionForCorruptStoredValues(String s) {
        assertThatThrownBy(() -> ConfigType.parse(s)).isInstanceOf(ConfigType.UnrecognisedConfigTypeException.class);
    }

    @Test
    void configTypeParseRejectsNullAsTheBareInternalException() {
        assertThatThrownBy(() -> ConfigType.parse(null)).isInstanceOf(ConfigType.UnrecognisedConfigTypeException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"anchor", "GLOBAL", ""})
    void configTypeParseStrictTurnsTheSameFailureIntoAValidationError(String s) {
        assertUseCaseError(() -> ConfigType.parseStrict(s), UseCaseError.Validation.class, "INVALID_CONFIG_TYPE");
    }

    @ParameterizedTest
    @ValueSource(strings = {"INTERNAL", "OIDC"})
    void authProviderParsesTheClosedSet(String s) {
        assertThat(AuthProvider.parse(s)).isEqualTo(AuthProvider.valueOf(s));
        assertThat(AuthProvider.parseStrict(s)).isEqualTo(AuthProvider.valueOf(s));
    }

    @ParameterizedTest
    @ValueSource(strings = {"internal", "SAML", ""})
    void authProviderParseThrowsTheBareInternalExceptionForCorruptStoredValues(String s) {
        assertThatThrownBy(() -> AuthProvider.parse(s)).isInstanceOf(AuthProvider.UnrecognisedAuthProviderException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"internal", "SAML", ""})
    void authProviderParseStrictTurnsTheSameFailureIntoAValidationError(String s) {
        assertUseCaseError(() -> AuthProvider.parseStrict(s), UseCaseError.Validation.class, "INVALID_AUTH_PROVIDER");
    }

    // ── ClientAuthConfig ─────────────────────────────────────────────────────

    @Test
    void createIsAnEmptyConfigWithNoGrantsAndNoOidcSettings() {
        var c = ClientAuthConfig.create(ClientAuthConfigEmailDomain.parse("Acme.COM"), ConfigType.ANCHOR, AuthProvider.INTERNAL);
        assertThat(c.id()).startsWith("cac_");
        assertThat(c.emailDomain()).isEqualTo("acme.com");
        assertThat(c.configType()).isEqualTo(ConfigType.ANCHOR);
        assertThat(c.authProvider()).isEqualTo(AuthProvider.INTERNAL);
        assertThat(c.primaryClientId()).isNull();
        assertThat(c.additionalClientIds()).isEmpty();
        assertThat(c.grantedClientIds()).isEmpty();
        assertThat(c.oidcIssuerUrl()).isNull();
        assertThat(c.oidcClientId()).isNull();
        assertThat(c.oidcMultiTenant()).isFalse();
        assertThat(c.oidcIssuerPattern()).isNull();
        assertThat(c.oidcClientSecretRef()).isNull();
        assertThat(c.createdAt()).isEqualTo(c.updatedAt());
    }

    @Test
    void copiesReplaceOneFieldAndDefensivelyCopyLists() {
        var c = ClientAuthConfig.create(ClientAuthConfigEmailDomain.parse("acme.com"), ConfigType.CLIENT, AuthProvider.OIDC)
                .withPrimaryClientId("clt_p")
                .withAdditionalClientIds(List.of("clt_a"))
                .withGrantedClientIds(List.of("clt_g"))
                .withOidcIssuerUrl("https://issuer")
                .withOidcClientId("oidc-client")
                .withOidcMultiTenant(true)
                .withOidcIssuerPattern("https://*.issuer")
                .withOidcClientSecretRef("secret-ref-1");
        assertThat(c.primaryClientId()).isEqualTo("clt_p");
        assertThat(c.additionalClientIds()).containsExactly("clt_a");
        assertThat(c.grantedClientIds()).containsExactly("clt_g");
        assertThat(c.oidcIssuerUrl()).isEqualTo("https://issuer");
        assertThat(c.oidcClientId()).isEqualTo("oidc-client");
        assertThat(c.oidcMultiTenant()).isTrue();
        assertThat(c.oidcIssuerPattern()).isEqualTo("https://*.issuer");
        assertThat(c.oidcClientSecretRef()).isEqualTo("secret-ref-1");
        assertThat(c.withPrimaryClientId(null).primaryClientId()).isNull();
        assertThat(c.withAdditionalClientIds(null).additionalClientIds()).isEmpty();
    }

    private static final ClientAuthConfig.Changes NO_CHANGES =
            new ClientAuthConfig.Changes(null, null, null, null, null, null, null, null, null);

    @Test
    void updateAppliesOnlySuppliedFieldsAndLeavesTheRestUntouched() {
        var c = ClientAuthConfig.create(ClientAuthConfigEmailDomain.parse("acme.com"), ConfigType.CLIENT, AuthProvider.INTERNAL)
                .withPrimaryClientId("clt_p")
                .withAdditionalClientIds(List.of("clt_a"))
                .withGrantedClientIds(List.of("clt_g"))
                .withOidcIssuerUrl("https://issuer")
                .withOidcClientId("oidc-client")
                .withOidcMultiTenant(true)
                .withOidcIssuerPattern("pattern")
                .withOidcClientSecretRef("ref-1");

        var untouched = c.update(NO_CHANGES);
        assertThat(untouched.primaryClientId()).as("null = untouched (spec §4.2)").isEqualTo("clt_p");
        assertThat(untouched.additionalClientIds()).containsExactly("clt_a");
        assertThat(untouched.grantedClientIds()).containsExactly("clt_g");
        assertThat(untouched.authProvider()).isEqualTo(AuthProvider.INTERNAL);
        assertThat(untouched.oidcIssuerUrl()).isEqualTo("https://issuer");
        assertThat(untouched.oidcClientId()).isEqualTo("oidc-client");
        assertThat(untouched.oidcMultiTenant()).isTrue();
        assertThat(untouched.oidcIssuerPattern()).isEqualTo("pattern");
        assertThat(untouched.oidcClientSecretRef()).isEqualTo("ref-1");
        assertThat(untouched.emailDomain()).as("not updatable").isEqualTo("acme.com");
        assertThat(untouched.configType()).as("not updatable").isEqualTo(ConfigType.CLIENT);
        assertThat(untouched.createdAt()).isEqualTo(c.createdAt());
        assertThat(c.primaryClientId()).as("records are immutable").isEqualTo("clt_p");

        var replaced = c.update(new ClientAuthConfig.Changes("clt_p2", List.of(), List.of("clt_g2"),
                AuthProvider.OIDC, "https://issuer2", "client2", false, "pattern2", "ref-2"));
        assertThat(replaced.primaryClientId()).isEqualTo("clt_p2");
        assertThat(replaced.additionalClientIds()).as("empty list clears").isEmpty();
        assertThat(replaced.grantedClientIds()).containsExactly("clt_g2");
        assertThat(replaced.authProvider()).isEqualTo(AuthProvider.OIDC);
        assertThat(replaced.oidcIssuerUrl()).isEqualTo("https://issuer2");
        assertThat(replaced.oidcClientId()).isEqualTo("client2");
        assertThat(replaced.oidcMultiTenant()).isFalse();
        assertThat(replaced.oidcIssuerPattern()).isEqualTo("pattern2");
        assertThat(replaced.oidcClientSecretRef()).isEqualTo("ref-2");
    }

    @Test
    void updateNeverAppliesAnOidcCompletenessCheck() {
        // Spec §8 D3: kept as Go — an update may leave authProvider=OIDC with no issuer/client id.
        var c = ClientAuthConfig.create(ClientAuthConfigEmailDomain.parse("acme.com"), ConfigType.ANCHOR, AuthProvider.INTERNAL);
        var updated = c.update(new ClientAuthConfig.Changes(null, null, null, AuthProvider.OIDC, null, null, null, null, null));
        assertThat(updated.authProvider()).isEqualTo(AuthProvider.OIDC);
        assertThat(updated.oidcIssuerUrl()).isNull();
        assertThat(updated.oidcClientId()).isNull();
    }

    // ── IdpRoleMapping ───────────────────────────────────────────────────────

    @Test
    void createStoresFieldsVerbatimWithNoNormalisation() {
        var m = IdpRoleMapping.create("Keycloak", "  Some-Role  ", "app:Role");
        assertThat(m.id()).startsWith("irm_");
        assertThat(m.idpType()).isEqualTo("Keycloak");
        assertThat(m.idpRoleName()).isEqualTo("  Some-Role  ");
        assertThat(m.platformRoleName()).isEqualTo("app:Role");
        assertThat(m.createdAt()).isEqualTo(m.updatedAt());
    }

    @Test
    void createAllowsANullIdpType() {
        // The entity itself does not enforce non-blank idpType; that is the create operation's job (spec §4.3).
        var m = IdpRoleMapping.create(null, "role", "app:role");
        assertThat(m.idpType()).isNull();
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
