package io.flowcatalyst.platform.purger;

import io.flowcatalyst.platform.loginattempt.LoginAttemptRepository;
import io.flowcatalyst.platform.purger.jfr.PurgerStepEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Objects;

/// The purger (`docs/spec/scheduled-job-scheduler.md` §4): a single tick loop
/// that runs whenever the platform database is open — **not** leader-gated
/// (every instance may purge; the statements are idempotent/`IF EXISTS`, so
/// a duplicate pass from a second instance is harmless), every step
/// independently logged on failure so one table's problem never stops the
/// rest of the pass.
///
/// Only the steps whose tables Java owns today are implemented:
///
///   - the `iam_login_attempts` quarterly partition maintenance
///     ([LoginAttemptRepository#ensureQuarterlyPartition] /
///     [LoginAttemptRepository#dropPartitionsOlderThan]) — this is what keeps
///     `lastSuccessAt`'s 400-day lookback window partition-pruned;
///   - the rate-limit-event prune, which is a **named no-op** ([#pruneRateLimitEvents]):
///     `iam_rate_limit_events` is not written by any Java path yet (grepped
///     2026-09-05 — only the jOOQ-generated table classes reference it), so
///     there is nothing to delete until that write path is ported.
///
/// Every other row in the Go purger's table (spec §4) purges an auth table
/// this port has not built yet, and is a stub until the auth aggregate
/// lands:
///
///   - OAuth payloads — `oauth_oidc_payloads` (access/refresh tokens)
///   - OIDC login states — `oauth_oidc_login_states`
///   - WebAuthn ceremonies — `webauthn_ceremonies`
///   - Portal login flows — `portal_login_flows`
///   - OAuth previous secrets — the `previous_secret_*` rotation-window columns
public final class Purger implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(Purger.class);

    /// Tick cadence (spec §4: "every minute").
    static final Duration TICK_INTERVAL = Duration.ofMinutes(1);

    /// How far ahead the poller pre-creates a quarterly partition (spec §4:
    /// "`EnsureQuarterlyPartition(now)` and `(now + 3 months)`").
    private static final int LOOKAHEAD_MONTHS = 3;

    /// How long a quarterly partition survives before it is dropped (spec §4:
    /// "`DropPartitionsOlderThan(now − 3 years)`").
    private static final int RETENTION_YEARS = 3;

    private final Thread thread;

    private Purger(Thread thread) {
        this.thread = thread;
    }

    /// Starts the purger against `pool`. Never `null` — unlike the leader-gated
    /// subsystems, there is no toggle to fail: `Server` calls this whenever a
    /// pool exists at all (spec §4).
    public static Purger start(DataSource pool) {
        return start(pool, TICK_INTERVAL, Clock.systemUTC());
    }

    /// Test-only: overrides the tick interval and clock.
    static Purger start(DataSource pool, Duration tickInterval, Clock clock) {
        Objects.requireNonNull(pool, "pool");
        Objects.requireNonNull(tickInterval, "tickInterval");
        Objects.requireNonNull(clock, "clock");
        var loginAttempts = new LoginAttemptRepository(pool);
        Thread thread = Thread.ofVirtual().name("purger").start(() -> loop(loginAttempts, tickInterval, clock));
        return new Purger(thread);
    }

    private static void loop(LoginAttemptRepository loginAttempts, Duration tickInterval, Clock clock) {
        while (!Thread.currentThread().isInterrupted()) {
            tick(loginAttempts, clock.instant());
            if (!sleep(tickInterval)) return;
        }
    }

    /// One purge pass (spec §4). Package-private so the test can drive it
    /// synchronously instead of racing the tick loop.
    static void tick(LoginAttemptRepository loginAttempts, Instant now) {
        step("login-attempt-partitions", () -> {
            loginAttempts.ensureQuarterlyPartition(now);
            loginAttempts.ensureQuarterlyPartition(now.atZone(ZoneOffset.UTC).plusMonths(LOOKAHEAD_MONTHS).toInstant());
            loginAttempts.dropPartitionsOlderThan(now.atZone(ZoneOffset.UTC).minusYears(RETENTION_YEARS).toInstant());
        });
        step("rate-limit-events", Purger::pruneRateLimitEvents);
    }

    /// No-op: see the class doc. Named so the per-step failure log (and a
    /// future implementation) has somewhere to land.
    private static void pruneRateLimitEvents() {
    }

    private static void step(String name, Runnable action) {
        try {
            action.run();
            recordStep(name, true, null);
        } catch (RuntimeException e) {
            LOG.warn("purger step failed name={}", name, e);
            recordStep(name, false, e.getClass().getName() + ": " + e.getMessage());
        }
    }

    /// Records the step's outcome, if anyone is recording
    /// (`docs/spec/jfr-events.md` §4). `shouldCommit()` first so a disabled
    /// recording costs one virtual call and no field writes.
    private static void recordStep(String name, boolean succeeded, String error) {
        var event = new PurgerStepEvent();
        if (!event.shouldCommit()) {
            return;
        }
        event.step = name;
        event.succeeded = succeeded;
        event.error = error;
        event.commit();
    }

    private static boolean sleep(Duration duration) {
        try {
            Thread.sleep(duration);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public void close() {
        thread.interrupt();
        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
