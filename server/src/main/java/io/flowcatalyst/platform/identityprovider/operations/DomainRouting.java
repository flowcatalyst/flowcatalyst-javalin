package io.flowcatalyst.platform.identityprovider.operations;

import io.flowcatalyst.platform.emaildomainmapping.EmailDomain;
import io.flowcatalyst.platform.emaildomainmapping.EmailDomainMapping;
import io.flowcatalyst.platform.emaildomainmapping.EmailDomainMappingRepository;
import io.flowcatalyst.platform.emaildomainmapping.ScopeType;
import io.flowcatalyst.platform.emaildomainmapping.operations.EmailDomainMappingEvents.EmailDomainMappingCreated;
import io.flowcatalyst.platform.emaildomainmapping.operations.EmailDomainMappingEvents.EmailDomainMappingProviderChanged;
import io.flowcatalyst.platform.emaildomainmapping.operations.EmailDomainMappingEvents.EmailDomainMappingUpdated;
import io.flowcatalyst.platform.identityprovider.IdentityProvider;
import io.flowcatalyst.sdk.usecase.ExecutionContext;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.jdbc.TxScopedUnitOfWork;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/// The one place this aggregate touches the email-domain mapping aggregate
/// (spec §4, §9): "route this domain to that provider". Create and update
/// both call [#mapDomain]; update's release path calls [#moveTo]. Every
/// write goes through the mapping repository and emits the mapping
/// aggregate's own events, audited under the identity-provider command that
/// drove them — exactly what the mapping aggregate's own move operation
/// does. Also carries the mapping-scope validation shared by create and
/// update (owner ruling 2026-09-15): the scope of a NEW mapping is the
/// request's explicit choice, never derived from whether a client was given.
/// Flagged: the "move a mapping" rule restated in [#moveTo] belongs in the
/// mapping package as one shared helper (spec §9).
final class DomainRouting {

    private final EmailDomainMappingRepository mappings;

    DomainRouting(EmailDomainMappingRepository mappings) {
        this.mappings = Objects.requireNonNull(mappings, "mappings");
    }

    /// The mapping-scope choice resolved from a command's `mappingScope` /
    /// `primaryClientId` (spec §4 "Validation"): `scope` is `null` only when
    /// the command set neither field — legal only when every domain in the
    /// request already has a mapping ([#requireScopeForNewDomains] enforces
    /// that); `primaryClientId` is the trimmed client id, `null` when blank
    /// or absent.
    record ResolvedScope(ScopeType scope, String primaryClientId) {
    }

    /// What [#mapDomain] did to one domain's mapping.
    ///
    /// @param created a fresh mapping was created for the domain
    /// @param claimed the domain's existing mapping was re-pointed from another provider
    /// @param linked  the mapping gained a primary client from this call — either a brand-new
    ///                `CLIENT`-scoped mapping, or an existing mapping (claimed or already routed
    ///                here) that had no client yet
    record MapResult(boolean created, boolean claimed, boolean linked) {
        static final MapResult NONE = new MapResult(false, false, false);
    }

    /// Trims, lower-cases and validates every listed domain through the
    /// mapping aggregate's one parser, skipping blank entries and dropping
    /// duplicates (first position wins). `null` → empty (spec §4).
    ///
    /// @throws UseCaseException validation `INVALID_EMAIL_DOMAIN`
    static List<EmailDomain> normalise(List<String> raw) {
        if (raw == null) return List.of();
        var out = new LinkedHashSet<EmailDomain>();
        for (String d : raw) {
            if (d == null || d.isBlank()) continue;
            out.add(EmailDomain.parse(d));
        }
        return List.copyOf(out);
    }

    /// Validates the `mappingScope` / `primaryClientId` contract shared by
    /// create and update (spec §4 "Validation"), checked in this order:
    ///
    /// 1. a `primaryClientId` without a `mappingScope` is rejected outright — the caller must
    ///    say which scope the client is being linked under;
    /// 2. `mappingScope`, when set, must parse (case-insensitively) to `ANCHOR` or `CLIENT` —
    ///    `PARTNER` mappings are managed on the email-domain page, not here;
    /// 3. `CLIENT` requires a non-blank `primaryClientId`;
    /// 4. `ANCHOR` forbids a `primaryClientId`.
    ///
    /// @throws UseCaseException validation `MAPPING_SCOPE_REQUIRED` / `INVALID_MAPPING_SCOPE` / `PRIMARY_CLIENT_REQUIRED` / `PRIMARY_CLIENT_NOT_ALLOWED`
    static ResolvedScope validateScope(String mappingScope, String primaryClientId) {
        String clientId = primaryClientId == null || primaryClientId.isBlank() ? null : primaryClientId.trim();
        if (mappingScope == null) {
            if (clientId != null) {
                throw UseCaseException.validation("MAPPING_SCOPE_REQUIRED",
                        "mappingScope is required when primaryClientId is set");
            }
            return new ResolvedScope(null, null);
        }
        ScopeType scope = switch (mappingScope.strip().toUpperCase(Locale.ROOT)) {
            case "ANCHOR" -> ScopeType.ANCHOR;
            case "CLIENT" -> ScopeType.CLIENT;
            default -> throw UseCaseException.validation("INVALID_MAPPING_SCOPE",
                    "mappingScope must be ANCHOR or CLIENT; partner mappings are managed on the email-domain page");
        };
        if (scope == ScopeType.CLIENT && clientId == null) {
            throw UseCaseException.validation("PRIMARY_CLIENT_REQUIRED",
                    "primaryClientId is required when mappingScope is CLIENT");
        }
        if (scope == ScopeType.ANCHOR && clientId != null) {
            throw UseCaseException.validation("PRIMARY_CLIENT_NOT_ALLOWED",
                    "primaryClientId is not allowed when mappingScope is ANCHOR");
        }
        return new ResolvedScope(scope, clientId);
    }

