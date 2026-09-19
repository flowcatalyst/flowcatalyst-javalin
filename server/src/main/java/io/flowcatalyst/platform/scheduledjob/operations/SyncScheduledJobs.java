package io.flowcatalyst.platform.scheduledjob.operations;

import io.flowcatalyst.platform.scheduledjob.ScheduledJob;
import io.flowcatalyst.platform.scheduledjob.ScheduledJobCode;
import io.flowcatalyst.platform.scheduledjob.ScheduledJobRepository;
import io.flowcatalyst.platform.scheduledjob.ScheduledJobRepository.ClientFilter;
import io.flowcatalyst.platform.scheduledjob.ScheduledJobStatus;
import io.flowcatalyst.platform.scheduledjob.operations.ScheduledJobEvents.ScheduledJobArchived;
import io.flowcatalyst.platform.scheduledjob.operations.ScheduledJobEvents.ScheduledJobCreated;
import io.flowcatalyst.platform.scheduledjob.operations.ScheduledJobEvents.ScheduledJobUpdated;
import io.flowcatalyst.platform.scheduledjob.operations.ScheduledJobEvents.ScheduledJobsSynced;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.AuthContext;
import io.flowcatalyst.platform.shared.auth.Checks;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.jdbc.SyncSave;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/// Declarative sync of an application SDK's scheduled jobs within one
/// client scope, in one transaction (spec §8): a declared code that exists
/// is reconciled ([ScheduledJob#reconcile] — persisted and reported only
/// when something differs, re-activated if it was paused/archived), a new
/// code is created, and with `archiveUnlisted` every `ACTIVE` job in scope
/// **belonging to this sync's application** that is not declared is archived
/// (ledger X-02(a), ruled 2026-09-01 — narrowed from "every job in the
/// client scope": a sibling application's job in the same client must
/// survive a sync that never mentions it). One per-row event per row
/// touched plus one [ScheduledJobsSynced] rollup carrying the affected ids.
///
/// A job named in `cmd.protectedIds()` (a function's own schedule entry,
/// `function-invocation.md` §4.2) is skipped entirely — never reconciled by
/// a matching declared entry, never archived by `archiveUnlisted` — and not
/// counted either way. This operation never learns *why* a job is
/// protected; it only ever sees the id set the sdksync handler hands it.
///
/// Authorization is resource-level on both dimensions: the application the
/// sync is scoped to (`Checks.checkApplicationAccess`) and the target client
/// scope (`checkScopeAccess`) — except a platform scope (`clientId == null`)
/// sync, which X-02(d) refuses outright for a non-anchor caller with the
/// specific `ANCHOR_REQUIRED_FOR_PLATFORM_SWEEP` code, not the generic
/// `SCOPE_FORBIDDEN` [Checks#checkScopeAccess] would raise. The coarse sync
/// permission and the `appCode → id` resolution belong to the sdksync handler.
public final class SyncScheduledJobs {

    private SyncScheduledJobs() {
    }

