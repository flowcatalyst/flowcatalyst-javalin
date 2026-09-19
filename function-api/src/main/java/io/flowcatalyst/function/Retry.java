package io.flowcatalyst.function;

import java.time.Duration;

/// Redeliver the invocation after `after`. Built through [Result#retry].
///
/// @param after how long the host should wait before redelivering
public record Retry(Duration after) implements Result {

    public Retry {
        if (after == null || after.isNegative()) {
            throw new IllegalArgumentException("after must be a non-null, non-negative Duration");
        }
    }
}
