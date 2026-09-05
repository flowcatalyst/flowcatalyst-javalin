package io.flowcatalyst.platform.authadmin.operations;

import io.flowcatalyst.platform.authadmin.IdpRoleMapping;
import io.flowcatalyst.platform.authadmin.IdpRoleMappingRepository;
import io.flowcatalyst.platform.authadmin.operations.AuthAdminEvents.IdpRoleMappingCreated;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// Creates an IdP role mapping (unique by `idpRoleName`) and emits
/// [IdpRoleMappingCreated] (spec §4.3). Fields are stored as given — no
/// normalisation. A duplicate `idpRoleName` is pre-checked and answered as
/// `MAPPING_EXISTS` (spec §8 D1 — a deliberate deviation from Go, which has
/// no pre-check and answers 500 on the UNIQUE violation).
public final class CreateIdpRoleMapping {

    private CreateIdpRoleMapping() {
    }

    public static Operation<CreateIdpRoleMappingCommand, IdpRoleMappingCreated> of(IdpRoleMappingRepository repo) {
        return Operation.<CreateIdpRoleMappingCommand, IdpRoleMappingCreated>named("CreateIdpRoleMapping")
                .validate(cmd -> {
                    // First missing field wins, in this order (spec §4.3).
                    UseCaseException.requireNonBlank(cmd.idpType(), "FIELD_REQUIRED", "idpType is required");
                    UseCaseException.requireNonBlank(cmd.idpRoleName(), "FIELD_REQUIRED", "idpRoleName is required");
                    UseCaseException.requireNonBlank(cmd.platformRoleName(), "FIELD_REQUIRED", "platformRoleName is required");
                })
                // IdP role mappings are anchor-only with no per-resource dimension; the handler's requireAnchor is the whole check.
                .authorize(Operation.Authorize.publicAccess())
                .execute((cmd, ec) -> {
                    if (repo.findByIdpRoleName(cmd.idpRoleName()).isPresent()) {
                        throw UseCaseException.conflict("MAPPING_EXISTS", "IdP role mapping for '" + cmd.idpRoleName() + "' already exists");
                    }
                    IdpRoleMapping m = IdpRoleMapping.create(cmd.idpType(), cmd.idpRoleName(), cmd.platformRoleName());
                    return Plan.save(m, repo, IdpRoleMappingCreated.of(ec, m));
                });
    }
}
