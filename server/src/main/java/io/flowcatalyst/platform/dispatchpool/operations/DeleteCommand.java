package io.flowcatalyst.platform.dispatchpool.operations;

/// The input DTO for [DeleteDispatchPool] (audit `operation` = `DeleteCommand`).
public record DeleteCommand(String id) {
}
