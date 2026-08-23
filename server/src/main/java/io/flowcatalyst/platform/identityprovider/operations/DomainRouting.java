package io.flowcatalyst.platform.identityprovider.operations;

import io.flowcatalyst.platform.emaildomainmapping.EmailDomain;
import io.flowcatalyst.platform.emaildomainmapping.EmailDomainMapping;
import io.flowcatalyst.platform.emaildomainmapping.EmailDomainMappingRepository;
import io.flowcatalyst.platform.emaildomainmapping.ScopeType;
import io.flowcatalyst.platform.emaildomainmapping.operations.EmailDomainMappingEvents.EmailDomainMappingCreated;
import io.flowcatalyst.platform.emaildomainmapping.operations.EmailDomainMappingEvents.EmailDomainMappingProviderChanged;
import io.flowcatalyst.platform.identityprovider.IdentityProvider;
import io.flowcatalyst.sdk.usecase.ExecutionContext;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.jdbc.TxScopedUnitOfWork;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/// The one place this aggregate touches the email-domain mapping aggregate
/// (spec §4, §9): "route this domain to that provider". Create and update
/// both call [#mapDomain]; update's release path calls [#moveTo]. Every
/// write goes through the mapping repository and emits the mapping
/// aggregate's own events, audited under the identity-provider command that
/// drove them — exactly what the mapping aggregate's own move operation
/// does. Flagged: the "move a mapping" rule restated in [#moveTo] belongs in
/// the mapping package as one shared helper (spec §9).
final class DomainRouting {

    private final EmailDomainMappingRepository mappings;

    DomainRouting(EmailDomainMappingRepository mappings) {
        this.mappings = Objects.requireNonNull(mappings, "mappings");
    }

    /// What mapping a domain produced.
    enum Outcome {
        /// A fresh mapping was created for the domain.
        CREATED,
        /// The domain's existing mapping was re-pointed from another provider.
        CLAIMED,
        /// The domain already routed here; nothing happened.
        UNCHANGED
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

    /// Routes `domain` to `target` inside the open transaction (spec §4):
    /// unknown domain → new mapping (`CLIENT`-scoped when `primaryClientId`
    /// is given, else `ANCHOR`); already routed here → nothing; routed
    /// elsewhere → claimed (the client link is filled only when the command
    /// gives one and the mapping has none), then moved.
    Outcome mapDomain(TxScopedUnitOfWork scoped, IdentityProvider target, EmailDomain domain, String primaryClientId,
                      ExecutionContext ec, Object auditCommand) {
        var existing = mappings.findByEmailDomain(domain.value());
        if (existing.isEmpty()) {
            var m = EmailDomainMapping.create(domain, target.id(), primaryClientId == null ? ScopeType.ANCHOR : ScopeType.CLIENT)
                    .withPrimaryClientId(primaryClientId);
            scoped.commit(m, mappings, EmailDomainMappingCreated.of(ec, m), auditCommand);
            return Outcome.CREATED;
        }
        var m = existing.get();
        if (m.identityProviderId().equals(target.id())) {
            return Outcome.UNCHANGED;
        }
        if (primaryClientId != null && m.primaryClientId() == null) {
            m = m.withPrimaryClientId(primaryClientId);
        }
        moveTo(scoped, m, target, ec, auditCommand); // the converted-user count is not reported for claims (spec §4)
        return Outcome.CLAIMED;
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
