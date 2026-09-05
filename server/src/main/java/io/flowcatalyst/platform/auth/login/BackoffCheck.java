package io.flowcatalyst.platform.auth.login;

import io.flowcatalyst.platform.loginattempt.LoginAttemptRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/// The login brute-force gate (`docs/spec/auth-core.md` §7.2,
/// `docs/spec/login-backoff-lock.md`; Go `loginbackoff.Check`): a per
/// (identifier, IP) exponential delay, then a per-identifier ceiling that
/// is a **real lock** (ruling A-19).
///
/// The decision is a pure function over `(policy, now, stats)` — the three
/// store reads are gathered by [#check] and handed to [#decide], so the
/// table in the spec's §6 runs without a database.
///
/// Two invariants this depends on, stated so they are not lost:
///
///   - **A denied attempt is never recorded** (spec §4). Every caller returns
///     on a denial before it writes a login-attempt row, so the failure
///     timeline freezes while an identifier is locked and the lock end is a
///     bounded, deterministic instant. Recording denials would turn the lock
///     into a denial-of-service at one attempt per lock window.
///   - **A store error fails closed** (ruling C-Q23). [#check] lets the
///     repository's exception propagate; the login endpoint answers 503.
public final class BackoffCheck {

    /// How far back the per-pair window reaches when the identifier has no
    /// (recent) success: 30 days. A success older than the repository's
    /// 400-day lookback reads as never-succeeded (ruling 2026-09-03).
    public static final Duration NEVER_SUCCEEDED_WINDOW = Duration.ofDays(30);

    /// Why a decision denied.
    public enum Reason { PAIR_BACKOFF, GLOBAL_CEILING }

    /// @param allowed        whether the attempt may proceed
    /// @param retryAfterSecs the honest `Retry-After` when denied (≥ 1); 0 when allowed
    /// @param reason         which gate denied; `null` when allowed
    public record Decision(boolean allowed, long retryAfterSecs, Reason reason) {
        public static final Decision ALLOWED = new Decision(true, 0, null);

        static Decision denied(long retryAfterSecs, Reason reason) {
            return new Decision(false, Math.max(1, retryAfterSecs), reason);
        }
    }

    /// The store reads the decision needs, gathered at one `now`.
    ///
    /// @param lastSuccessAt      the identifier's last SUCCESS within the lookback, if any
    /// @param pairFailures       failures for (identifier, ip) since the cutoff — 0 when no ip
    /// @param pairLastFailureAt  the latest of those, if any
    /// @param ceilingTrippedAt   the ceiling-th most recent failure in the search window, if any —
    ///                           the one whose expiry from the window drops the count below the ceiling
    /// @param globalLastFailureAt the most recent failure (any IP) in the search window — the lock's anchor
    public record Stats(Optional<Instant> lastSuccessAt, int pairFailures, Optional<Instant> pairLastFailureAt,
                        Optional<Instant> ceilingTrippedAt, Optional<Instant> globalLastFailureAt) {
    }

    private final LoginAttemptRepository attempts;
    private final BackoffPolicy policy;

    public BackoffCheck(LoginAttemptRepository attempts, BackoffPolicy policy) {
        this.attempts = Objects.requireNonNull(attempts, "attempts");
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    public BackoffPolicy policy() {
        return policy;
    }

    /// Runs the gate for `identifier` (lower-cased, trimmed) from `ip` (`""`
    /// when unknown — then only the ceiling applies). Any repository failure
    /// propagates: the caller fails closed.
    public Decision check(String identifier, String ip, Instant now) {
        String id = identifier.trim().toLowerCase(Locale.ROOT);
        Optional<Instant> lastSuccess = attempts.lastSuccessAt(id);
        Instant cutoff = lastSuccess.orElse(now.minus(NEVER_SUCCEEDED_WINDOW));

        int pairFailures = 0;
        Optional<Instant> pairLast = Optional.empty();
        if (ip != null && !ip.isBlank()) {
            var pair = attempts.failureStatsSince(id, ip, cutoff);
            pairFailures = Math.max(pair.count(), 0);
            pairLast = Optional.ofNullable(pair.lastFailureAt());
        }

        // Search back far enough to catch any trip whose derived expiry could
        // still be in the future: the ceiling-th failure can sit up to one
        // window before the last failure, and the lock runs one lock length
        // after that — so window + lock, not the larger of the two (Go's
        // choice, which only holds for its own trippedAt-anchored lock).
        long searchWindowSecs = policy.globalWindowSecs() + policy.globalLockSecs();
        Instant searchSince = now.minusSeconds(searchWindowSecs);
        if (cutoff.isAfter(searchSince)) {
            searchSince = cutoff;
        }
        Optional<Instant> tripped = attempts.globalCeilingTrippedAt(id, searchSince, policy.globalCeiling());
        Optional<Instant> lastFailure = tripped.isPresent() ? attempts.lastFailureAt(id, searchSince) : Optional.empty();

        return decide(policy, now, new Stats(lastSuccess, pairFailures, pairLast, tripped, lastFailure));
    }

    /// The pure decision.
    ///
    /// Window 1 — per pair: `required = policy.delaySecs(pairFailures)`; when
    /// `required > 0` and less than `required` seconds have elapsed since
    /// the last pair failure, deny with the remainder.
    ///
    /// Window 2 — the ceiling as a lock (`login-backoff-lock.md` §3): with
    /// `trippedAt` the ceiling-th most recent failure, `countEnds = trippedAt
    /// + window` (when the count next drops below the ceiling on its own) and
    /// `lockEnds = lastFailureAt + lock` (the enforced lock runs from the
    /// **last** failure); deny while `now < max(lockEnds, countEnds)`,
    /// advertising the later of the two — so the lock is enforced, the
    /// hourly ceiling is retained, and `Retry-After` is honest.
    ///
    /// Go anchors both to `trippedAt`, which makes its lock shorter than
    /// `GlobalLockSecs` whenever the ceiling-sized set spans more than the
    /// lock — recorded in `docs/backlog.md`; Java follows the spec.
    public static Decision decide(BackoffPolicy policy, Instant now, Stats s) {
        if (s.pairFailures() > 0) {
            long required = policy.delaySecs(s.pairFailures());
            if (required > 0) {
                Instant last = s.pairLastFailureAt().orElse(now);
                long elapsed = Math.max(Duration.between(last, now).getSeconds(), 0);
                if (elapsed < required) {
                    return Decision.denied(required - elapsed, Reason.PAIR_BACKOFF);
                }
            }
        }
        if (s.ceilingTrippedAt().isPresent()) {
            Instant trippedAt = s.ceilingTrippedAt().get();
            Instant lockEnds = s.globalLastFailureAt().orElse(trippedAt).plusSeconds(policy.globalLockSecs());
            Instant countEnds = trippedAt.plusSeconds(policy.globalWindowSecs());
            Instant end = countEnds.isAfter(lockEnds) ? countEnds : lockEnds;
            if (now.isBefore(end)) {
                long secs = (long) Math.ceil(Duration.between(now, end).toMillis() / 1000.0);
                return Decision.denied(secs, Reason.GLOBAL_CEILING);
            }
        }
        return Decision.ALLOWED;
    }
}