    public static Operation<SyncScheduledJobsCommand, ScheduledJobsSynced> of(ScheduledJobRepository repo) {
        return Operation.<SyncScheduledJobsCommand, ScheduledJobsSynced>named("SyncScheduledJobs")
                .validate(cmd -> {
                    UseCaseException.requireNonBlank(cmd.applicationCode(), "APPLICATION_CODE_REQUIRED",
                            "Application code is required");
                    cmd.jobs().forEach(SyncScheduledJobs::validateEntry);
                })
                .authorize(cmd -> {
                    Checks.checkApplicationAccess(Auth.current(), cmd.applicationId(), cmd.applicationCode());
                    if (cmd.clientId() == null) {
                        requireAnchorForPlatformSweep(Auth.current());
                    } else {
                        Checks.checkScopeAccess(Auth.current(), cmd.clientId());
                    }
                })
                .execute((cmd, ec) -> {
                    Map<String, ScheduledJob> existingByCode = repo.findInScope(ClientFilter.scope(cmd.clientId())).stream()
                            .collect(Collectors.toMap(ScheduledJob::code, Function.identity(), (a, _) -> a, LinkedHashMap::new));

                    var saves = new ArrayList<SyncSave<ScheduledJob>>(cmd.jobs().size());
                    var created = new ArrayList<String>();
                    var updated = new ArrayList<String>();
                    var archived = new ArrayList<String>();
                    for (ScheduledJobSyncEntry entry : cmd.jobs()) {
                        ScheduledJob existing = existingByCode.remove(entry.code());
                        if (existing != null) {
                            if (cmd.protectedIds().contains(existing.id())) continue; // a function's own job — untouched
                            existing.reconcile(definitionOf(entry), cmd.applicationId(), ec.principalId()).ifPresent(j -> {
                                saves.add(new SyncSave<>(j, ScheduledJobUpdated.of(ec, j)));
                                updated.add(j.id());
                            });
                        } else {
                            ScheduledJob j = ScheduledJob.create(ScheduledJobCode.verbatim(entry.code()), definitionOf(entry))
                                    .withClientId(cmd.clientId())
                                    .withApplicationId(cmd.applicationId())
                                    .withCreatedBy(ec.principalId());
                            saves.add(new SyncSave<>(j, ScheduledJobCreated.of(ec, j)));
                            created.add(j.id());
                        }
                    }
                    if (cmd.archiveUnlisted()) {
                        for (ScheduledJob unlisted : existingByCode.values()) {
                            if (unlisted.status() != ScheduledJobStatus.ACTIVE) continue;
                            // X-02(a): never sweep a sibling application's job just
                            // because this sync's payload omitted it. A job with no
                            // application_id yet (not stamped by any sync) falls back
                            // to the pre-fix client-only scope, same as Go.
                            if (cmd.applicationId() != null && !cmd.applicationId().equals(unlisted.applicationId())) {
                                continue;
                            }
                            if (cmd.protectedIds().contains(unlisted.id())) continue; // a function's own job — never archived by an SDK sync
                            ScheduledJob j = unlisted.archive(ec.principalId());
                            saves.add(new SyncSave<>(j, ScheduledJobArchived.of(ec, j)));
                            archived.add(j.id());
                        }
                    }

                    var rollup = ScheduledJobsSynced.of(ec, cmd.applicationCode(), cmd.clientId(), created, updated, archived);
                    return Plan.sync(repo, saves, List.of(), rollup);
                });
    }

    /// X-02(d): a platform-scope (`clientId == null`) sync sweeps every
    /// client, so it is refused for anyone but an anchor/super-admin, with a
    /// code distinct from the generic per-resource `SCOPE_FORBIDDEN`.
    private static void requireAnchorForPlatformSweep(AuthContext ac) {
        if (ac == null || (!ac.isAnchor() && !ac.isSuperAdmin())) {
            throw UseCaseException.authorization("ANCHOR_REQUIRED_FOR_PLATFORM_SWEEP",
                    "Only anchor users can sync platform-scoped (clientId-less) scheduled jobs");
        }
    }

    /// The entry rules (spec §5): code, name and at least one cron, with one
    /// shared code naming the entry; then every cron must parse.
    private static void validateEntry(ScheduledJobSyncEntry e) {
        if (e.code() == null || e.code().isBlank() || e.name() == null || e.name().isBlank() || e.crons().isEmpty()) {
            throw UseCaseException.validation("INVALID_SYNC_ENTRY",
                    "Sync entry '" + (e.code() == null ? "" : e.code()) + "' must have code, name, and at least one cron");
        }
        Crons.parseAll(e.crons());
    }

    private static ScheduledJob.Definition definitionOf(ScheduledJobSyncEntry e) {
        return new ScheduledJob.Definition(e.name(), e.description(), Crons.parseAll(e.crons()), e.timezone(), e.payload(),
                e.concurrent(), e.tracksCompletion(), e.timeoutSeconds(), e.deliveryMaxAttempts(), e.targetUrl());
    }
}
