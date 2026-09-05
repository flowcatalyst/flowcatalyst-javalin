package io.flowcatalyst.platform.authadmin;

import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.sdk.usecase.HasId;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/// Per-e-mail-domain login configuration (spec §1): which scope
/// ([ConfigType]) such users get, which clients they are bound to
/// (`primaryClientId`, `additionalClientIds`, `grantedClientIds`), and the
/// provider ([AuthProvider]). Client auth configs are anchor-only platform
/// configuration with no per-resource scope.
///
/// Immutable record: [#update] returns a copy; the repository persists
/// whatever copy it is handed and stamps `updatedAt` itself.
///
/// @param id                    `cac_…` TSID
/// @param emailDomain           normalised (trimmed, lower-cased) domain, unique, immutable
/// @param configType            `ANCHOR` | `PARTNER` | `CLIENT`, immutable
/// @param primaryClientId       optional primary client
/// @param additionalClientIds   further clients the domain's users belong to
/// @param grantedClientIds      clients the domain's users are granted access to
/// @param authProvider          `INTERNAL` | `OIDC`
/// @param oidcIssuerUrl         required when `authProvider` is `OIDC` at create (spec §4.2)
/// @param oidcClientId          required when `authProvider` is `OIDC` at create
/// @param oidcMultiTenant       whether the OIDC issuer is multi-tenant
/// @param oidcIssuerPattern     optional issuer-matching pattern for multi-tenant issuers
/// @param oidcClientSecretRef   a reference to the client secret, never the secret itself (spec §8, Q1)
/// @param createdAt             creation time
/// @param updatedAt             last change
public record ClientAuthConfig(
        String id,
        String emailDomain,
        ConfigType configType,
        String primaryClientId,
        List<String> additionalClientIds,
        List<String> grantedClientIds,
        AuthProvider authProvider,
        String oidcIssuerUrl,
        String oidcClientId,
        boolean oidcMultiTenant,
        String oidcIssuerPattern,
        String oidcClientSecretRef,
        Instant createdAt,
        Instant updatedAt) implements HasId {

    public ClientAuthConfig {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(emailDomain, "emailDomain");
        Objects.requireNonNull(configType, "configType");
        additionalClientIds = additionalClientIds == null ? List.of() : List.copyOf(additionalClientIds);
        grantedClientIds = grantedClientIds == null ? List.of() : List.copyOf(grantedClientIds);
        Objects.requireNonNull(authProvider, "authProvider");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    /// A fresh config with no grants and no OIDC settings. The domain arrives
    /// already normalised and validated ([ClientAuthConfigEmailDomain#parse]).
    public static ClientAuthConfig create(ClientAuthConfigEmailDomain emailDomain, ConfigType configType, AuthProvider authProvider) {
        Instant now = Instant.now();
        return new ClientAuthConfig(EntityType.CLIENT_AUTH_CONFIG.generate(), emailDomain.value(), configType, null,
                List.of(), List.of(), authProvider, null, null, false, null, null, now, now);
    }

    // ── Copies (construction-time defaults for the create chain; admin updates go through update(Changes)) ──

    public ClientAuthConfig withPrimaryClientId(String newPrimaryClientId) {
        return new ClientAuthConfig(id, emailDomain, configType, newPrimaryClientId, additionalClientIds,
                grantedClientIds, authProvider, oidcIssuerUrl, oidcClientId, oidcMultiTenant, oidcIssuerPattern,
                oidcClientSecretRef, createdAt, Instant.now());
    }

    public ClientAuthConfig withAdditionalClientIds(List<String> newIds) {
        return new ClientAuthConfig(id, emailDomain, configType, primaryClientId, newIds, grantedClientIds,
                authProvider, oidcIssuerUrl, oidcClientId, oidcMultiTenant, oidcIssuerPattern, oidcClientSecretRef,
                createdAt, Instant.now());
    }

    public ClientAuthConfig withGrantedClientIds(List<String> newIds) {
        return new ClientAuthConfig(id, emailDomain, configType, primaryClientId, additionalClientIds, newIds,
                authProvider, oidcIssuerUrl, oidcClientId, oidcMultiTenant, oidcIssuerPattern, oidcClientSecretRef,
                createdAt, Instant.now());
    }

    public ClientAuthConfig withOidcIssuerUrl(String v) {
        return new ClientAuthConfig(id, emailDomain, configType, primaryClientId, additionalClientIds,
                grantedClientIds, authProvider, v, oidcClientId, oidcMultiTenant, oidcIssuerPattern,
                oidcClientSecretRef, createdAt, Instant.now());
    }

    public ClientAuthConfig withOidcClientId(String v) {
        return new ClientAuthConfig(id, emailDomain, configType, primaryClientId, additionalClientIds,
                grantedClientIds, authProvider, oidcIssuerUrl, v, oidcMultiTenant, oidcIssuerPattern,
                oidcClientSecretRef, createdAt, Instant.now());
    }

    public ClientAuthConfig withOidcMultiTenant(boolean v) {
        return new ClientAuthConfig(id, emailDomain, configType, primaryClientId, additionalClientIds,
                grantedClientIds, authProvider, oidcIssuerUrl, oidcClientId, v, oidcIssuerPattern,
                oidcClientSecretRef, createdAt, Instant.now());
    }

    public ClientAuthConfig withOidcIssuerPattern(String v) {
        return new ClientAuthConfig(id, emailDomain, configType, primaryClientId, additionalClientIds,
                grantedClientIds, authProvider, oidcIssuerUrl, oidcClientId, oidcMultiTenant, v,
                oidcClientSecretRef, createdAt, Instant.now());
    }

    public ClientAuthConfig withOidcClientSecretRef(String v) {
        return new ClientAuthConfig(id, emailDomain, configType, primaryClientId, additionalClientIds,
                grantedClientIds, authProvider, oidcIssuerUrl, oidcClientId, oidcMultiTenant, oidcIssuerPattern,
                v, createdAt, Instant.now());
    }

    /// The admin update (spec §4.2): every field of `changes` that is
    /// non-null replaces the current value; `null` means untouched
    /// (`emailDomain` and `configType` are not updatable — not part of
    /// [Changes] at all). No OIDC-completeness check here — kept as Go (spec
    /// §8 D3, an owner question).
    public ClientAuthConfig update(Changes changes) {
        return new ClientAuthConfig(id, emailDomain, configType,
                changes.primaryClientId() == null ? primaryClientId : changes.primaryClientId(),
                changes.additionalClientIds() == null ? additionalClientIds : changes.additionalClientIds(),
                changes.grantedClientIds() == null ? grantedClientIds : changes.grantedClientIds(),
                changes.authProvider() == null ? authProvider : changes.authProvider(),
                changes.oidcIssuerUrl() == null ? oidcIssuerUrl : changes.oidcIssuerUrl(),
                changes.oidcClientId() == null ? oidcClientId : changes.oidcClientId(),
                changes.oidcMultiTenant() == null ? oidcMultiTenant : changes.oidcMultiTenant(),
                changes.oidcIssuerPattern() == null ? oidcIssuerPattern : changes.oidcIssuerPattern(),
                changes.oidcClientSecretRef() == null ? oidcClientSecretRef : changes.oidcClientSecretRef(),
                createdAt, Instant.now());
    }

    /// The fields an admin update may replace (spec §4.2): every component
    /// is `null` = leave untouched; an empty list clears that list.
    public record Changes(String primaryClientId, List<String> additionalClientIds, List<String> grantedClientIds,
                          AuthProvider authProvider, String oidcIssuerUrl, String oidcClientId, Boolean oidcMultiTenant,
                          String oidcIssuerPattern, String oidcClientSecretRef) {
        public Changes {
            additionalClientIds = additionalClientIds == null ? null : List.copyOf(additionalClientIds);
            grantedClientIds = grantedClientIds == null ? null : List.copyOf(grantedClientIds);
        }
    }
}
