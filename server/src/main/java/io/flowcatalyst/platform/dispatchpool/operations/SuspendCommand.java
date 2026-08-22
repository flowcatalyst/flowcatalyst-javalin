package io.flowcatalyst.platform.dispatchpool.operations;

/// The input DTO for [SuspendDispatchPool] (audit `operation` = `SuspendCommand`).
public record SuspendCommand(String id) {
}
