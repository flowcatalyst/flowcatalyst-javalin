package io.flowcatalyst.platform.function.operations;

import io.flowcatalyst.platform.function.FunctionDomain;
import io.flowcatalyst.platform.function.FunctionDomainRepository;
import io.flowcatalyst.platform.function.FunctionRepository;
import io.flowcatalyst.platform.function.FunctionRoute;
import io.flowcatalyst.platform.function.FunctionRouteRepository;
import io.flowcatalyst.platform.function.Hostname;
import io.flowcatalyst.platform.function.operations.FunctionEvents.DomainReleased;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/// Releases (deletes) a claimed hostname (spec `function-public-routes.md`
/// §1) — refused, `DOMAIN_IN_USE`, naming the functions, while `fn_routes`
/// rows exist for it (a function's manifest still names this hostname; the
/// caller must publish/promote it out first, or delete the function).
///
/// `Authorize: Public` — load-or-404 + reach is [Access#byHostname], run in
/// `execute` (`CONVENTIONS.md` §3).
public final class ReleaseFunctionDomain {

    private ReleaseFunctionDomain() {
    }

    public static Operation<ReleaseCommand, DomainReleased> of(FunctionDomainRepository domains,
            FunctionRouteRepository routes, FunctionRepository functions) {
        Objects.requireNonNull(domains, "domains");
        Objects.requireNonNull(routes, "routes");
        Objects.requireNonNull(functions, "functions");
        return Operation.<ReleaseCommand, DomainReleased>named("ReleaseFunctionDomain")
                .authorize(Operation.Authorize.publicAccess()) // load-or-404 + reach is Access.byHostname, below
                .execute((cmd, ec) -> {
                    Hostname hostname = Hostname.parse(cmd.hostname());
                    FunctionDomain d = Access.byHostname(domains, hostname, Auth.current());

                    List<FunctionRoute> using = routes.listByHostname(hostname);
                    if (!using.isEmpty()) {
                        throw UseCaseException.conflict("DOMAIN_IN_USE",
                                "domain is in use by: " + String.join(", ", functionAddresses(functions, using)));
                    }

                    DomainReleased event = DomainReleased.of(ec, d);
                    return Plan.delete(d, domains, event);
                });
    }

    private static List<String> functionAddresses(FunctionRepository functions, List<FunctionRoute> routes) {
        Set<String> functionIds = new LinkedHashSet<>();
        for (FunctionRoute r : routes) {
            functionIds.add(r.functionId());
        }
        return functionIds.stream()
                .map(id -> functions.findById(id).map(f -> f.address().render()).orElse(id))
                .sorted()
                .toList();
    }
}
