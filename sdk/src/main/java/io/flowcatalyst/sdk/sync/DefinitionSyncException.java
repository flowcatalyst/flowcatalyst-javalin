package io.flowcatalyst.sdk.sync;

import io.flowcatalyst.sdk.error.FlowCatalystException;
import io.flowcatalyst.sdk.error.SdkError;
import io.flowcatalyst.sdk.sync.SyncResult.Category;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/// Thrown by [DefinitionSynchronizer#sync], [DefinitionSynchronizer#syncAll]
/// and [DefinitionSynchronizer#syncGrouped] when ANY category of ANY
/// application they synced came back [Category.Failed] — a duplicate code
/// within one sync scope, or a connection sync failure that skipped its
/// scope's subscriptions. Before this exception existed, that case would
/// have returned normally with a `Category.Failed` buried in the result, so
/// a caller that doesn't inspect every category (a deploy step relying on
/// "no exception means it worked") would report success while part of the
/// sync silently did not happen. This exception makes that impossible to
/// miss, while still preserving whatever DID sync: exactly one of
/// [#result()], [#results()] or [#resultsByApplication()] is non-null,
/// matching whichever method threw.
///
/// Every application/scope that COULD run still ran before this is thrown —
/// [DefinitionSynchronizer#syncAll] and [DefinitionSynchronizer#syncGrouped]
/// process every set/application first and throw once at the end, rather
/// than stopping at the first one with a failure. A genuinely uncaught
/// exception from a category that does not catch its own HTTP failures
/// (roles, event types, dispatch pools, principals, processes, scheduled
/// jobs, OpenAPI) still propagates immediately and stops the run, exactly as
/// it always has — only connections and subscriptions collect their own
/// failures into `Category.Failed`.
public final class DefinitionSyncException extends FlowCatalystException {

    private final transient SyncResult result;
    private final transient List<SyncResult> results;
    private final transient Map<String, SyncResult> resultsByApplication;

    private DefinitionSyncException(
            String message, SyncResult result, List<SyncResult> results,
            Map<String, SyncResult> resultsByApplication) {
        super(new SdkError.PartialFailure(message));
        this.result = result;
        this.results = results;
        this.resultsByApplication = resultsByApplication;
    }

    /// The partial result of the [DefinitionSynchronizer#sync] call that
    /// threw this. Null when thrown by `syncAll`/`syncGrouped` instead —
    /// see [#results()] / [#resultsByApplication()].
    public SyncResult result() {
        return result;
    }

    /// The partial, in-order results of the [DefinitionSynchronizer#syncAll]
    /// call that threw this — including every set that synced successfully
    /// before/after the failing one(s). Null when thrown by `sync`/
    /// `syncGrouped` instead.
    public List<SyncResult> results() {
        return results;
    }

    /// The partial results of the [DefinitionSynchronizer#syncGrouped] call
    /// that threw this, keyed by application code — including every
    /// application that synced successfully. Null when thrown by `sync`/
    /// `syncAll` instead.
    public Map<String, SyncResult> resultsByApplication() {
        return resultsByApplication;
    }

    /// Returns `result` unchanged if every category synced or was skipped;
    /// throws naming every failed category and its error otherwise.
    static SyncResult throwIfFailed(SyncResult result) {
        List<String> failures = describeFailures(result.applicationCode(), result);
        if (!failures.isEmpty()) {
            throw new DefinitionSyncException(message(failures), result, null, null);
        }
        return result;
    }

    /// Returns `results` unchanged if every application's every category
    /// synced or was skipped; throws naming every failure across every
    /// application otherwise.
    static List<SyncResult> throwIfAnyFailed(List<SyncResult> results) {
        List<String> failures = new ArrayList<>();
        for (SyncResult result : results) {
            failures.addAll(describeFailures(result.applicationCode(), result));
        }
        if (!failures.isEmpty()) {
            throw new DefinitionSyncException(message(failures), null, List.copyOf(results), null);
        }
        return results;
    }

    /// Returns `results` unchanged if every application's every category
    /// synced or was skipped; throws naming every failure across every
    /// application otherwise.
    static Map<String, SyncResult> throwIfAnyFailed(Map<String, SyncResult> results) {
        List<String> failures = new ArrayList<>();
        results.forEach((app, result) -> failures.addAll(describeFailures(app, result)));
        if (!failures.isEmpty()) {
            throw new DefinitionSyncException(message(failures), null, null, Map.copyOf(results));
        }
        return results;
    }

    private static String message(List<String> failures) {
        return "Definition sync had failures — " + String.join("; ", failures);
    }

    /// `"application "<app>" <category>: <error>"` for every failed category of one result.
    private static List<String> describeFailures(String applicationCode, SyncResult result) {
        List<String> failures = new ArrayList<>();
        describeIfFailed(failures, applicationCode, "roles", result.roles());
        describeIfFailed(failures, applicationCode, "eventTypes", result.eventTypes());
        describeIfFailed(failures, applicationCode, "connections", result.connections());
        describeIfFailed(failures, applicationCode, "subscriptions", result.subscriptions());
        describeIfFailed(failures, applicationCode, "dispatchPools", result.dispatchPools());
        describeIfFailed(failures, applicationCode, "principals", result.principals());
        describeIfFailed(failures, applicationCode, "processes", result.processes());
        describeIfFailed(failures, applicationCode, "scheduledJobs", result.scheduledJobs());
        describeIfFailed(failures, applicationCode, "openapi", result.openapi());
        return failures;
    }

    private static void describeIfFailed(
            List<String> failures, String applicationCode, String category, Category value) {
        if (value instanceof Category.Failed failed) {
            failures.add(String.format(
                    "application \"%s\" %s: %s", applicationCode, category, failed.error()));
        }
    }
}
