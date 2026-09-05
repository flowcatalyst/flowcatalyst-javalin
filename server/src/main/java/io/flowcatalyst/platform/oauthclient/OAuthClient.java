package io.flowcatalyst.platform.oauthclient;

import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.sdk.usecase.HasId;
import io.flowcatalyst.sdk.usecase.UseCaseException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/// The OAuth-client aggregate root (spec: `docs/spec/auth-core.md` §3.6,
/// §6.3, §8.5; grace-window ruling A-22, `docs/improvements.md`). A
/// registered client of the platform's OAuth 2.0 provider. Platform-level —
/// no client dimension — so every by-id write is `Authorize.publicAccess()`
/// and the anchor-only gate lives in the handler (spec §6.3).
///
/// Immutable record: each transition returns a copy (or a small nested
/// result record when the transition has a side result) and throws
/// [UseCaseException] when an invariant is violated. `client_secret_ref` /
/// `previous_secret_ref` hold ciphertext ([io.flowcatalyst.platform.shared.encryption.SecretRef])
/// exactly as written by the repository — the entity never decrypts on its
/// own; [#acceptsSecret] takes a decryptor so the token endpoint (out of
/// this unit's scope) can supply the real one.
///
/// @param id                       `oac_…` TSID
/// @param clientId                 the OAuth2 `client_id` string, unique; backend-generated when omitted (internal-only)
/// @param clientName               required, trimmed on update
/// @param clientType               `PUBLIC` / `CONFIDENTIAL`; unknown never defaults (X-06)
/// @param secretRef                the current secret's ciphertext ref; `null` for `PUBLIC`
/// @param previousSecretRef        the demoted secret kept usable during a rotation overlap; read only through [#usablePreviousSecretRef]
/// @param previousSecretExpiresAt  when the overlap lapses; `null` = no overlap in flight
/// @param previousSecretLastUsedAt when a caller last authenticated on the previous secret; `null` = never (or no overlap)
/// @param redirectUris             OAuth2 redirect URI allowlist
/// @param postLogoutRedirectUris   OIDC RP-Initiated Logout allowlist
/// @param grantTypes               allowed grant types; **empty = no grant allowed** (ruling Q17/Q20, fail closed)
/// @param defaultScopes            the client's scope list — the single wire name `defaultScopes` everywhere (Fix 5)
/// @param allowedOrigins           CORS allowlist (persisted here, consumed by the CORS filter, not this unit)
/// @param applicationIds           application scope; `applications[{id,name}]` is a read-side resolution, not stored here
/// @param pkceRequired             whether `/oauth/authorize` demands a code_challenge
/// @param active                   `Active` / `Inactive` via activate/deactivate; idempotent, no error either way
/// @param principalId              the `SERVICE` principal `client_credentials` mints for; `null` until attached
/// @param portalClientId           non-blank ⇒ portal plane; mutually exclusive with [#apiAccess]
/// @param apiAccess                authority-bearing interactive tokens; mutually exclusive with a portal client
/// @param createdAt                creation time
/// @param updatedAt                last change
public record OAuthClient(
        String id,
        String clientId,
        String clientName,
        ClientType clientType,
        String secretRef,
        String previousSecretRef,
        Instant previousSecretExpiresAt,
        Instant previousSecretLastUsedAt,
        List<String> redirectUris,
        List<String> postLogoutRedirectUris,
        List<String> grantTypes,
        List<String> defaultScopes,
        List<String> allowedOrigins,
        List<String> applicationIds,
        boolean pkceRequired,
        boolean active,
        String principalId,
        String portalClientId,
        boolean apiAccess,
        Instant createdAt,
        Instant updatedAt) implements HasId {

    public OAuthClient {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(clientId, "clientId");
        Objects.requireNonNull(clientName, "clientName");
        Objects.requireNonNull(clientType, "clientType");
        redirectUris = redirectUris == null ? List.of() : List.copyOf(redirectUris);
        postLogoutRedirectUris = postLogoutRedirectUris == null ? List.of() : List.copyOf(postLogoutRedirectUris);
        grantTypes = grantTypes == null ? List.of() : List.copyOf(grantTypes);
        defaultScopes = defaultScopes == null ? List.of() : List.copyOf(defaultScopes);
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
        applicationIds = applicationIds == null ? List.of() : List.copyOf(applicationIds);
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    /// A fresh, active client with every collection empty, `pkceRequired`
    /// true, no secret, no portal/apiAccess plane and no application scope.
    /// The `Create` operation chains the `withX` copies below to populate it
    /// (construction-time defaults, not an admin update — CONVENTIONS §2).
    public static OAuthClient create(String clientId, String clientName, ClientType clientType) {
        Objects.requireNonNull(clientId, "clientId");
        Objects.requireNonNull(clientName, "clientName");
        Objects.requireNonNull(clientType, "clientType");
        Instant now = Instant.now();
        return new OAuthClient(EntityType.OAUTH_CLIENT.generate(), clientId, clientName, clientType,
                null, null, null, null,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                true, true, null, null, false, now, now);
    }

    // ── Grant / plane rules ──────────────────────────────────────────────────

    /// Whether `grantType` is permitted. **Empty [#grantTypes] means no grant
    /// is allowed** (ruling Q17/Q20: fail closed, overriding Go's legacy
    /// "empty = unrestricted") — never coalesced to "anything goes".
    public boolean allowsGrant(String grantType) {
        return !grantTypes.isEmpty() && grantTypes.contains(grantType);
    }

    /// Non-blank [#portalClientId] ⇒ this client is a portal entry point.
    public boolean isPortal() {
        return portalClientId != null;
    }

    // ── Lifecycle (spec §8.5): idempotent, no error either way ───────────────

    public OAuthClient activate() {
        return new OAuthClient(id, clientId, clientName, clientType, secretRef, previousSecretRef,
                previousSecretExpiresAt, previousSecretLastUsedAt, redirectUris, postLogoutRedirectUris, grantTypes,
                defaultScopes, allowedOrigins, applicationIds, pkceRequired, true, principalId, portalClientId,
                apiAccess, createdAt, Instant.now());
    }

    public OAuthClient deactivate() {
        return new OAuthClient(id, clientId, clientName, clientType, secretRef, previousSecretRef,
                previousSecretExpiresAt, previousSecretLastUsedAt, redirectUris, postLogoutRedirectUris, grantTypes,
                defaultScopes, allowedOrigins, applicationIds, pkceRequired, false, principalId, portalClientId,
                apiAccess, createdAt, Instant.now());
    }

    // ── Secret at rest (A-22, `docs/improvements.md`) ────────────────────────

    /// Installs `ref` as the current secret with no overlap window,
    /// discarding any in-flight previous secret — the provisioning path (a
    /// brand-new client has no prior secret to honour) and the
    /// immediate-cutover path for a secret believed compromised.
    public OAuthClient withSecretRef(String ref) {
        Objects.requireNonNull(ref, "ref");
        return new OAuthClient(id, clientId, clientName, clientType, ref, null, null, null,
                redirectUris, postLogoutRedirectUris, grantTypes, defaultScopes, allowedOrigins, applicationIds,
                pkceRequired, active, principalId, portalClientId, apiAccess, createdAt, Instant.now());
    }

    /// Installs `ref` as the current secret and keeps the outgoing one
    /// acceptable for `grace`, so a fleet holding the old secret can be
    /// rolled gradually instead of all at once. A non-positive `grace` — or
    /// no current secret to demote — degrades to [#withSecretRef] (hard
    /// cutover). Exactly one previous secret is honoured at a time: rotating
    /// twice inside a window retires the older one immediately.
    ///
    /// @return the changed client, and the instant the outgoing secret
    ///         lapses (`null` when none was kept — an immediate cutover)
    public RotateResult rotateSecret(String ref, Duration grace, Instant now) {
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(grace, "grace");
        Objects.requireNonNull(now, "now");
        if (grace.isZero() || grace.isNegative() || secretRef == null) {
            return new RotateResult(withSecretRef(ref), null);
        }
        Instant expires = now.plus(grace);
        OAuthClient updated = new OAuthClient(id, clientId, clientName, clientType, ref, secretRef, expires,
                null, // a fresh overlap starts unused
                redirectUris, postLogoutRedirectUris, grantTypes, defaultScopes, allowedOrigins, applicationIds,
                pkceRequired, active, principalId, portalClientId, apiAccess, createdAt, now);
        return new RotateResult(updated, expires);
    }

    /// The outcome of [#rotateSecret]: the changed client, and when the
    /// outgoing secret lapses (`null` on an immediate cutover).
    public record RotateResult(OAuthClient client, Instant previousSecretExpiresAt) {
        public RotateResult {
            Objects.requireNonNull(client, "client");
        }
    }

    /// Ends an in-flight overlap immediately. No-op (same instance, no
    /// `updatedAt` bump) when no previous secret is held, so calling this
    /// twice — or after the window already lapsed — changes nothing.
    public RevokeResult revokePreviousSecret() {
        if (previousSecretRef == null) {
            return new RevokeResult(this, false);
        }
        OAuthClient updated = new OAuthClient(id, clientId, clientName, clientType, secretRef, null, null, null,
                redirectUris, postLogoutRedirectUris, grantTypes, defaultScopes, allowedOrigins, applicationIds,
                pkceRequired, active, principalId, portalClientId, apiAccess, createdAt, Instant.now());
        return new RevokeResult(updated, true);
    }

    /// The outcome of [#revokePreviousSecret]: the changed (or unchanged)
    /// client, and whether an overlap was actually dropped.
    public record RevokeResult(OAuthClient client, boolean dropped) {
        public RevokeResult {
            Objects.requireNonNull(client, "client");
        }
    }

    /// The previous secret ref while its overlap window is still open, else
    /// empty. Verification MUST go through this rather than reading
    /// [#previousSecretRef] directly — a lapsed ref is still present in the
    /// row until the purger clears it (hygiene, not enforcement).
    public Optional<String> usablePreviousSecretRef(Instant now) {
        Objects.requireNonNull(now, "now");
        if (previousSecretRef == null || previousSecretExpiresAt == null) {
            return Optional.empty();
        }
        if (!now.isBefore(previousSecretExpiresAt)) {
            return Optional.empty();
        }
        return Optional.of(previousSecretRef);
    }

    /// Verifies `providedPlaintext` against the current secret and, failing
    /// that, against a usable previous secret. **Both compares always run**
    /// (`docs/improvements.md`): short-circuiting on a current-secret match
    /// would make a still-valid old secret measurably slower than a new one,
    /// leaking where a client sits in its rotation — a lazily-evaluated `||`
    /// over the two calls would reintroduce exactly that, so `current` and
    /// `previousMatches` are each fully computed (decryptor included) before
    /// they are combined. `decryptor` resolves a stored ref to its plaintext
    /// (empty when it cannot).
    public boolean acceptsSecret(String providedPlaintext, Instant now, Function<String, Optional<String>> decryptor) {
        Objects.requireNonNull(providedPlaintext, "providedPlaintext");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(decryptor, "decryptor");
        boolean current = secretRef != null && matches(secretRef, providedPlaintext, decryptor);
        Optional<String> previous = usablePreviousSecretRef(now);
        boolean previousMatches = previous.isPresent() && matches(previous.get(), providedPlaintext, decryptor);
        return current || previousMatches;
    }

    private static boolean matches(String ref, String providedPlaintext, Function<String, Optional<String>> decryptor) {
        return decryptor.apply(ref)
                .map(pt -> MessageDigest.isEqual(
                        pt.getBytes(StandardCharsets.UTF_8), providedPlaintext.getBytes(StandardCharsets.UTF_8)))
                .orElse(false);
    }

    // ── Construction-time / admin-update copies ──────────────────────────────

    public OAuthClient withRedirectUris(List<String> uris) {
        return new OAuthClient(id, clientId, clientName, clientType, secretRef, previousSecretRef,
                previousSecretExpiresAt, previousSecretLastUsedAt, uris, postLogoutRedirectUris, grantTypes,
                defaultScopes, allowedOrigins, applicationIds, pkceRequired, active, principalId, portalClientId,
                apiAccess, createdAt, updatedAt);
    }

    public OAuthClient withPostLogoutRedirectUris(List<String> uris) {
        return new OAuthClient(id, clientId, clientName, clientType, secretRef, previousSecretRef,
                previousSecretExpiresAt, previousSecretLastUsedAt, redirectUris, uris, grantTypes,
                defaultScopes, allowedOrigins, applicationIds, pkceRequired, active, principalId, portalClientId,
                apiAccess, createdAt, updatedAt);
    }

    public OAuthClient withGrantTypes(List<String> types) {
        return new OAuthClient(id, clientId, clientName, clientType, secretRef, previousSecretRef,
                previousSecretExpiresAt, previousSecretLastUsedAt, redirectUris, postLogoutRedirectUris, types,
                defaultScopes, allowedOrigins, applicationIds, pkceRequired, active, principalId, portalClientId,
                apiAccess, createdAt, updatedAt);
    }

    public OAuthClient withDefaultScopes(List<String> scopes) {
        return new OAuthClient(id, clientId, clientName, clientType, secretRef, previousSecretRef,
                previousSecretExpiresAt, previousSecretLastUsedAt, redirectUris, postLogoutRedirectUris, grantTypes,
                scopes, allowedOrigins, applicationIds, pkceRequired, active, principalId, portalClientId,
                apiAccess, createdAt, updatedAt);
    }

    public OAuthClient withAllowedOrigins(List<String> origins) {
        return new OAuthClient(id, clientId, clientName, clientType, secretRef, previousSecretRef,
                previousSecretExpiresAt, previousSecretLastUsedAt, redirectUris, postLogoutRedirectUris, grantTypes,
                defaultScopes, origins, applicationIds, pkceRequired, active, principalId, portalClientId,
                apiAccess, createdAt, updatedAt);
    }

    public OAuthClient withApplicationIds(List<String> ids) {
        return new OAuthClient(id, clientId, clientName, clientType, secretRef, previousSecretRef,
                previousSecretExpiresAt, previousSecretLastUsedAt, redirectUris, postLogoutRedirectUris, grantTypes,
                defaultScopes, allowedOrigins, ids, pkceRequired, active, principalId, portalClientId,
                apiAccess, createdAt, updatedAt);
    }

    public OAuthClient withPrincipalId(String newPrincipalId) {
        return new OAuthClient(id, clientId, clientName, clientType, secretRef, previousSecretRef,
                previousSecretExpiresAt, previousSecretLastUsedAt, redirectUris, postLogoutRedirectUris, grantTypes,
                defaultScopes, allowedOrigins, applicationIds, pkceRequired, active, newPrincipalId, portalClientId,
                apiAccess, createdAt, updatedAt);
    }

    public OAuthClient withPkceRequired(boolean required) {
        return new OAuthClient(id, clientId, clientName, clientType, secretRef, previousSecretRef,
                previousSecretExpiresAt, previousSecretLastUsedAt, redirectUris, postLogoutRedirectUris, grantTypes,
                defaultScopes, allowedOrigins, applicationIds, required, active, principalId, portalClientId,
                apiAccess, createdAt, updatedAt);
    }

    public OAuthClient withClientName(String name) {
        return new OAuthClient(id, clientId, name, clientType, secretRef, previousSecretRef,
                previousSecretExpiresAt, previousSecretLastUsedAt, redirectUris, postLogoutRedirectUris, grantTypes,
                defaultScopes, allowedOrigins, applicationIds, pkceRequired, active, principalId, portalClientId,
                apiAccess, createdAt, updatedAt);
    }

    /// Sets both plane flags together, since they are mutually exclusive: a
    /// portal identity never carries platform authority. `newPortalClientId`
    /// is normalised here — blank clears it, otherwise it is trimmed — the
    /// single place either flag changes, so the invariant cannot be
    /// bypassed by setting them one at a time.
    ///
    /// @throws UseCaseException validation `PORTAL_API_ACCESS_CONFLICT`
    public OAuthClient withPortalAndApiAccess(String newPortalClientId, boolean newApiAccess) {
        String normalised = newPortalClientId == null || newPortalClientId.isBlank() ? null : newPortalClientId.trim();
        if (newApiAccess && normalised != null) {
            throw UseCaseException.validation("PORTAL_API_ACCESS_CONFLICT",
                    "a portal client cannot have apiAccess — portal identities never carry platform authority");
        }
        return new OAuthClient(id, clientId, clientName, clientType, secretRef, previousSecretRef,
                previousSecretExpiresAt, previousSecretLastUsedAt, redirectUris, postLogoutRedirectUris, grantTypes,
                defaultScopes, allowedOrigins, applicationIds, pkceRequired, active, principalId, normalised,
                newApiAccess, createdAt, updatedAt);
    }

    // ── Update (CONVENTIONS §2: one update(Changes) transition) ──────────────

    /// A multi-field partial update: `null` = untouched (collections included
    /// — an empty list is an explicit clear, not "no change"). `portalClientId`
    /// is the one field with three states, matching the wire contract exactly
    /// (spec §6.3): `null` = untouched, blank = clear, non-blank = set. This
    /// is not the "empty-string-as-absence" sentinel CONVENTIONS §8 warns
    /// against — blank here is a distinct, spec-mandated *clear* signal,
    /// never confused with "field omitted".
    public record Changes(
            String clientName,
            List<String> redirectUris,
            List<String> postLogoutRedirectUris,
            List<String> grantTypes,
            List<String> defaultScopes,
            List<String> allowedOrigins,
            List<String> applicationIds,
            Boolean pkceRequired,
            String portalClientId,
            Boolean apiAccess) {
        public Changes {
            redirectUris = redirectUris == null ? null : List.copyOf(redirectUris);
            postLogoutRedirectUris = postLogoutRedirectUris == null ? null : List.copyOf(postLogoutRedirectUris);
            grantTypes = grantTypes == null ? null : List.copyOf(grantTypes);
            defaultScopes = defaultScopes == null ? null : List.copyOf(defaultScopes);
            allowedOrigins = allowedOrigins == null ? null : List.copyOf(allowedOrigins);
            applicationIds = applicationIds == null ? null : List.copyOf(applicationIds);
        }
    }

    /// Applies every non-null field of `c` and re-stamps `updatedAt`.
    ///
    /// @throws UseCaseException validation `PORTAL_API_ACCESS_CONFLICT`
    public OAuthClient update(Changes c) {
        OAuthClient intermediate = new OAuthClient(id, clientId,
                c.clientName() != null ? c.clientName().trim() : clientName,
                clientType, secretRef, previousSecretRef, previousSecretExpiresAt, previousSecretLastUsedAt,
                c.redirectUris() != null ? c.redirectUris() : redirectUris,
                c.postLogoutRedirectUris() != null ? c.postLogoutRedirectUris() : postLogoutRedirectUris,
                c.grantTypes() != null ? c.grantTypes() : grantTypes,
                c.defaultScopes() != null ? c.defaultScopes() : defaultScopes,
                c.allowedOrigins() != null ? c.allowedOrigins() : allowedOrigins,
                c.applicationIds() != null ? c.applicationIds() : applicationIds,
                c.pkceRequired() != null ? c.pkceRequired() : pkceRequired,
                active, principalId, portalClientId, apiAccess, createdAt, Instant.now());
        String portalInput = c.portalClientId() != null ? c.portalClientId() : intermediate.portalClientId;
        boolean apiAccessInput = c.apiAccess() != null ? c.apiAccess() : intermediate.apiAccess;
        return intermediate.withPortalAndApiAccess(portalInput, apiAccessInput);
    }
}
