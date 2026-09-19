package io.flowcatalyst.platform.function.operations;

import io.flowcatalyst.platform.function.Function;
import io.flowcatalyst.platform.function.FunctionAddress;
import io.flowcatalyst.platform.function.FunctionOwner;
import io.flowcatalyst.platform.function.FunctionRepository;
import io.flowcatalyst.platform.shared.auth.AuthContext;
import io.flowcatalyst.platform.shared.auth.Checks;
import io.flowcatalyst.sdk.usecase.UseCaseException;

/// Load-or-404 + reach for an existing function (spec `function-api.md` §2):
/// owner `Client(id)` ⇒ the caller must reach that client; owner `Platform`
/// ⇒ the caller must be anchor-scoped; either way the caller must also reach
/// the function's OWNING APPLICATION (an application-scoped service account
/// publishes only its own application's functions).
///
/// Deliberately does **not** go through `Checks.requireAnchor` /
/// `Checks.checkScopeAccess` / `Checks.checkApplicationAccess` — those throw
/// a 403, and spec §2 is explicit that a function out of reach is 404, on a
/// read AND on every write: "never 403, which would confirm the address".
/// [#canReach] is the one predicate both this class's [#requireReach] (writes,
/// from `execute`, after the load) and `api.FunctionApi`'s read handlers
/// (GET one; GET list uses its own SQL-level reach instead, spec §4.2) share,
/// so the two paths can never drift on what "out of reach" means.
public final class Access {

    private Access() {
    }

    /// @throws UseCaseException not-found `Function_NOT_FOUND` when the row
    ///                          is absent, or present but out of reach
    public static Function byAddress(FunctionRepository repo, FunctionAddress address, AuthContext ac) {
        Function f = repo.findByAddress(address).orElseThrow(() -> notFound(address));
        requireReach(ac, f);
        return f;
    }

    /// @throws UseCaseException not-found `Function_NOT_FOUND` when `f` is out of reach
    public static void requireReach(AuthContext ac, Function f) {
        if (!canReach(ac, f)) {
            throw notFound(f.address());
        }
    }

    /// Whether `ac` may act on / see `f`: reach to the owner, AND reach to
    /// the owning application. `null` (unauthenticated) never reaches
    /// anything.
    public static boolean canReach(AuthContext ac, Function f) {
        if (ac == null) {
            return false;
        }
        boolean ownerReach = switch (f.owner()) {
            case FunctionOwner.Platform ignored -> ac.isAnchor();
            case FunctionOwner.Client(String clientId) -> Checks.canAccessScope(ac, clientId);
        };
        return ownerReach && ac.canAccessApplication(f.applicationId());
    }

    private static UseCaseException notFound(FunctionAddress address) {
        return UseCaseException.resourceNotFound("Function", address.render());
    }
}
