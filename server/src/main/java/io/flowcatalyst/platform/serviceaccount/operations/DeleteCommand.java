package io.flowcatalyst.platform.serviceaccount.operations;

/// The input DTO for [DeleteServiceAccount] (audit `operation` = `DeleteCommand`).
public record DeleteCommand(String id) {
}
