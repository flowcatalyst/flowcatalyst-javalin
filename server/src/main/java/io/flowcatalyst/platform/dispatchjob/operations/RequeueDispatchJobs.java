package io.flowcatalyst.platform.dispatchjob.operations;

import io.flowcatalyst.platform.dispatchjob.DispatchJob;
import io.flowcatalyst.platform.dispatchjob.DispatchJobRepository;
import io.flowcatalyst.platform.dispatchjob.operations.DispatchJobEvents.DispatchJobRequeued;
import io.flowcatalyst.platform.dispatchjob.operations.DispatchJobEvents.DispatchJobsRequeued;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.AuthContext;
import io.flowcatalyst.platform.shared.auth.Checks;
import io.flowcatalyst.sdk.tsid.Tsid;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.jdbc.SyncSave;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/// The operator's "resend" (spec §2.1, §6): resets the given jobs to
/// `PENDING` with a full retry budget ([DispatchJob#requeue]) so the
/// scheduler re-dispatches them — one [DispatchJobRequeued] per row plus the
/// [DispatchJobsRequeued] rollup, all in the transaction that writes the rows.
///
/// Unknown ids and rows outside the caller's scope are skipped silently and
/// not counted (spec §5, open question 3) — which is why `authorize` is
/// [Operation.Authorize#publicAccess()]: the per-row check happens in
/// `execute` with the platform's one scope predicate, [Checks#canAccessScope].
public final class RequeueDispatchJobs {

    private RequeueDispatchJobs() {
    }

    public static Operation<RequeueCommand, DispatchJobsRequeued> of(DispatchJobRepository repo) {
        return Operation.<RequeueCommand, DispatchJobsRequeued>named("RequeueDispatchJobs")
                .validate(cmd -> {
                    if (cmd.ids() == null) {
                        throw UseCaseException.validation("IDS_REQUIRED", "ids is required");
                    }
                })
                .authorize(Operation.Authorize.publicAccess()) // per-row scope filter in execute (spec §5)
                .execute((cmd, ec) -> {
                    AuthContext ac = Auth.current();
                    List<String> ids = List.copyOf(new LinkedHashSet<>(cmd.ids()));
                    Map<String, DispatchJob> loaded = repo.findByIds(ids).stream()
                            .collect(Collectors.toMap(DispatchJob::id, Function.identity()));
                    var saves = new ArrayList<SyncSave<DispatchJob>>(ids.size());
                    var requeued = new ArrayList<String>(ids.size());
                    for (String id : ids) { // request order, so the rollup lists ids as asked
                        DispatchJob before = loaded.get(id);
                        if (before == null || !Checks.canAccessScope(ac, before.clientId())) continue;
                        saves.add(new SyncSave<>(before.requeue(), DispatchJobRequeued.of(ec, before)));
                        requeued.add(before.id());
                    }
                    return Plan.sync(repo, saves, List.of(), DispatchJobsRequeued.of(ec, Tsid.generate(), requeued));
                });
    }
}
