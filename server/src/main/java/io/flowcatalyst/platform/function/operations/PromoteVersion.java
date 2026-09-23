package io.flowcatalyst.platform.function.operations;

import io.flowcatalyst.platform.function.Function;
import io.flowcatalyst.platform.function.FunctionRepository;
import io.flowcatalyst.platform.function.FunctionSettingsRepository;
import io.flowcatalyst.platform.function.FunctionVersion;
import io.flowcatalyst.platform.function.FunctionVersionRepository;
import io.flowcatalyst.platform.function.Manifest;
import io.flowcatalyst.platform.function.operations.FunctionEvents.AliasChanged;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.TxOperation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/// Promotes a version to an alias — `live` or any named alias matching
/// `fn_aliases`' check constraint (spec `function-zones-and-aliases.md` §2,
/// R3). `Authorize: Public` — load-or-404 + reach is [Access#byAddress], run
/// in `execute`.
///
/// R3's own guard lives HERE, not in [Function#promote]: only a `PUBLISHED`
/// (not yet ready) version is rejected with `VERSION_NOT_READY` — a
/// `RETIRED` version is NOT `Ready` either, but it must fall through to
/// [Function#promote]'s own `VERSION_RETIRED` check instead (spec §8 P12:
/// "promote back to retired v1 ⇒ 409 VERSION_RETIRED", not `VERSION_NOT_READY`).
/// R3 applies identically to `live` and to a named alias — spec §2: "the
/// same R3 rule — a host has verified the artifact". `Function.promote` then
/// owns its own errors: `VERSION_RETIRED`, `FUNCTION_DISABLED`,
/// `ALIAS_UNCHANGED`.
///
/// Review fix, slice B3: alias-name validity is checked in `validate` —
/// BEFORE authorize/execute ever load the function or its version — so `PUT
/// …/aliases/BAD` on an unready version is 400 `ALIAS_INVALID`, not 409
/// `VERSION_NOT_READY`; [Function#requireValidAliasName] is the rule's one
/// home, called again inside [Function#promote] itself so the two paths can
/// never disagree and the message is written once.
///
/// **Wiring is `live`-only** (spec §2: "no wiring change — HTTP-only by
/// ruling"): [TriggerSync#onPromote] is called below ONLY when `cmd.alias()`
/// is [Function#LIVE] — never for a named alias. This is not merely an
/// optimisation: `onPromote` reconciles subscriptions/schedules/pool/public-
/// routes from the manifest of the version just handed to it, so calling it
/// with a named alias's version would materialise THAT version's wiring as
/// if it were live, which is exactly the wiring change the ruling forbids.
/// The mutant this guards against: dropping the `LIVE.equals` guard and
/// running the seam for every alias — pinned by
/// `FunctionTriggerSyncTest#promotingANamedAliasRunsNoWiringAndLeavesLiveAndWiringUnchanged`.
public final class PromoteVersion {

    private PromoteVersion() {
    }

    /// A [TxOperation] (not the single-aggregate [Operation] this package's
    /// other by-address operations use) so `TriggerSync.onPromote`'s
    /// reconciliation of the function's subscriptions/pool/scheduled jobs
    /// runs in the SAME transaction as the alias change (spec
    /// `function-invocation.md` §4: "reconcile ... inside the promote
    /// transaction") — a trigger write that cannot be honoured rolls the
    /// alias change back too.
    public static TxOperation<PromoteCommand, AliasChanged> of(FunctionRepository functions,
            FunctionVersionRepository versions, TriggerSync triggerSync, FunctionSettingsRepository settings) {
        Objects.requireNonNull(functions, "functions");
        Objects.requireNonNull(versions, "versions");
        Objects.requireNonNull(triggerSync, "triggerSync");
        Objects.requireNonNull(settings, "settings");
        return TxOperation.<PromoteCommand, AliasChanged>named("PromoteVersion")
                .validate(cmd -> Function.requireValidAliasName(cmd.alias()))
                .authorize(Operation.Authorize.publicAccess())
                .execute((scoped, cmd, ec) -> {
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

                    requireSettingsPresent(settings, f.id(), v.manifest());

                    Instant now = Instant.now();
                    Function.Promoted promoted = f.promote(cmd.alias(), v, ec.principalId(), now);
                    AliasChanged event = AliasChanged.of(ec, promoted.function(), cmd.alias(), v, promoted.previousVersionId());
                    scoped.commit(promoted.function(), functions, event, cmd);

                    // Named-alias promote is HTTP-only (spec §2) — never reaches TriggerSync at all.
                    if (Function.LIVE.equals(cmd.alias())) {
                        FunctionVersion previousLive = promoted.previousVersionId() == null ? null
                                : versions.findById(promoted.previousVersionId()).orElse(null);
                        triggerSync.onPromote(scoped, promoted.function(), v, previousLive, ec);
                    }

                    return event;
                });
    }

    /// spec `function-context.md` §1: every key `v`'s manifest declares —
    /// `config`, `secrets`, and each `db[].secretRef` — must have a value,
    /// checked as three independent sources (a candidate may be missing some
    /// of one and none of another) and reported together, naming every
    /// missing key from all three, or none is thrown at all.
    ///
    /// @throws UseCaseException conflict `SETTINGS_MISSING`
    private static void requireSettingsPresent(FunctionSettingsRepository settings, String functionId, Manifest manifest) {
        Set<String> configured = settings.configMap(functionId).keySet();
        Set<String> secretKeys = settings.secretKeySet(functionId);

        List<String> missing = new ArrayList<>();
        for (String key : manifest.config()) {
            if (!configured.contains(key)) {
                missing.add(key);
            }
        }
        for (String key : manifest.secrets()) {
            if (!secretKeys.contains(key)) {
                missing.add(key);
            }
        }
        for (Manifest.DbRef ref : manifest.db()) {
            if (!secretKeys.contains(ref.secretRef())) {
                missing.add(ref.secretRef());
            }
        }
        if (!missing.isEmpty()) {
            // De-duplicate (a name can legitimately appear in more than one source)
            // while keeping the first-seen order, so the message is stable.
            List<String> named = List.copyOf(new LinkedHashSet<>(missing));
            throw UseCaseException.conflict("SETTINGS_MISSING",
                    "the following config/secret keys have no value set: " + String.join(", ", named));
        }
    }
}
