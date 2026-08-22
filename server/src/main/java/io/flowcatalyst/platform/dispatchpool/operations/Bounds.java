package io.flowcatalyst.platform.dispatchpool.operations;

import io.flowcatalyst.sdk.usecase.UseCaseException;

/// The numeric bounds on pool settings (spec §4). Two rule sets exist on
/// purpose until the owner rules otherwise (open question 2): the admin
/// create/update accept `rateLimit = 0`, the SDK sync does not.
final class Bounds {

    private Bounds() {
    }

    /// Admin create/update: `concurrency` (when given) ≥ 1, `rateLimit` (when given) ≥ 0.
    ///
    /// @throws UseCaseException validation `INVALID_CONCURRENCY` | `INVALID_RATE_LIMIT`
    static void checkAdmin(Integer concurrency, Integer rateLimit) {
        if (concurrency != null && concurrency < 1) {
            throw UseCaseException.validation("INVALID_CONCURRENCY", "concurrency must be >= 1");
        }
        if (rateLimit != null && rateLimit < 0) {
            throw UseCaseException.validation("INVALID_RATE_LIMIT", "rateLimit cannot be negative");
        }
    }

    /// SDK sync: `rateLimit` (when given) ≥ 1, `concurrency` (when given) ≥ 1.
    ///
    /// @throws UseCaseException validation `INVALID_RATE_LIMIT` | `INVALID_CONCURRENCY`
    static void checkSync(Integer rateLimit, Integer concurrency) {
        if (rateLimit != null && rateLimit < 1) {
            throw UseCaseException.validation("INVALID_RATE_LIMIT", "Rate limit, when set, must be at least 1");
        }
        if (concurrency != null && concurrency < 1) {
            throw UseCaseException.validation("INVALID_CONCURRENCY", "Concurrency must be at least 1");
        }
    }
}
