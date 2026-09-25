package io.flowcatalyst.platform.identityprovider;

import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.sdk.usecase.HasId;
import io.flowcatalyst.sdk.usecase.UseCaseException;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/// The identity provider aggregate root (spec: `docs/spec/identityprovider.md`).
/// An IdP is how a person proves who they are — the seeded internal
/// password provider or an external OIDC provider. It is deliberately not
/// bound to any client or plane (spec §1): which email domains route to it
/// is the email-domain mapping table's business, so `allowedEmailDomains`
/// is **derived** on read and never persisted by this aggregate. IdPs are
/// anchor-only platform configuration with no per-resource scope.
///
/// Immutable record: each transition returns a copy and throws
/// [UseCaseException] when an invariant is violated. Optional strings are
/// `null` when absent — a blank incoming value clears the field (spec §1).
/// The repository persists whatever copy it is handed and stamps
/// `updatedAt` itself.
///
/// @param id                  `idp_…` TSID
/// @param code                unique, immutable; verbatim as created
/// @param name                display name
/// @param type                `INTERNAL` | `OIDC`
/// @param oidcIssuerUrl       OIDC issuer, `null` when none
/// @param oidcClientId        OIDC client id, `null` when none
/// @param oidcClientSecretRef the **at-rest** secret ref (spec §5), `null` when no secret; never plaintext
/// @param oidcMultiTenant     whether the issuer is a multi-tenant pattern
/// @param oidcIssuerPattern   regex the multi-tenant issuer must match, `null` when none
/// @param allowedEmailDomains domains currently routed here — derived from the mapping table on read (spec §1)
/// @param syncRolesFromIdp    whether logins reconcile the user's `IDP_SYNC` roles from the token's `roles` claim
/// @param allowedRoleIds      roles this IdP may confer via role sync; empty = no restriction
/// @param allowedTenantIds    for a multi-tenant OIDC provider, the Entra tenants (`tid`) it accepts for
///                            every mapping without its own pin and for provider-direct logins; empty =
///                            none pinned at the provider level (owner ruling 2026-09-25, backlog item 3)
/// @param createdAt           creation time
/// @param updatedAt           last change
public record IdentityProvider(
        String id,
        String code,
        String name,
        IdentityProviderType type,
        String oidcIssuerUrl,
        String oidcClientId,
        String oidcClientSecretRef,
        boolean oidcMultiTenant,
        String oidcIssuerPattern,
        List<String> allowedEmailDomains,
        boolean syncRolesFromIdp,
        List<String> allowedRoleIds,
        List<String> allowedTenantIds,
        Instant createdAt,
        Instant updatedAt) implements HasId {

    /// The seeded password provider's code (`seeder.md`): the fallback target
    /// for domain releases and the anchor of password auth.
    public static final String INTERNAL_CODE = "internal";

    public IdentityProvider {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        oidcIssuerUrl = blankToNull(oidcIssuerUrl);
        oidcClientId = blankToNull(oidcClientId);
        oidcClientSecretRef = blankToNull(oidcClientSecretRef);
        oidcIssuerPattern = blankToNull(oidcIssuerPattern);
        allowedEmailDomains = allowedEmailDomains == null ? List.of() : List.copyOf(allowedEmailDomains);
        allowedRoleIds = allowedRoleIds == null ? List.of() : List.copyOf(allowedRoleIds);
        allowedTenantIds = allowedTenantIds == null ? List.of()
                : allowedTenantIds.stream().filter(t -> t != null && !t.isBlank()).map(String::strip).distinct().toList();
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    /// A fresh provider with no OIDC settings, no domains, no role
    /// restriction and role sync off. `code` and `name` are stored verbatim
    /// (spec §1, open question 1).
    public static IdentityProvider create(String code, String name, IdentityProviderType type) {
        Instant now = Instant.now();
        return new IdentityProvider(EntityType.IDENTITY_PROVIDER.generate(), code, name, type,
                null, null, null, false, null, List.of(), false, List.of(), List.of(), now, now);
    }

    /// Whether a secret ref is configured (the wire's `hasClientSecret`).
    public boolean hasClientSecret() {
        return oidcClientSecretRef != null;
    }

    /// Whether this is the seeded internal provider (by **code**, spec §1) —
    /// the one that cannot be deleted and that released domains fall back to.
    public boolean isSeededInternal() {
        return INTERNAL_CODE.equals(code);
    }

    /// Whether this provider authenticates with passwords (by **type**) —
    /// the direction of a mapping move (spec §4).
    public boolean isInternalType() {
        return type == IdentityProviderType.INTERNAL;
    }

    // ── Construction-time settings (create) ────────────────────────────────

    /// The OIDC settings as supplied at create; blanks clear.
    public IdentityProvider withOidc(String issuerUrl, String clientId, String clientSecretRef, boolean multiTenant,
                                     String issuerPattern) {
        return new IdentityProvider(id, code, name, type, issuerUrl, clientId, clientSecretRef, multiTenant,
                issuerPattern, allowedEmailDomains, syncRolesFromIdp, allowedRoleIds, allowedTenantIds, createdAt, updatedAt);
    }

    /// The role-sync settings as supplied at create; a `null` list means no restriction.
    public IdentityProvider withRoleSync(boolean sync, List<String> roleIds) {
        return new IdentityProvider(id, code, name, type, oidcIssuerUrl, oidcClientId, oidcClientSecretRef,
                oidcMultiTenant, oidcIssuerPattern, allowedEmailDomains, sync, roleIds, allowedTenantIds, createdAt, updatedAt);
    }

    /// The provider-level tenant pin as supplied at create; `null` means none.
    public IdentityProvider withAllowedTenants(List<String> tenantIds) {
        return new IdentityProvider(id, code, name, type, oidcIssuerUrl, oidcClientId, oidcClientSecretRef,
                oidcMultiTenant, oidcIssuerPattern, allowedEmailDomains, syncRolesFromIdp, allowedRoleIds, tenantIds,
                createdAt, updatedAt);
    }

    /// Whether a login through a mapping with pin `mappingPin` (may be blank)
    /// has a tenant to check against: always for a single-tenant provider,
    /// whose issuer is the pin; for a multi-tenant one, only when the mapping
    /// or the provider names tenants.
    public boolean tenantPinned(String mappingPin) {
        return !oidcMultiTenant || (mappingPin != null && !mappingPin.isBlank()) || !allowedTenantIds.isEmpty();
    }

    // ── Transitions (spec §2, §4) ──────────────────────────────────────────

    /// Applies the non-null fields of `changes` and re-stamps `updatedAt`.
    /// `code` and `type` are immutable; `name` is trimmed; a blank string
    /// clears its field; `allowedRoleIds` is replaced wholesale (`[]` clears).
    public IdentityProvider update(Changes changes) {
        return new IdentityProvider(id, code,
                changes.name() == null ? name : changes.name().trim(),
                type,
                changes.oidcIssuerUrl() == null ? oidcIssuerUrl : changes.oidcIssuerUrl(),
                changes.oidcClientId() == null ? oidcClientId : changes.oidcClientId(),
                changes.oidcClientSecretRef() == null ? oidcClientSecretRef : changes.oidcClientSecretRef(),
                changes.oidcMultiTenant() == null ? oidcMultiTenant : changes.oidcMultiTenant(),
                changes.oidcIssuerPattern() == null ? oidcIssuerPattern : changes.oidcIssuerPattern(),
                allowedEmailDomains,
                changes.syncRolesFromIdp() == null ? syncRolesFromIdp : changes.syncRolesFromIdp(),
                changes.allowedRoleIds() == null ? allowedRoleIds : changes.allowedRoleIds(),
                changes.allowedTenantIds() == null ? allowedTenantIds : changes.allowedTenantIds(),
                createdAt, Instant.now());
    }

    /// The fields an admin update may replace; `null` = leave untouched. A
    /// blank string clears the field (`""` on `oidcClientSecretRef` removes
    /// the secret); `allowedRoleIds = []` clears the restriction, `null`
    /// keeps it (spec §4).
    public record Changes(String name, String oidcIssuerUrl, String oidcClientId, String oidcClientSecretRef,
                          Boolean oidcMultiTenant, String oidcIssuerPattern, Boolean syncRolesFromIdp,
                          List<String> allowedRoleIds, List<String> allowedTenantIds) {
        public Changes {
            allowedRoleIds = allowedRoleIds == null ? null : List.copyOf(allowedRoleIds);
            allowedTenantIds = allowedTenantIds == null ? null : List.copyOf(allowedTenantIds);
        }

        /// Without a tenant change, as every caller before item 3 built it.
        public Changes(String name, String oidcIssuerUrl, String oidcClientId, String oidcClientSecretRef,
                       Boolean oidcMultiTenant, String oidcIssuerPattern, Boolean syncRolesFromIdp,
                       List<String> allowedRoleIds) {
            this(name, oidcIssuerUrl, oidcClientId, oidcClientSecretRef, oidcMultiTenant, oidcIssuerPattern,
                    syncRolesFromIdp, allowedRoleIds, null);
        }
    }

    /// The provider itself, if it may be deleted (spec §2): not the seeded
    /// internal provider, and no email domain still routes to it.
    ///
    /// @throws UseCaseException business rule `INTERNAL_IDP_PROTECTED`, conflict `DOMAINS_STILL_MAPPED`
    public IdentityProvider requireDeletable() {
        if (isSeededInternal()) {
            throw UseCaseException.businessRule("INTERNAL_IDP_PROTECTED",
                    "The internal identity provider cannot be deleted");
        }
        if (!allowedEmailDomains.isEmpty()) {
            throw UseCaseException.conflict("DOMAINS_STILL_MAPPED",
                    "Identity provider still routes email domains (" + String.join(", ", allowedEmailDomains)
                            + "); move or delete those mappings first");
        }
        return this;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
