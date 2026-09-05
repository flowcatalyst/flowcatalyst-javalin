package io.flowcatalyst.platform.purger;

import io.flowcatalyst.platform.auth.grant.GrantStore;
import io.flowcatalyst.platform.auth.mfa.MfaRepository;
import io.flowcatalyst.platform.auth.ratelimit.PostgresRateLimitStore;
import io.flowcatalyst.platform.auth.ratelimit.RateLimit;
import io.flowcatalyst.platform.loginattempt.LoginAttemptRepository;
import io.flowcatalyst.platform.oauthclient.OAuthClientRepository;
import io.flowcatalyst.platform.purger.jfr.PurgerStepEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.function.IntSupplier;

/// The auth janitor (`docs/spec/scheduled-job-scheduler.md` §4,
/// `auth-retention.md` §3–§4, `auth-identity.md` §14 with ruling I-Q17):
/// always on, every minute, not leader-gated — every statement is
/// idempotent, so a second instance's pass deletes nothing. One virtual
/// thread, interruption as the stop signal; each step is logged on
/// failure and the loop goes on to the next.
///
/// Rows past their expiry are refused by every reader already; the sweep
/// only reclaims them. Login-facing rows (PINs, trusted devices, reset
/// tokens) get a day of grace before removal so a support question about
/// "the code that did not work" can still be answered from the table.
public final class Purger implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(Purger.class);

    static final Duration TICK_INTERVAL = Duration.ofMinutes(1);
    /// Go's `rateLimitPruneMargin`: beyond the longest policy window.
    static final Duration RATE_LIMIT_PRUNE_MARGIN = Duration.ofMinutes(10);
    /// Ruling I-Q17: "expiry + grace" for the four auth tables.
    static final Duration EXPIRED_ROW_GRACE = Duration.ofHours(24);
    private static final int LOOKAHEAD_MONTHS = 3;
    private static final int RETENTION_YEARS = 3;

    /// Everything one tick touches. Built once at start; a test builds its
    /// own over the embedded database.
    public record Sweeps(LoginAttemptRepository loginAttempts, RateLimit.Store rateLimitEvents, Duration rateLimitRetention,
                         GrantStore grants, OAuthClientRepository oauthClients, MfaRepository mfa,
                         AuthHousekeeping housekeeping) {
        public Sweeps {
            Objects.requireNonNull(loginAttempts, "loginAttempts");
            Objects.requireNonNull(rateLimitEvents, "rateLimitEvents");
            Objects.requireNonNull(rateLimitRetention, "rateLimitRetention");
            Objects.requireNonNull(grants, "grants");
            Objects.requireNonNull(oauthClients, "oauthClients");
            Objects.requireNonNull(mfa, "mfa");
            Objects.requireNonNull(housekeeping, "housekeeping");
        }

        public static Sweeps over(DataSource pool, RateLimit.Policies policies) {
            return new Sweeps(new LoginAttemptRepository(pool), new PostgresRateLimitStore(pool),
                    policies.maxWindow().plus(RATE_LIMIT_PRUNE_MARGIN), new GrantStore(pool),
                    new OAuthClientRepository(pool, new io.flowcatalyst.platform.application.ApplicationRepository(pool)),
                    new MfaRepository(pool), new AuthHousekeeping(pool));
        }
    }

    private final Thread thread;

    private Purger(Thread thread) {
        this.thread = thread;
    }

    public static Purger start(DataSource pool, RateLimit.Policies policies) {
        return start(Sweeps.over(pool, policies), TICK_INTERVAL, Clock.systemUTC());
    }

    static Purger start(Sweeps sweeps, Duration tickInterval, Clock clock) {
        Objects.requireNonNull(sweeps, "sweeps");
        Objects.requireNonNull(tickInterval, "tickInterval");
        Objects.requireNonNull(clock, "clock");
        Thread thread = Thread.ofVirtual().name("purger").start(() -> loop(sweeps, tickInterval, clock));
        return new Purger(thread);
    }

    private static void loop(Sweeps sweeps, Duration tickInterval, Clock clock) {
        while (!Thread.currentThread().isInterrupted()) {
            tick(sweeps, clock.instant());
            if (!sleep(tickInterval)) return;
        }
    }

    /// One pass over every sweep, in Go's order, each isolated.
    static void tick(Sweeps s, Instant now) {
        Instant graced = now.minus(EXPIRED_ROW_GRACE);
        step("oauth-payloads", s.grants()::deleteExpired);
        step("oidc-login-states", () -> s.housekeeping().deleteExpiredOidcLoginStates(now));
        step("portal-login-flows", () -> s.housekeeping().deleteExpiredPortalLoginFlows(now));
        step("rate-limit-events", () -> s.rateLimitEvents().prune(s.rateLimitRetention()));
        step("oauth-previous-secrets", () -> s.oauthClients().clearLapsedPreviousSecrets(now));
        step("mfa-email-pins", () -> s.mfa().deleteExpiredPins(graced));
        step("mfa-trusted-devices", () -> s.mfa().deleteExpiredTrustedDevices(graced));
        step("password-reset-tokens", () -> s.housekeeping().deleteExpiredPasswordResetTokens(graced));
        step("reset-approval-requests", () -> s.housekeeping().expirePendingApprovalRequests(now));
        step("login-attempt-partitions", () -> {
            s.loginAttempts().ensureQuarterlyPartition(now);
            s.loginAttempts().ensureQuarterlyPartition(now.atZone(ZoneOffset.UTC).plusMonths(LOOKAHEAD_MONTHS).toInstant());
            s.loginAttempts().dropPartitionsOlderThan(now.atZone(ZoneOffset.UTC).minusYears(RETENTION_YEARS).toInstant());
            return 0;
        });
    }

    private static void step(String name, IntSupplier action) {
        try {
            int n = action.getAsInt();
            if (n > 0) {
                LOG.debug("purger step={} removed={}", name, n);
            }
            recordStep(name, true, null);
        } catch (RuntimeException e) {
            LOG.warn("purger step failed name={}", name, e);
            recordStep(name, false, e.getClass().getName() + ": " + e.getMessage());
        }
    }

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
