package io.flowcatalyst.platform.serviceaccount.operations;

import io.flowcatalyst.platform.client.ClientRepository;
import io.flowcatalyst.platform.principal.Principal;
import io.flowcatalyst.platform.principal.PrincipalRepository;
import io.flowcatalyst.platform.serviceaccount.ServiceAccount;
import io.flowcatalyst.platform.serviceaccount.ServiceAccountRepository;
import io.flowcatalyst.platform.serviceaccount.operations.ServiceAccountEvents.ServiceAccountUpdated;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.TxOperation;

import java.sql.SQLException;

/// Replaces the mutable fields of an existing service account and emits
/// [ServiceAccountUpdated] (spec §4.2). `code` is immutable. When `clientIds`
/// is present on the wire (spec `docs/spec/service-account-reach.md` §1), the
/// linked `SERVICE` principal's client reach is re-derived and persisted in
/// the same transaction — hence [TxOperation] rather than the single-event
/// [Operation] this used to be.
public final class UpdateServiceAccount {

    private UpdateServiceAccount() {
    }

    public static TxOperation<UpdateCommand, ServiceAccountUpdated> of(ServiceAccountRepository repo,
            PrincipalRepository principals, ClientRepository clients) {
        return TxOperation.<UpdateCommand, ServiceAccountUpdated>named("UpdateServiceAccount")
                .validate(cmd -> {
                    UseCaseException.requireNonBlank(cmd.id(), "ID_REQUIRED", "id is required");
                    if (cmd.name() != null && cmd.name().isBlank()) {
                        throw UseCaseException.validation("NAME_REQUIRED", "name cannot be empty");
                    }
                })
                .authorize(Operation.Authorize.publicAccess()) // admin-managed update, no per-client resource check (spec §4.2)
                .execute((scoped, cmd, ec) -> {
                    if (cmd.clientIds() != null) {
                        for (String clientId : cmd.clientIds()) {
                            if (clients.findById(clientId).isEmpty()) {
                                throw UseCaseException.resourceNotFound("Client", clientId);
                            }
                        }
                    }
                    ServiceAccount sa = Access.byId(repo, cmd.id())
                            .update(new ServiceAccount.Changes(cmd.name(), cmd.description(), cmd.scope(), cmd.clientIds(), cmd.webhookCredentials()));
                    ServiceAccountUpdated event = scoped.commit(sa, repo, ServiceAccountUpdated.of(ec, sa), cmd);

                    // Re-derive the linked principal's reach whenever clientIds was given on the
                    // wire (service-account-reach.md §1) — a persistence detail of the update, no
                    // event of its own, exactly like the create path.
                    if (cmd.clientIds() != null) {
                        Principal linked = principals.findByServiceAccount(sa.id())
                                .orElseThrow(() -> UseCaseException.resourceNotFound("Principal", sa.id()));
                        Principal.ClientAssociationChanged reach = linked.withServiceReach(cmd.clientIds());
                        try {
                            // Reach REPLACES the grant set: an account moved from several clients down
                            // to fewer (or none) must not keep the dropped clients' grants — one persist
                            // through the composed writer, even when the new grant list is empty.
                            principals.withClientGrantsReplaced(reach.grantClientIds(), ec.principalId()).persist(reach.principal(), scoped.dbTx());
                        } catch (SQLException e) {
                            throw UseCaseException.internal("PERSIST", "service principal persist failed", e);
                        }
                    }

                    return event;
                });
    }
}
