package io.flowcatalyst.platform.auth.login;

import io.flowcatalyst.server.EnvReader;

/// The login brute-force policy numbers (`docs/spec/auth-core.md` §7.2 — the
/// numbers are contract; Go `loginbackoff.Policy`, `PolicyFromEnv`).
///
/// @param freeAttempts     failures allowed with no delay
/// @param baseDelaySecs    delay applied at `freeAttempts + 1`
/// @param maxDelaySecs     cap on the per-pair delay
/// @param globalWindowSecs window for the per-identifier ceiling
/// @param globalCeiling    failures (any IP) in-window that trip the lock
/// @param globalLockSecs   the enforced lock once the ceiling trips (ruling A-19)
public record BackoffPolicy(int freeAttempts, int baseDelaySecs, int maxDelaySecs,
                            long globalWindowSecs, int globalCeiling, long globalLockSecs) {

    public static final BackoffPolicy DEFAULT = new BackoffPolicy(3, 2, 300, 3600, 100, 900);

    public BackoffPolicy {
        if (freeAttempts < 0 || baseDelaySecs < 0 || maxDelaySecs < 0) throw new IllegalArgumentException("negative delay setting");
        if (globalWindowSecs < 1 || globalCeiling < 1 || globalLockSecs < 0) throw new IllegalArgumentException("invalid ceiling setting");
    }

    /// `FC_LOGIN_BACKOFF_FREE_ATTEMPTS`, `FC_LOGIN_BACKOFF_BASE_SECS`,
    /// `FC_LOGIN_BACKOFF_MAX_SECS`, `FC_LOGIN_GLOBAL_WINDOW_SECS`,
    /// `FC_LOGIN_GLOBAL_CEILING`, `FC_LOGIN_GLOBAL_LOCK_SECS`, defaults as
    /// [#DEFAULT]. Go: an unparseable value is the default.
    public static BackoffPolicy fromEnv(EnvReader e) {
        return new BackoffPolicy(
                e.integer("FC_LOGIN_BACKOFF_FREE_ATTEMPTS", DEFAULT.freeAttempts()),
                e.integer("FC_LOGIN_BACKOFF_BASE_SECS", DEFAULT.baseDelaySecs()),
                e.integer("FC_LOGIN_BACKOFF_MAX_SECS", DEFAULT.maxDelaySecs()),
                e.integer("FC_LOGIN_GLOBAL_WINDOW_SECS", (int) DEFAULT.globalWindowSecs()),
                e.integer("FC_LOGIN_GLOBAL_CEILING", DEFAULT.globalCeiling()),
                e.integer("FC_LOGIN_GLOBAL_LOCK_SECS", (int) DEFAULT.globalLockSecs()));
    }

    /// The delay required after `failureCount` failures from one
    /// (identifier, IP) pair since its last success: `0` up to
    /// [#freeAttempts], then `base · 2^(n − free − 1)` capped at
    /// [#maxDelaySecs] (the exponent clamped to 31). Defaults give
    /// 0,0,0,0,2,4,8,16,32,64,128,256,300,300…
    public long delaySecs(long failureCount) {
        if (failureCount <= freeAttempts) {
            return 0;
        }
        long exponent = Math.min(failureCount - freeAttempts - 1, 31);
        long scaled = ((long) baseDelaySecs) << exponent;
        return Math.min(scaled, maxDelaySecs);
    }
}
