package io.flowcatalyst.platform.dispatchjob.operations;

/// The input DTO for [CancelDispatchJob] (audit `operation` = `CancelCommand`):
/// the dispatch-job id to flip `FAILED` → `CANCELLED` (dispatch-seam spec §8).
public record CancelCommand(String id) {
}
