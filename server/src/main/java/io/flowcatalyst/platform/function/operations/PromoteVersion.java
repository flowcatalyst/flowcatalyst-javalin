package io.flowcatalyst.platform.function.operations;

import io.flowcatalyst.platform.function.Function;
import io.flowcatalyst.platform.function.FunctionRepository;
import io.flowcatalyst.platform.function.FunctionVersion;
import io.flowcatalyst.platform.function.FunctionVersionRepository;
import io.flowcatalyst.platform.function.operations.FunctionEvents.AliasChanged;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

import java.time.Instant;
import java.util.Objects;

/// Promotes a version to an alias — today always `live` (spec
/// `function-api.md` §5.2, R3). `Authorize: Public` — load-or-404 + reach is
/// [Access#byAddress], run in `execute`.
///
/// R3's own guard lives HERE, not in [Function#promote]: only a `PUBLISHED`
/// (not yet ready) version is rejected with `VERSION_NOT_READY` — a
/// `RETIRED` version is NOT `Ready` either, but it must fall through to
/// [Function#promote]'s own `VERSION_RETIRED` check instead (spec §8 P12:
/// "promote back to retired v1 ⇒ 409 VERSION_RETIRED", not `VERSION_NOT_READY`).
/// `Function.promote` then owns its own errors: `VERSION_RETIRED`,
/// `FUNCTION_DISABLED`, `ALIAS_UNCHANGED`.
public final class PromoteVersion {

    private PromoteVersion() {
    }

    public static Operation<PromoteCommand, AliasChanged> of(FunctionRepository functions, FunctionVersionRepository versions) {
        Objects.requireNonNull(functions, "functions");
        Objects.requireNonNull(versions, "versions");
        return Operation.<PromoteCommand, AliasChanged>named("PromoteVersion")
                .authorize(Operation.Authorize.publicAccess())
                .execute((cmd, ec) -> {
                    Function f = Access.byAddress(functions, cmd.address(), Auth.current());
                    FunctionVersion v = versions.findByFunctionAndVersion(f.id(), cmd.version())
                            .orElseThrow(() -> UseCaseException.resourceNotFound("FunctionVersion",
                                    f.address().render() + "#" + cmd.version()));

                    // R3: only "not yet ready" is this operation's own guard — a RETIRED version
                    // is deliberately left to Function.promote's own VERSION_RETIRED check below.
                    if (v.state() instanceof FunctionVersion.VersionState.Published) {
                        throw UseCaseException.conflict("VERSION_NOT_READY",
                                "version " + v.version() + " has not been verified by any host in pool '"
                                        + v.manifest().pool().value() + "' yet");
                    }

                    Instant now = Instant.now();
                    Function.Promoted promoted = f.promote(cmd.alias(), v, ec.principalId(), now);
                    AliasChanged event = AliasChanged.of(ec, promoted.function(), cmd.alias(), v, promoted.previousVersionId());
                    return Plan.save(promoted.function(), functions, event);
                });
    }
}
