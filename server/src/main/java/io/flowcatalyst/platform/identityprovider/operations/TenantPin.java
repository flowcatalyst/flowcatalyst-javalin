package io.flowcatalyst.platform.identityprovider.operations;

import io.flowcatalyst.platform.emaildomainmapping.EmailDomainMapping;
import io.flowcatalyst.platform.identityprovider.IdentityProvider;
import io.flowcatalyst.sdk.usecase.UseCaseException;

import java.util.Collection;
import java.util.List;

/// A multi-tenant OIDC provider must pin the tenant (owner ruling 2026-09-25,
/// `docs/backlog.md` §"Overnight review" item 3).
///
/// Entra's shared multi-tenant keys sign tokens for **any** tenant, and the
/// `email` claim is an attribute any tenant admin can set. Without a pin, an
/// attacker's own tenant could sign in as `victim@customer.com` through that
/// customer's domain mapping (the "nOAuth" class). A pin is either the
/// mapping's own `requiredOidcTenantId`, or the provider's `allowedTenantIds`,
/// which cover every mapping without its own and provider-direct logins.
///
/// Refused when saved (a mapping or provider change that would leave a mapping
/// of a multi-tenant provider unpinned), and again at login, which covers rows
/// that predate the rule.
public final class TenantPin {

    private TenantPin() {
    }

    /// The domains routed to `ip` whose mappings would carry no pin.
    ///
    /// @param mappings the mappings that will route to `ip`, by domain; a domain
    ///                 with no mapping yet (about to be created) is passed as `null` pin
    public static List<String> unpinned(IdentityProvider ip, Collection<Routed> mappings) {
        if (ip.tenantPinned(null)) {
            return List.of();
        }
        return mappings.stream().filter(m -> !ip.tenantPinned(m.pin())).map(Routed::domain).toList();
    }

    /// One routed domain and its mapping's own pin (`null` when none, or not yet created).
    public record Routed(String domain, String pin) {
        public static Routed of(EmailDomainMapping m) {
            return new Routed(m.emailDomain(), m.requiredOidcTenantId());
        }
    }

    /// @throws UseCaseException validation `TENANT_PIN_REQUIRED`, naming the domains
    public static void require(IdentityProvider ip, Collection<Routed> mappings) {
        List<String> unpinned = unpinned(ip, mappings);
        if (!unpinned.isEmpty()) {
            throw UseCaseException.validation("TENANT_PIN_REQUIRED",
                    "A multi-tenant identity provider must pin the tenant: set allowedTenantIds on the provider, "
                            + "or requiredOidcTenantId on each mapping. Unpinned: " + String.join(", ", unpinned));
        }
    }
}
