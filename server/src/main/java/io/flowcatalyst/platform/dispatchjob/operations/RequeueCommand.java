package io.flowcatalyst.platform.dispatchjob.operations;

import java.util.List;

/// The input DTO for [RequeueDispatchJobs] (audit `operation` =
/// `RequeueCommand`): the dispatch-job ids to reset to `PENDING`. `null`
/// means the body carried no `ids` and is rejected in validation; an empty
/// list is a legal no-op.
public record RequeueCommand(List<String> ids) {
    public RequeueCommand {
        ids = ids == null ? null : List.copyOf(ids);
    }
}
