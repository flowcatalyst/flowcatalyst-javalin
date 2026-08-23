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

    /// Replaces the second-factor policy.
    ///
    /// @throws UseCaseException validation `2FA_METHOD_REQUIRED` (see [TwoFactorPolicy#checkConsistent])
    public EmailDomainMapping withTwoFactor(TwoFactorPolicy policy) {
        policy.checkConsistent();
        return new EmailDomainMapping(id, emailDomain, identityProviderId, scopeType, primaryClientId,
                additionalClientIds, grantedClientIds, requiredOidcTenantId, policy, createdAt, Instant.now());
    }

    // ── Copies ─────────────────────────────────────────────────────────────

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
