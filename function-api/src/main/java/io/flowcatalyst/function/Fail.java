package io.flowcatalyst.function;

/// The invocation failed and should not be retried on this account. Built
/// through [Result#fail].
///
/// @param reason the audit/metrics detail; never blank
public record Fail(String reason) implements Result {

    public Fail {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
    }
}
