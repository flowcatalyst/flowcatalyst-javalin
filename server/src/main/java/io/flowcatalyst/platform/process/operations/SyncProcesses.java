package io.flowcatalyst.platform.process.operations;

import io.flowcatalyst.platform.process.Process;
import io.flowcatalyst.platform.process.ProcessRepository;
import io.flowcatalyst.platform.process.ProcessSource;
import io.flowcatalyst.platform.process.operations.ProcessEvents.ProcessCreated;
import io.flowcatalyst.platform.process.operations.ProcessEvents.ProcessDeleted;
import io.flowcatalyst.platform.process.operations.ProcessEvents.ProcessUpdated;
import io.flowcatalyst.platform.process.operations.ProcessEvents.ProcessesSynced;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.Checks;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.jdbc.SyncDelete;
import io.flowcatalyst.sdk.usecase.jdbc.SyncSave;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/// Bulk-upserts an application's process catalogue in one transaction
/// (spec §7): existing sync-managed (`API` / `CODE`) codes are overwritten
/// declaratively, new codes are created `API`-sourced, `UI`-authored rows are
/// never touched, and with `removeUnlisted` the sync-managed rows not in the
/// batch are deleted. One per-row event per row touched plus one
/// [ProcessesSynced] rollup.
///
/// Authorization is resource-level here: the caller must have access to the
/// target application (the handler's coarse "may sync processes" gate and
/// the `{appCode}` resolution are separate).
public final class SyncProcesses {

    private SyncProcesses() {
    }

    public static Operation<SyncProcessesCommand, ProcessesSynced> of(ProcessRepository repo) {
        return Operation.<SyncProcessesCommand, ProcessesSynced>named("SyncProcesses")
                .validate(cmd -> UseCaseException.requireNonBlank(cmd.applicationCode(),
                        "APPLICATION_CODE_REQUIRED", "Application code is required"))
                .authorize(cmd -> Checks.checkApplicationAccess(Auth.current(), cmd.applicationId(), cmd.applicationCode()))
                .execute((cmd, ec) -> {
                    Map<String, Process> existingByCode = repo.findByApplication(cmd.applicationCode()).stream()
                            .collect(Collectors.toMap(Process::code, Function.identity(), (a, _) -> a, LinkedHashMap::new));
                    Set<String> incomingCodes = cmd.processes().stream()
                            .map(SyncProcessInput::code).collect(Collectors.toSet());

                    var saves = new ArrayList<SyncSave<Process>>(cmd.processes().size());
                    var deletes = new ArrayList<SyncDelete<Process>>();
                    int created = 0;
                    int updated = 0;
                    for (SyncProcessInput in : cmd.processes()) {
                        Process existing = existingByCode.get(in.code());
                        if (existing != null) {
                            if (!existing.source().isSyncManaged()) {
                                continue; // never touch UI-authored rows
                            }
                            Process p = existing.syncedFrom(in.name(), in.description(), in.body(), in.diagramType(), in.tags());
                            saves.add(new SyncSave<>(p, ProcessUpdated.of(ec, p)));
                            updated++;
                        } else {
                            Process p = fromSync(in);
                            saves.add(new SyncSave<>(p, ProcessCreated.of(ec, p)));
                            created++;
                        }
                    }
                    if (cmd.removeUnlisted()) {
                        existingByCode.values().stream()
                                .filter(p -> p.source().isSyncManaged() && !incomingCodes.contains(p.code()))
                                .forEach(p -> deletes.add(new SyncDelete<>(p, ProcessDeleted.of(ec, p))));
                    }

                    List<String> syncedCodes = cmd.processes().stream().map(SyncProcessInput::code).toList();
                    var rollup = ProcessesSynced.of(ec, cmd.applicationCode(), created, updated, deletes.size(), syncedCodes);
                    return Plan.sync(repo, saves, deletes, rollup);
                });
    }

    /// A new `API`-sourced process for a batch row. A malformed code names
    /// the offending row: the batch aborts on the first one, and a bare
    /// format message gives no clue which of N codes failed.
    private static Process fromSync(SyncProcessInput in) {
        try {
            return Process.create(in.code(), in.name())
                    .withSource(ProcessSource.API)
                    .withDescription(in.description())
                    .withBody(in.body())
                    .withDiagramType(in.diagramType())
                    .withTags(in.tags());
        } catch (UseCaseException e) {
            throw UseCaseException.validation("INVALID_PROCESS_CODE",
                    e.error().message() + " (offending code: \"" + in.code() + "\")");
        }
    }
}
