package io.flowcatalyst.platform.identityprovider;

import io.flowcatalyst.sdk.usecase.UseCaseError;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The aggregate's pure rules (spec §1–2): the lenient type reader, the
/// create defaults, the `update(Changes)` semantics and the delete guard —
/// no database involved.
class IdentityProviderTest {

    private static IdentityProvider oidc() {
        return IdentityProvider.create("entra", "Entra", IdentityProviderType.OIDC)
                .withOidc("https://login.example.com", "client-1", "encrypted:AAAA", true, null);
    }

    // ── Type: stored is strict (X-06), wire (create command) stays lenient ──

    @ParameterizedTest
    @CsvSource({"OIDC, OIDC", "INTERNAL, INTERNAL"})
    void storedTypeParsesTheTwoRecognisedValues(String stored, IdentityProviderType expected) {
        assertThat(IdentityProviderType.parse(stored)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"oidc", "anything", ""})
    void storedTypeRejectsAnythingElseInsteadOfDefaultingToInternal(String stored) {
        assertThatThrownBy(() -> IdentityProviderType.parse(stored))
                .isInstanceOf(IdentityProviderType.UnrecognisedIdentityProviderTypeException.class);
    }

    @Test
    void storedTypeRejectsNull() {
        assertThatThrownBy(() -> IdentityProviderType.parse(null))
                .isInstanceOf(IdentityProviderType.UnrecognisedIdentityProviderTypeException.class);
    }

    @ParameterizedTest
    @CsvSource({"OIDC, OIDC", "INTERNAL, INTERNAL"})
    void wireTypeReadsTheTwoExactSpellings(String given, IdentityProviderType expected) {
        assertThat(IdentityProviderType.parseWire(given)).isEqualTo(expected);
    }

