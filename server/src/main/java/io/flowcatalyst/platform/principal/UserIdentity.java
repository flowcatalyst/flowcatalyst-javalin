package io.flowcatalyst.platform.principal;

import java.time.Instant;
import java.util.Objects;

/// The human-user half of a USER principal (spec §1), stored as flat
/// columns on `iam_principals`. Carries two secrets — the password hash and
/// the encrypted developer client-secret — so `toString` masks them; they
/// never reach a DTO, an event or an audit row (the API and events files
/// copy individual fields, never this record).
///
/// @param email                    login identity; lower-cased + trimmed on write
/// @param provider                 IdP type (`INTERNAL`, `OIDC`, …); `null` is persisted as `INTERNAL`
/// @param externalId               subject at the IdP, when federated
/// @param passwordHash             argon2id (or a migrated upstream) hash; `null` = no password
/// @param lastLoginAt              stamped by the login flow; carried through persist
/// @param devClientSecretRef       encrypted self-service developer client-secret; `null` = none
/// @param devClientSecretUpdatedAt when the developer secret was last set
public record UserIdentity(
        String email,
        String provider,
        String externalId,
        String passwordHash,
        Instant lastLoginAt,
        String devClientSecretRef,
        Instant devClientSecretUpdatedAt) {

    public static final String INTERNAL = "INTERNAL";
    public static final String OIDC = "OIDC";

    public UserIdentity {
        Objects.requireNonNull(email, "email");
    }

    /// A bare identity for a fresh user: just the email.
    public static UserIdentity of(String email) {
        return new UserIdentity(email, null, null, null, null, null, null);
    }

    public boolean hasPassword() {
        return passwordHash != null;
    }

    public boolean hasDeveloperSecret() {
        return devClientSecretRef != null;
    }

    /// `provider`, or `INTERNAL` when unset — what the wire reports.
    public String providerOrInternal() {
        return provider == null || provider.isEmpty() ? INTERNAL : provider;
    }

    public boolean isOidc() {
        return OIDC.equals(provider);
    }

    UserIdentity withPasswordHash(String hash) {
        return new UserIdentity(email, provider, externalId, hash, lastLoginAt, devClientSecretRef, devClientSecretUpdatedAt);
    }

    UserIdentity withProvider(String newProvider) {
        return new UserIdentity(email, newProvider, externalId, passwordHash, lastLoginAt, devClientSecretRef, devClientSecretUpdatedAt);
    }

    UserIdentity withDeveloperSecret(String ref, Instant at) {
        return new UserIdentity(email, provider, externalId, passwordHash, lastLoginAt, ref, at);
    }

    @Override
    public String toString() {
        return "UserIdentity[email=" + email + ", provider=" + provider + ", externalId=" + externalId
                + ", passwordHash=" + (passwordHash == null ? "null" : "***")
                + ", lastLoginAt=" + lastLoginAt
                + ", devClientSecretRef=" + (devClientSecretRef == null ? "null" : "***")
                + ", devClientSecretUpdatedAt=" + devClientSecretUpdatedAt + "]";
    }
}