    /// Fails fast — before any row in this request is written — when `scope`
    /// is `null` and any of `domains` has no existing mapping. A `null`
    /// scope is only legal when every domain already routes somewhere
    /// (claims and no-op links need no scope choice); spec §4 "Require a
    /// scope for new domains".
    ///
    /// @throws UseCaseException validation `MAPPING_SCOPE_REQUIRED`
    void requireScopeForNewDomains(List<EmailDomain> domains, ScopeType scope) {
        if (scope != null) return;
        for (var d : domains) {
            if (mappings.findByEmailDomain(d.value()).isEmpty()) {
                throw UseCaseException.validation("MAPPING_SCOPE_REQUIRED",
                        "mappingScope is required: domain '" + d.value() + "' has no mapping yet; choose ANCHOR or CLIENT");
            }
        }
    }

    /// Routes `domain` to `target` inside the open transaction (spec §4
    /// "Mapping one domain to an IdP"): unknown domain → new mapping with
    /// `scope` (`primaryClientId` set only when `scope` is `CLIENT`);
    /// already routed here → scope untouched, the client is linked only
    /// when the command gives one and the mapping has none; routed
    /// elsewhere → claimed (the client link is filled only when the command
    /// gives one and the mapping has none), then moved. `scope` is only
    /// read when a new mapping is created — the caller
    /// ([#requireScopeForNewDomains]) guarantees it is non-null whenever
    /// this call would create one; a mapping's existing scope is never
    /// changed, and an existing client link is never overwritten.
    ///
    /// @throws UseCaseException internal `INVARIANT_MAPPING_SCOPE` if a new mapping is reached with no resolved scope
    MapResult mapDomain(TxScopedUnitOfWork scoped, IdentityProvider target, EmailDomain domain, ScopeType scope,
                        String primaryClientId, ExecutionContext ec, Object auditCommand) {
        var existing = mappings.findByEmailDomain(domain.value());
        if (existing.isEmpty()) {
            if (scope == null) {
                // Invariant: requireScopeForNewDomains must have already rejected this request before any write happened.
                throw UseCaseException.internal("INVARIANT_MAPPING_SCOPE",
                        "mapDomain reached a new mapping with no resolved scope for domain '" + domain.value() + "'", null);
            }
            var m = EmailDomainMapping.create(domain, target.id(), scope);
            if (scope == ScopeType.CLIENT) {
                m = m.withPrimaryClientId(primaryClientId);
            }
            scoped.commit(m, mappings, EmailDomainMappingCreated.of(ec, m), auditCommand);
            return new MapResult(true, false, scope == ScopeType.CLIENT);
        }
        var m = existing.get();
        if (m.identityProviderId().equals(target.id())) {
            // Already routed here: scope untouched; link the client only if it is missing.
            if (primaryClientId == null || m.primaryClientId() != null) {
                return MapResult.NONE;
            }
            var linked = m.withPrimaryClientId(primaryClientId);
            scoped.commit(linked, mappings, EmailDomainMappingUpdated.of(ec, linked), auditCommand);
            return new MapResult(false, false, true);
        }
        // Claim the domain: link the client only when the mapping has none, then re-point through the shared move behaviour.
        boolean linked = false;
        if (primaryClientId != null && m.primaryClientId() == null) {
            m = m.withPrimaryClientId(primaryClientId);
            linked = true;
        }
        moveTo(scoped, m, target, ec, auditCommand); // the converted-user count is not reported for claims (spec §4)
        return new MapResult(false, true, linked);
    }

    /// Re-points `mapping` to `target`, emits the mapping aggregate's
    /// `provider-changed`, and — only when the target authenticates with
    /// passwords — converts the domain's OIDC-provisioned users back to
    /// internal auth. Returns how many users were converted (spec §4).
    int moveTo(TxScopedUnitOfWork scoped, EmailDomainMapping mapping, IdentityProvider target, ExecutionContext ec,
               Object auditCommand) {
        var moved = mapping.moveToProvider(target.id());
        scoped.commit(moved, mappings, EmailDomainMappingProviderChanged.of(ec, moved, mapping.identityProviderId()), auditCommand);
        return target.isInternalType() ? mappings.resetOidcUsersToInternal(moved.emailDomain(), scoped.dbTx()) : 0;
    }

    /// The mappings currently routed to `identityProviderId`.
    List<EmailDomainMapping> routedTo(String identityProviderId) {
        return mappings.findByIdentityProvider(identityProviderId);
    }
}
