package io.flowcatalyst.platform.serviceaccount.operations;

import io.flowcatalyst.platform.principal.Principal;
import io.flowcatalyst.platform.principal.PrincipalRepository;
import io.flowcatalyst.platform.serviceaccount.ServiceAccount;
import io.flowcatalyst.platform.serviceaccount.ServiceAccountRepository;
import io.flowcatalyst.sdk.usecase.UseCaseException;

import java.util.List;
import java.util.function.Function;

/// `POST /api/service-accounts/{id}/token` (spec §8) — not a use-case
/// operation, deliberately: Go's `mintToken` is a bare handler, not wrapped
/// in its envelope either, because minting emits no domain event (spec §6's
/// event list has no "token minted" entry) and the audit side effect must be
/// best-effort in a way the all-or-nothing envelope commit cannot express
/// (see [io.flowcatalyst.platform.serviceaccount.api.ServiceAccountApi] class
/// doc for the audit-row gap this leaves). This class holds the business
/// logic so it stays out of `api/`, per CONVENTIONS, without pretending it is
/// an `Operation`.
///
/// Anchor gate is the caller's job (spec §3: anchor-only). In order:
///
/// 1. token minting wired at all, else 500 `TOKEN`;
/// 2. load the account, unknown → 404;
/// 3. account inactive → 400 `SERVICE_ACCOUNT_INACTIVE`;
/// 4. load the linked `SERVICE` principal, absent → 500 `PRINCIPAL`;
/// 5. principal inactive → 400 `SERVICE_ACCOUNT_INACTIVE` (a different message, spec §8);
/// 6. flatten the principal's roles to a permission ceiling and mint with that scope.
public final class MintServiceAccountToken {

    private MintServiceAccountToken() {
    }

    /// @param accessToken      the minted bearer
    /// @param expiresInSeconds token lifetime
    /// @param permissions      the flattened scope minted into the token, `[]` when nothing was granted
    public record Result(String accessToken, long expiresInSeconds, List<String> permissions) {
    }

    /// @param flattenPermissions resolves role names to a permission ceiling — the same
    ///                           computation the `client_credentials` grant runs. `null`
    ///                           mints with no scope claim (no permission-flattening wired).
    /// @throws UseCaseException internal `TOKEN` (minting not wired), not-found `ServiceAccount_NOT_FOUND`,
    ///                          validation `SERVICE_ACCOUNT_INACTIVE` (account or principal), internal `PRINCIPAL`
    public static Result mint(ServiceAccountRepository saRepo, PrincipalRepository principals,
                              ServiceAccountTokenMinter minter, Function<List<String>, List<String>> flattenPermissions,
                              String serviceAccountId) {
        if (minter == null) {
            throw UseCaseException.internal("TOKEN", "token minting is not wired", null);
        }
        ServiceAccount sa = saRepo.findById(serviceAccountId)
                .orElseThrow(() -> UseCaseException.resourceNotFound("ServiceAccount", serviceAccountId));
        if (!sa.active()) {
            throw UseCaseException.validation("SERVICE_ACCOUNT_INACTIVE",
                    "the service account is deactivated — reactivate it before minting a token");
        }
        Principal principal = principals.findByServiceAccount(sa.id())
                .orElseThrow(() -> UseCaseException.internal("PRINCIPAL",
                        "service account " + sa.id() + " has no linked principal", null));
        if (!principal.active()) {
            throw UseCaseException.validation("SERVICE_ACCOUNT_INACTIVE",
                    "the service account's principal is deactivated");
        }

        List<String> permissions = flattenPermissions == null ? List.of() : flattenPermissions.apply(principal.roleNames());
        var minted = minter.mint(principal, permissions);
        return new Result(minted.accessToken(), minted.expiresInSeconds(), permissions);
    }
}
