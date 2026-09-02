package io.flowcatalyst.platform.dispatchjob.operations;

/// The input DTO for [CompleteDispatchJob] (audit `operation` =
/// `CompleteCommand`): the dispatch-job id to flip `FAILED` → `COMPLETED`
/// (dispatch-seam spec §8).
public record CompleteCommand(String id) {
}
