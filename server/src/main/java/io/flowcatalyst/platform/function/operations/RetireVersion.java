package io.flowcatalyst.platform.function.operations;

import io.flowcatalyst.platform.function.Function;
import io.flowcatalyst.platform.function.FunctionRepository;
import io.flowcatalyst.platform.function.FunctionVersion;
import io.flowcatalyst.platform.function.FunctionVersionRepository;
import io.flowcatalyst.platform.function.operations.FunctionEvents.VersionRetired;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

import java.time.Instant;
import java.util.Objects;

/// Retires a version (spec `function-api.md` §5.2). `Authorize: Public` —
/// load-or-404 + reach is [Access#byAddress], run in `execute`. The
/// function's LIVE version may never be retired — "promote another version
/// first" — checked via [Function#isLive], which is why this operation loads
/// the function even though it only writes the version aggregate.
public final class RetireVersion {

    private RetireVersion() {
    }

    public static Operation<RetireCommand, VersionRetired> of(FunctionRepository functions, FunctionVersionRepository versions) {
        Objects.requireNonNull(functions, "functions");
        Objects.requireNonNull(versions, "versions");
        return Operation.<RetireCommand, VersionRetired>named("RetireVersion")
                .authorize(Operation.Authorize.publicAccess())
                .execute((cmd, ec) -> {
                    Function f = Access.byAddress(functions, cmd.address(), Auth.current());
                    FunctionVersion v = versions.findByFunctionAndVersion(f.id(), cmd.version())
                            .orElseThrow(() -> UseCaseException.resourceNotFound("FunctionVersion",
                                    f.address().render() + "#" + cmd.version()));

                    if (f.isLive(v.id())) {
                        throw UseCaseException.conflict("VERSION_IS_LIVE", "promote another version first");
                    }

                    FunctionVersion retired = v.retire(Instant.now());
                    VersionRetired event = VersionRetired.of(ec, f, retired);
                    return Plan.save(retired, versions, event);
                });
    }
}
