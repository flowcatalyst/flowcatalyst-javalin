package io.flowcatalyst.platform.principal;

import io.flowcatalyst.platform.emaildomainmapping.EmailDomainMapping;
import io.flowcatalyst.platform.emaildomainmapping.ScopeType;
import io.flowcatalyst.sdk.usecase.UseCaseException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/// The create-user scope rules (spec §7): the caller's requested scope wins
/// and defaults to `CLIENT`; the email domain's setup can only **confirm** a
/// privileged scope, never grant one unasked. Pure — the handler resolves
/// the anchor-domain flag, the mapping and the client reference, then asks.
public final class UserScopeDerivation {

    private UserScopeDerivation() {
    }

    /// A derived `(scope, homeClientId)` pair; `clientId` is `null` for anchors.
    public record Derived(UserScope scope, String clientId) {
        public Derived {
            Objects.requireNonNull(scope, "scope");
        }
    }

    /// @param requestedScope the request's `scope` (`null`/blank = `CLIENT`), case-insensitive
    /// @param isAnchorDomain whether the email's domain is a registered anchor domain
    /// @param mapping        the email-domain mapping for the domain, or `null`
    /// @param clientId       the request's client id (already resolved to a `clt_` id), or `null`
    /// @throws UseCaseException validation `ANCHOR_DOMAIN_REQUIRED` | `PARTNER_DOMAIN_REQUIRED` |
    ///                          `CLIENT_REQUIRED` | `CLIENT_NOT_ALLOWED` | `INVALID_SCOPE`
    public static Derived derive(String requestedScope, boolean isAnchorDomain, EmailDomainMapping mapping, String clientId) {
        String scope = requestedScope == null || requestedScope.isBlank()
                ? "CLIENT" : requestedScope.trim().toUpperCase(Locale.ROOT);
        return switch (scope) {
            case "ANCHOR" -> {
                boolean anchorMapped = mapping != null && mapping.scopeType() == ScopeType.ANCHOR;
                if (!isAnchorDomain && !anchorMapped) {
                    throw UseCaseException.validation("ANCHOR_DOMAIN_REQUIRED",
                            "ANCHOR scope requires the email's domain to be a registered anchor domain");
                }
                yield new Derived(UserScope.ANCHOR, null);
            }
            case "PARTNER" -> {
                if (mapping == null || mapping.scopeType() != ScopeType.PARTNER) {
                    throw UseCaseException.validation("PARTNER_DOMAIN_REQUIRED",
                            "PARTNER scope requires a PARTNER email-domain mapping for the email's domain");
                }
                if (clientId == null || clientId.isEmpty()) {
                    throw UseCaseException.validation("CLIENT_REQUIRED", "clientId is required for partner users");
                }
                boolean allowed = clientId.equals(mapping.primaryClientId()) || mapping.grantedClientIds().contains(clientId);
                if (!allowed) {
                    throw UseCaseException.validation("CLIENT_NOT_ALLOWED",
                            "clientId " + clientId + " is not allowed for partner domain " + mapping.emailDomain());
                }
                yield new Derived(UserScope.PARTNER, clientId);
            }
            case "CLIENT" -> {
                String home = clientId;
                if (home == null && mapping != null && mapping.scopeType() == ScopeType.CLIENT) {
                    home = mapping.primaryClientId();
                }
                yield new Derived(UserScope.CLIENT, home);
            }
            default -> throw UseCaseException.validation("INVALID_SCOPE", UserScope.INVALID_SCOPE_MESSAGE);
        };
    }

    /// The scope a new user from this domain would get before any client is
    /// chosen (`/check-email-domain`): anchor domain → `ANCHOR`; unmapped →
    /// `CLIENT`; otherwise the mapping's scope.
    public static UserScope forDomain(boolean isAnchorDomain, EmailDomainMapping mapping) {
        if (isAnchorDomain) return UserScope.ANCHOR;
        if (mapping == null) return UserScope.CLIENT;
        return switch (mapping.scopeType()) {
            case ANCHOR -> UserScope.ANCHOR;
            case PARTNER -> UserScope.PARTNER;
            case CLIENT -> UserScope.CLIENT;
        };
    }

    /// The client ids a create-user picker is constrained to by the domain's
    /// mapping: PARTNER → primary + granted (de-duplicated); CLIENT → primary;
    /// otherwise none (no restriction).
    public static List<String> allowedClientIds(EmailDomainMapping mapping) {
        if (mapping == null) return List.of();
        return switch (mapping.scopeType()) {
            case PARTNER -> {
                var out = new LinkedHashSet<String>();
                if (mapping.primaryClientId() != null && !mapping.primaryClientId().isEmpty()) out.add(mapping.primaryClientId());
                for (String c : mapping.grantedClientIds()) {
                    if (c != null && !c.isEmpty()) out.add(c);
                }
                yield List.copyOf(out);
            }
            case CLIENT -> mapping.primaryClientId() == null || mapping.primaryClientId().isEmpty()
                    ? List.of() : List.of(mapping.primaryClientId());
            case ANCHOR -> List.of();
        };
    }

    /// The clients an email-domain mapping is *owned* by (primary + additional);
    /// granted ids are access, not ownership. An unmapped or anchor-scope
    /// domain has no owners. Used by bulk import's foreign-domain guard.
    public static List<String> ownerClientIds(EmailDomainMapping mapping) {
        if (mapping == null) return List.of();
        var owners = new ArrayList<String>(1 + mapping.additionalClientIds().size());
        if (mapping.primaryClientId() != null && !mapping.primaryClientId().isEmpty()) owners.add(mapping.primaryClientId());
        owners.addAll(mapping.additionalClientIds());
        return List.copyOf(owners);
    }
}
