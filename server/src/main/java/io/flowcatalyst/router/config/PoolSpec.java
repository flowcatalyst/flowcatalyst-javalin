package io.flowcatalyst.router.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.flowcatalyst.router.pool.Pool;

/// A pool as the **configuration document** describes it — the shape the
/// spec calls `PoolConfig` (`docs/spec/router.md` §2.5).
///
/// Deliberately not [Pool.Config], which is the *runtime* shape and insists
/// on a real concurrency. Here `0` is legal and meaningful: it means "choose
/// one for me". Collapsing the two types would make that unrepresentable —
/// and did, until a mutation test showed the derivation below was
/// unreachable because the value could never arrive.
///
/// @param code               pool identity
/// @param concurrency        simultaneous deliveries; `0` means derive from
///                           the rate limit
/// @param rateLimitPerMinute requests per minute; `0` or absent is unlimited
@JsonIgnoreProperties(ignoreUnknown = true)
public record PoolSpec(String code, int concurrency, int rateLimitPerMinute) {

    public PoolSpec {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("pool code is required");
        }
        concurrency = Math.max(concurrency, 0);
        rateLimitPerMinute = Math.max(rateLimitPerMinute, 0);
    }

    /// The runtime configuration this describes.
    ///
    /// A stated concurrency is used as given. An unstated one is derived from
    /// the rate limit: a pool allowed 60 requests a minute has no use for 20
    /// workers, which would spend their time queued on the limiter. At least
    /// one, so a pool always makes progress — including an unlimited pool
    /// that named no concurrency, which would otherwise derive zero.
    public Pool.Config toRuntime() {
        return new Pool.Config(code,
                concurrency > 0 ? concurrency : Math.max(rateLimitPerMinute / 60, 1),
                rateLimitPerMinute);
    }

    /// Whether the document actually stated a concurrency. A reconfigure
    /// leaves a running pool's concurrency alone when it did not — a config
    /// that says nothing is not asking for nothing.
    public boolean statesConcurrency() {
        return concurrency > 0;
    }
}