    /// Owner ruling 2026-09-06 #19 (X-06 at the wire): anything else is a 400, never a silent INTERNAL.
    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {"oidc", "internal", "anything", "''", "null"})
    void wireTypeRejectsEverythingElse(String given) {
        assertUseCaseError(() -> IdentityProviderType.parseWire(given), UseCaseError.Validation.class, "INVALID_TYPE");
    }

    // ── Create ─────────────────────────────────────────────────────────────

    @Test
    void createStartsWithNoOidcSettingsNoDomainsAndNoRoleRestriction() {
        var ip = IdentityProvider.create("  entra ", "  Entra  ", IdentityProviderType.OIDC);
        assertThat(ip.id()).startsWith("idp_");
        assertThat(ip.code()).as("code is stored verbatim (spec §1, open question 1)").isEqualTo("  entra ");
        assertThat(ip.name()).as("name is stored verbatim on create").isEqualTo("  Entra  ");
        assertThat(ip.type()).isEqualTo(IdentityProviderType.OIDC);
        assertThat(ip.oidcIssuerUrl()).isNull();
        assertThat(ip.oidcClientId()).isNull();
        assertThat(ip.hasClientSecret()).isFalse();
        assertThat(ip.oidcMultiTenant()).isFalse();
        assertThat(ip.oidcIssuerPattern()).isNull();
        assertThat(ip.allowedEmailDomains()).isEmpty();
        assertThat(ip.syncRolesFromIdp()).isFalse();
        assertThat(ip.allowedRoleIds()).isEmpty();
        assertThat(ip.createdAt()).isEqualTo(ip.updatedAt());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void blankOptionalStringsAreAbsentInsideTheAggregate(String blank) {
        var ip = IdentityProvider.create("x", "X", IdentityProviderType.OIDC).withOidc(blank, blank, blank, false, blank);
        assertThat(ip.oidcIssuerUrl()).isNull();
        assertThat(ip.oidcClientId()).isNull();
        assertThat(ip.oidcClientSecretRef()).isNull();
        assertThat(ip.hasClientSecret()).as("an empty secret is no secret (spec §1, open question 3)").isFalse();
        assertThat(ip.oidcIssuerPattern()).isNull();
    }

    @Test
    void roleSyncSettingsTakeANullListAsNoRestriction() {
        var restricted = IdentityProvider.create("x", "X", IdentityProviderType.OIDC).withRoleSync(true, List.of("rol_a", "rol_b"));
        assertThat(restricted.syncRolesFromIdp()).isTrue();
        assertThat(restricted.allowedRoleIds()).containsExactly("rol_a", "rol_b");
        assertThat(IdentityProvider.create("x", "X", IdentityProviderType.OIDC).withRoleSync(true, null).allowedRoleIds()).isEmpty();
    }

    @Test
    void seededInternalIsByCodeAndInternalTypeIsByType() {
        var seeded = IdentityProvider.create(IdentityProvider.INTERNAL_CODE, "Internal", IdentityProviderType.INTERNAL);
        assertThat(seeded.isSeededInternal()).isTrue();
        assertThat(seeded.isInternalType()).isTrue();
        var otherInternal = IdentityProvider.create("ldap-ish", "Other", IdentityProviderType.INTERNAL);
        assertThat(otherInternal.isSeededInternal()).isFalse();
        assertThat(otherInternal.isInternalType()).isTrue();
        assertThat(oidc().isSeededInternal()).isFalse();
        assertThat(oidc().isInternalType()).isFalse();
    }

    // ── Update ─────────────────────────────────────────────────────────────

    @Test
    void updateWithAllNullChangesTouchesNothingButUpdatedAt() {
        var before = oidc().withRoleSync(true, List.of("rol_a"));
        var after = before.update(new IdentityProvider.Changes(null, null, null, null, null, null, null, null));
        assertThat(after).usingRecursiveComparison().ignoringFields("updatedAt").isEqualTo(before);
        assertThat(after.updatedAt()).isAfterOrEqualTo(before.updatedAt());
    }

    @Test
    void updateAppliesTheNonNullFieldsTrimsTheNameAndKeepsCodeAndType() {
        var after = oidc().update(new IdentityProvider.Changes("  Renamed  ", "https://new.example.com", "client-2",
                "encrypted:BBBB", false, "^https://.*$", true, List.of("rol_z")));
        assertThat(after.name()).isEqualTo("Renamed");
        assertThat(after.code()).isEqualTo("entra");
        assertThat(after.type()).isEqualTo(IdentityProviderType.OIDC);
        assertThat(after.oidcIssuerUrl()).isEqualTo("https://new.example.com");
        assertThat(after.oidcClientId()).isEqualTo("client-2");
        assertThat(after.oidcClientSecretRef()).isEqualTo("encrypted:BBBB");
        assertThat(after.oidcMultiTenant()).isFalse();
        assertThat(after.oidcIssuerPattern()).isEqualTo("^https://.*$");
        assertThat(after.syncRolesFromIdp()).isTrue();
        assertThat(after.allowedRoleIds()).containsExactly("rol_z");
    }

    @Test
    void updateWithABlankStringClearsTheFieldAndAnEmptyRoleListClearsTheRestriction() {
        var before = oidc().withRoleSync(true, List.of("rol_a"));
        var after = before.update(new IdentityProvider.Changes(null, "", " ", "", null, "", null, List.of()));
        assertThat(after.oidcIssuerUrl()).isNull();
        assertThat(after.oidcClientId()).isNull();
        assertThat(after.hasClientSecret()).as("\"\" on the secret removes it (spec §5)").isFalse();
        assertThat(after.oidcIssuerPattern()).isNull();
        assertThat(after.allowedRoleIds()).isEmpty();
        assertThat(after.syncRolesFromIdp()).as("untouched").isTrue();
    }

    @Test
    void changesCopiesItsRoleListDefensively() {
        var roles = new ArrayList<>(List.of("rol_a"));
        var changes = new IdentityProvider.Changes(null, null, null, null, null, null, null, roles);
        roles.add("rol_b");
        assertThat(changes.allowedRoleIds()).containsExactly("rol_a");
    }

    // ── Delete guard ───────────────────────────────────────────────────────

    @Test
    void requireDeletableRefusesTheSeededInternalProvider() {
        var seeded = IdentityProvider.create(IdentityProvider.INTERNAL_CODE, "Internal", IdentityProviderType.INTERNAL);
        assertUseCaseError(seeded::requireDeletable, UseCaseError.BusinessRule.class, "INTERNAL_IDP_PROTECTED");
        assertThatThrownBy(seeded::requireDeletable).hasMessageContaining("The internal identity provider cannot be deleted");
    }

    @Test
    void requireDeletableRefusesWhileDomainsStillRouteHereNamingThem() {
        var mapped = new IdentityProvider("idp_x", "entra", "Entra", IdentityProviderType.OIDC, null, null, null, false, null,
                List.of("a.example.com", "b.example.com"), false, List.of(), List.of(), Instant.now(), Instant.now());
        assertUseCaseError(mapped::requireDeletable, UseCaseError.Conflict.class, "DOMAINS_STILL_MAPPED");
        assertThatThrownBy(mapped::requireDeletable)
                .hasMessageContaining("Identity provider still routes email domains (a.example.com, b.example.com); move or delete those mappings first");
    }

    @Test
    void requireDeletableReturnsTheProviderWhenNothingRoutesToIt() {
        var ip = oidc();
        assertThat(ip.requireDeletable()).isSameAs(ip);
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
