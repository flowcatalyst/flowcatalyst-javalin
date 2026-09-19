package io.flowcatalyst.platform.function.operations;

import io.flowcatalyst.platform.application.Application;
import io.flowcatalyst.platform.application.ApplicationCode;
import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.platform.client.Client;
import io.flowcatalyst.platform.client.ClientRepository;
import io.flowcatalyst.platform.function.DnsLabel;
import io.flowcatalyst.platform.function.Function;
import io.flowcatalyst.platform.function.FunctionAddress;
import io.flowcatalyst.platform.function.FunctionOwner;
import io.flowcatalyst.platform.function.FunctionRepository;
import io.flowcatalyst.platform.function.Runtime;
import io.flowcatalyst.platform.function.operations.FunctionEvents.FunctionCreated;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.AuthContext;
import io.flowcatalyst.platform.shared.auth.Checks;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// Creates a function under an application, owned by a client or the
/// platform (spec `function-api.md` §4.1, ruling R1/R2).
///
/// Reach is split across two phases, same as `CreateEventType`: `authorize`
/// checks the command's OWN `clientId` field — `Checks.checkScopeAccess`
/// (not `Checks.requireAnchor`, which `CONVENTIONS.md` §3 bans from
/// `operations/`; a `null` clientId already routes it to the anchor-only
/// branch) — a 403 here is fine because nothing exists yet to protect by
/// hiding it as a 404 (spec §2's "never 403" rule is about an EXISTING
/// function, not a create). `execute`, after loading the application, checks
/// `checkApplicationAccess` — it needs the application's id, which is only
/// known once loaded.
public final class CreateFunction {

    private CreateFunction() {
    }

    public static Operation<CreateCommand, FunctionCreated> of(FunctionRepository repo, ApplicationRepository applications,
            ClientRepository clients) {
        return Operation.<CreateCommand, FunctionCreated>named("CreateFunction")
                .validate(cmd -> {
                    UseCaseException.requireNonBlank(cmd.applicationCode(), "APPLICATION_CODE_REQUIRED",
                            "applicationCode is required");
                    DnsLabel.parse("serviceName", cmd.serviceName());
                    DnsLabel.parse("name", cmd.name());
                    Runtime.parseStrict(cmd.runtime());
                })
                .authorize(cmd -> Checks.checkScopeAccess(Auth.current(), blankToNull(cmd.clientId())))
                .execute((cmd, ec) -> {
                    AuthContext ac = Auth.current();
                    ApplicationCode code = ApplicationCode.parse(cmd.applicationCode());
                    Application application = applications.findByCode(code.value())
                            .orElseThrow(() -> UseCaseException.resourceNotFound("Application", code.value()));

                    DnsLabel appLabel = addressableApplicationLabel(application);

                    FunctionOwner owner;
                    String clientId = blankToNull(cmd.clientId());
                    if (clientId != null) {
                        Client client = clients.findById(clientId)
                                .orElseThrow(() -> UseCaseException.resourceNotFound("Client", clientId));
                        owner = FunctionOwner.ofClientId(client.id());
                    } else {
                        owner = new FunctionOwner.Platform();
                    }

                    Checks.checkApplicationAccess(ac, application.id(), application.code());

                    FunctionAddress address = FunctionAddress.of(appLabel,
                            DnsLabel.parse("serviceName", cmd.serviceName()), DnsLabel.parse("name", cmd.name()));
                    if (repo.findByAddress(address).isPresent()) {
                        throw UseCaseException.conflict("FUNCTION_EXISTS",
                                "function '" + address.render() + "' already exists");
                    }

                    Runtime runtime = Runtime.parseStrict(cmd.runtime());
                    Function f = Function.create(application.id(), address, owner, runtime, cmd.description());
                    return Plan.save(f, repo, FunctionCreated.of(ec, f));
                });
    }

    /// R1 (spec §4.1): the application's STORED code must itself be a
    /// [DnsLabel] — an application coded e.g. `logistics_portal` cannot own
    /// a function. Re-parses through the public [DnsLabel#parse] (its
    /// package-private `isValid` is not visible from `operations/`) and
    /// remaps the generic `LABEL_INVALID` to the named code + message R1 wants.
    private static DnsLabel addressableApplicationLabel(Application application) {
        try {
            return DnsLabel.parse("applicationCode", application.code());
        } catch (UseCaseException e) {
            throw UseCaseException.validation("APPLICATION_CODE_NOT_ADDRESSABLE",
                    "application code '" + application.code() + "' cannot be part of a function address: "
                            + "it must be a DNS label (a-z, 0-9, '-'); codes with '_' or longer than 63 characters "
                            + "cannot own functions");
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
