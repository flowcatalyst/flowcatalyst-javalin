package io.flowcatalyst.platform.emaildomainmapping;

import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.sdk.usecase.HasId;
import io.flowcatalyst.sdk.usecase.UseCaseException;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/// The email-domain mapping aggregate root (spec: `docs/spec/emaildomainmapping.md`).
/// A mapping routes every user of one email domain to one identity provider
/// and carries the client grants and second-factor policy applied to users
/// who sign up through that domain. Mappings are anchor-only platform
/// configuration with no per-resource scope.
///
/// Immutable record: each transition returns a copy and throws
/// [UseCaseException] when an invariant is violated, so an operation is just
/// load → transition → event. The repository persists whatever copy it is
/// handed and stamps `updatedAt` itself. Role-sync configuration lives on
/// the identity provider, not here (spec §1).
///
/// @param id                   `edm_…` TSID
/// @param emailDomain          normalised (trimmed, lower-cased) domain, unique, immutable
/// @param identityProviderId   the provider the domain authenticates with; changed only by [#moveToProvider]
/// @param scopeType            `ANCHOR` | `PARTNER` | `CLIENT`, immutable
/// @param primaryClientId      optional primary client (required for `PARTNER`/`CLIENT` at create)
/// @param additionalClientIds  further clients the domain's users belong to
/// @param grantedClientIds     clients the domain's users are granted access to
/// @param requiredOidcTenantId optional OIDC tenant the provider must assert
/// @param twoFactor            the second-factor policy
/// @param createdAt            creation time
/// @param updatedAt            last change
public record EmailDomainMapping(
        String id,
        String emailDomain,
        String identityProviderId,
        ScopeType scopeType,
        String primaryClientId,
        List<String> additionalClientIds,
        List<String> grantedClientIds,
        String requiredOidcTenantId,
        TwoFactorPolicy twoFactor,
        Instant createdAt,
        Instant updatedAt) implements HasId {

    public EmailDomainMapping {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(emailDomain, "emailDomain");
        Objects.requireNonNull(identityProviderId, "identityProviderId");
        Objects.requireNonNull(scopeType, "scopeType");
        additionalClientIds = additionalClientIds == null ? List.of() : List.copyOf(additionalClientIds);
        grantedClientIds = grantedClientIds == null ? List.of() : List.copyOf(grantedClientIds);
        Objects.requireNonNull(twoFactor, "twoFactor");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    /// A fresh mapping with no grants and the 2FA policy off. The domain
    /// arrives already normalised and validated ([EmailDomain#parse]) — the
    /// type makes "no unvalidated domain reaches the aggregate" a fact.
    public static EmailDomainMapping create(EmailDomain domain, String identityProviderId, ScopeType scope) {
        Instant now = Instant.now();
        return new EmailDomainMapping(EntityType.EMAIL_DOMAIN_MAPPING.generate(), domain.value(), identityProviderId,
                scope, null, List.of(), List.of(), null, TwoFactorPolicy.OFF, now, now);
    }

    // ── Transitions (spec §2) ──────────────────────────────────────────────

    /// Re-points the domain to `targetIdentityProviderId`.
    ///
    /// @throws UseCaseException conflict `ALREADY_ON_PROVIDER` when the target is the current provider
    public EmailDomainMapping moveToProvider(String targetIdentityProviderId) {
        if (identityProviderId.equals(targetIdentityProviderId)) {
            throw UseCaseException.conflict("ALREADY_ON_PROVIDER",
                    "Email domain '" + emailDomain + "' is already mapped to that identity provider");
        }
        return new EmailDomainMapping(id, emailDomain, targetIdentityProviderId, scopeType, primaryClientId,
                additionalClientIds, grantedClientIds, requiredOidcTenantId, twoFactor, createdAt, Instant.now());
    }

    /// The admin update (spec §1 absent-value rules): `primaryClientId` and
    /// `requiredOidcTenantId` are replaced wholesale (`null` clears); every
    /// other non-null field of `changes` replaces the current value; the
    /// resulting policy must be consistent. Domain, provider and scope are
    /// not updatable — re-pointing goes through [#moveToProvider].
    ///
    /// @throws UseCaseException validation `2FA_METHOD_REQUIRED` (see [TwoFactorPolicy#checkConsistent])
    public EmailDomainMapping update(Changes changes) {
        TwoFactorPolicy policy = new TwoFactorPolicy(
                changes.require2fa() == null ? twoFactor.required() : changes.require2fa(),
                changes.allowed2faMethods() == null ? twoFactor.allowedMethods() : changes.allowed2faMethods(),
                changes.rememberDeviceEnabled() == null ? twoFactor.rememberDeviceEnabled() : changes.rememberDeviceEnabled(),
                changes.rememberDeviceDays() == null ? twoFactor.rememberDeviceDays() : changes.rememberDeviceDays());
        policy.checkConsistent();
        return new EmailDomainMapping(id, emailDomain, identityProviderId, scopeType,
                changes.primaryClientId(),
                changes.additionalClientIds() == null ? additionalClientIds : changes.additionalClientIds(),
                changes.grantedClientIds() == null ? grantedClientIds : changes.grantedClientIds(),
                changes.requiredOidcTenantId(),
                policy, createdAt, Instant.now());
    }

    /// The fields an admin update may replace. `primaryClientId` and
    /// `requiredOidcTenantId` are **wholesale**: `null` clears them (spec §1,
    /// open question 3). Every other component is `null` = leave untouched;
    /// an empty list clears that list; `rememberDeviceDays` is stored as
    /// given, including `0` and negatives (spec §1, open question 4).
    public record Changes(String primaryClientId, List<String> additionalClientIds, List<String> grantedClientIds,
                          String requiredOidcTenantId, Boolean require2fa, List<MfaMethod> allowed2faMethods,
                          Boolean rememberDeviceEnabled, Integer rememberDeviceDays) {
        public Changes {
            additionalClientIds = additionalClientIds == null ? null : List.copyOf(additionalClientIds);
            grantedClientIds = grantedClientIds == null ? null : List.copyOf(grantedClientIds);
            allowed2faMethods = allowed2faMethods == null ? null : List.copyOf(allowed2faMethods);
        }
    }

    /// Replaces the second-factor policy (construction time — [#update] is the admin path).
    ///
    /// @throws UseCaseException validation `2FA_METHOD_REQUIRED` (see [TwoFactorPolicy#checkConsistent])
    public EmailDomainMapping withTwoFactor(TwoFactorPolicy policy) {
        policy.checkConsistent();
        return new EmailDomainMapping(id, emailDomain, identityProviderId, scopeType, primaryClientId,
                additionalClientIds, grantedClientIds, requiredOidcTenantId, policy, createdAt, Instant.now());
    }

    // ── Copies (construction-time defaults for the create chain; admin updates go through update(Changes)) ──

    public EmailDomainMapping withPrimaryClientId(String newPrimaryClientId) {
        return new EmailDomainMapping(id, emailDomain, identityProviderId, scopeType, newPrimaryClientId,
                additionalClientIds, grantedClientIds, requiredOidcTenantId, twoFactor, createdAt, Instant.now());
    }

    public EmailDomainMapping withAdditionalClientIds(List<String> newIds) {
        return new EmailDomainMapping(id, emailDomain, identityProviderId, scopeType, primaryClientId,
                newIds, grantedClientIds, requiredOidcTenantId, twoFactor, createdAt, Instant.now());
    }

    public EmailDomainMapping withGrantedClientIds(List<String> newIds) {
        return new EmailDomainMapping(id, emailDomain, identityProviderId, scopeType, primaryClientId,
                additionalClientIds, newIds, requiredOidcTenantId, twoFactor, createdAt, Instant.now());
    }

    public EmailDomainMapping withRequiredOidcTenantId(String newTenantId) {
        return new EmailDomainMapping(id, emailDomain, identityProviderId, scopeType, primaryClientId,
                additionalClientIds, grantedClientIds, newTenantId, twoFactor, createdAt, Instant.now());
    }
}
