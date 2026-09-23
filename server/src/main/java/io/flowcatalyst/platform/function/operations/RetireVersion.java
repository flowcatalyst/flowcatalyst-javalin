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
import java.util.List;
import java.util.Objects;

/// Retires a version (spec `function-api.md` §5.2, `function-zones-and-aliases.md`
/// §2). `Authorize: Public` — load-or-404 + reach is [Access#byAddress], run
/// in `execute`. The function's LIVE version may never be retired —
/// "promote another version first" — checked via [Function#isLive]; a
/// version any NAMED alias still points at may never be retired either —
/// "move or remove them first" — both checks are why this operation loads
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

                    // spec §2 (A3): a NAMED alias still pointing at this version blocks retirement
                    // too — named, plural, so the operator knows exactly what to move first.
                    List<String> pointingAliases = f.aliases().stream()
                            .filter(a -> a.versionId().equals(v.id()))
                            .map(Function.FunctionAlias::alias)
                            .sorted()
                            .toList();
                    if (!pointingAliases.isEmpty()) {
                        throw UseCaseException.conflict("VERSION_ALIASED", "aliases " + String.join(", ", pointingAliases)
                                + " point at this version; move or remove them first");
                    }

                    FunctionVersion retired = v.retire(Instant.now());
                    VersionRetired event = VersionRetired.of(ec, f, retired);
                    return Plan.save(retired, versions, event);
                });
    }
}
