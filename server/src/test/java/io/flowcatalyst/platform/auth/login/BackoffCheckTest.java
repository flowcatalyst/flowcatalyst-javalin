package io.flowcatalyst.platform.auth.login;

import io.flowcatalyst.platform.loginattempt.AttemptOutcome;
import io.flowcatalyst.platform.loginattempt.AttemptType;
import io.flowcatalyst.platform.loginattempt.LoginAttempt;
import io.flowcatalyst.platform.loginattempt.LoginAttemptRepository;
import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.testpg.TestPg;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static io.flowcatalyst.db.generated.Tables.IAM_LOGIN_ATTEMPTS;
import static org.assertj.core.api.Assertions.assertThat;

/// `docs/spec/login-backoff-lock.md` §6 — the table, run against the pure
/// decision, plus the two cases that only a real timeline can pin
/// ("Retry-After is honest", "denied attempts don't extend") against the
/// repository on the embedded Postgres.
class BackoffCheckTest {

    private static final BackoffPolicy P = BackoffPolicy.DEFAULT; // free 3, base 2, max 300, window 3600, ceiling 100, lock 900
    private static final Instant NOW = Instant.parse("2026-09-05T12:00:00Z");

    private static BackoffCheck.Stats stats(int pairFailures, Instant pairLast, Instant tripped) {
        return stats(pairFailures, pairLast, tripped, tripped);
    }

    private static BackoffCheck.Stats stats(int pairFailures, Instant pairLast, Instant tripped, Instant lastFailure) {
        return new BackoffCheck.Stats(Optional.empty(), pairFailures, Optional.ofNullable(pairLast),
                Optional.ofNullable(tripped), Optional.ofNullable(lastFailure));
    }

    // ── policy numbers ─────────────────────────────────────────────────────

    @ParameterizedTest(name = "{0} failures → {1}s")
    @CsvSource({"0,0", "1,0", "2,0", "3,0", "4,2", "5,4", "6,8", "7,16", "8,32", "9,64", "10,128", "11,256", "12,300", "13,300", "50,300"})
    void delayScheduleIsTheDefaultsTable(int failures, long expected) {
        assertThat(P.delaySecs(failures)).isEqualTo(expected);
    }

    // ── window 1: per pair ─────────────────────────────────────────────────

    @Test
    void underTheFreeAttemptsThereIsNoDelay() {
        assertThat(BackoffCheck.decide(P, NOW, stats(3, NOW, null)).allowed()).isTrue();
    }

    @Test
    void aPairDelayDeniesForTheRemainderAndClearsWhenItHasElapsed() {
        var d = BackoffCheck.decide(P, NOW, stats(6, NOW.minusSeconds(3), null)); // required 8, elapsed 3
        assertThat(d.allowed()).isFalse();
        assertThat(d.retryAfterSecs()).isEqualTo(5);
        assertThat(d.reason()).isEqualTo(BackoffCheck.Reason.PAIR_BACKOFF);
        assertThat(BackoffCheck.decide(P, NOW, stats(6, NOW.minusSeconds(8), null)).allowed()).isTrue();
    }

    // ── window 2: the ceiling as a lock (spec §6 table) ────────────────────

    @Test
    void underTheCeilingIsAllowed() {
        // 99 failures in-window: the repository reports no trip (fewer than ceiling rows).
        assertThat(BackoffCheck.decide(P, NOW, stats(0, null, null)).allowed()).isTrue();
    }

    @Test
    void tripDeniesWithTheLockAdvertised() {
        var d = BackoffCheck.decide(P, NOW, stats(0, null, NOW)); // 100th failure just now → countEnds 3600 > lock 900
        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).isEqualTo(BackoffCheck.Reason.GLOBAL_CEILING);
        assertThat(d.retryAfterSecs()).as("the later of lockEnds and countEnds").isEqualTo(3600);
    }

    @Test
    void theLockIsEnforcedEvenAfterTheCountWouldHaveCleared() {
        // Spec §6 "lock enforced past count-clear": 100 failures all ~59 min
        // old, the last one 10 min ago. The count clears in 60 s; the lock
        // (from the LAST failure) holds for another 5 min.
        var d = BackoffCheck.decide(P, NOW, stats(0, null, NOW.minusSeconds(59 * 60), NOW.minusSeconds(10 * 60)));
        assertThat(d.allowed()).isFalse();
        assertThat(d.retryAfterSecs()).as("the lock outlives the count").isEqualTo(5 * 60);

        // Go's anchor (oldest of the set) would have allowed this at +60 s —
        // the deviation recorded in the backlog.
        var goShaped = BackoffCheck.decide(P, NOW.plusSeconds(61), stats(0, null, NOW.minusSeconds(59 * 60), NOW.minusSeconds(10 * 60)));
        assertThat(goShaped.allowed()).isFalse();
    }

    @Test
    void theCeilingOutlivesTheLockWhenTheWindowIsLonger() {
        // Spec §6 "ceiling outlives the lock": spread failures, the last 20 min
        // ago (lock ended 5 min ago), the ceiling-th 40 min ago (count clears
        // in 20 min) — denied, Retry-After = time to countEnds, not 900.
        var d = BackoffCheck.decide(P, NOW, stats(0, null, NOW.minusSeconds(40 * 60), NOW.minusSeconds(20 * 60)));
        assertThat(d.allowed()).isFalse();
        assertThat(d.retryAfterSecs()).as("time to countEnds, not the 900 s lock").isEqualTo(20 * 60);
    }

    @Test
    void lockAndWindowBothExpiredIsAllowed() {
        assertThat(BackoffCheck.decide(P, NOW, stats(0, null, NOW.minusSeconds(3601), NOW.minusSeconds(901))).allowed()).isTrue();
        assertThat(BackoffCheck.decide(P, NOW, stats(0, null, NOW.minusSeconds(3601), NOW.minusSeconds(899))).allowed())
                .as("the window cleared but the lock has not").isFalse();
    }

    @Test
    void retryAfterIsNeverBelowOneSecond() {
        var d = BackoffCheck.decide(P, NOW, stats(0, null, NOW.minusMillis(3600_000 - 200)));
        assertThat(d.allowed()).isFalse();
        assertThat(d.retryAfterSecs()).isEqualTo(1);
    }

    // ── against the repository: the two cases that would silently regress ──

    private static final DSLContext DB = DSL.using(TestPg.dataSource(), SQLDialect.POSTGRES);
    private static final LoginAttemptRepository REPO = new LoginAttemptRepository(TestPg.dataSource());
    private static final String ID = "backoff-" + UUID.randomUUID().toString().substring(0, 8).toLowerCase(Locale.ROOT) + "@example.com";

    @AfterAll
    static void cleanup() {
        DB.deleteFrom(IAM_LOGIN_ATTEMPTS).where(IAM_LOGIN_ATTEMPTS.IDENTIFIER.like("backoff-%@example.com")).execute();
    }

    private static void failureAt(String identifier, Instant at) {
        DB.insertInto(IAM_LOGIN_ATTEMPTS)
                .set(IAM_LOGIN_ATTEMPTS.ID, EntityType.LOGIN_ATTEMPT.generate())
                .set(IAM_LOGIN_ATTEMPTS.ATTEMPT_TYPE, AttemptType.USER_LOGIN.name())
                .set(IAM_LOGIN_ATTEMPTS.OUTCOME, AttemptOutcome.FAILURE.name())
                .set(IAM_LOGIN_ATTEMPTS.IDENTIFIER, identifier)
                .set(IAM_LOGIN_ATTEMPTS.IP_ADDRESS, "10.0.0.1")
                .set(IAM_LOGIN_ATTEMPTS.FAILURE_REASON, "Invalid credentials")
                .set(IAM_LOGIN_ATTEMPTS.ATTEMPTED_AT, at.atOffset(ZoneOffset.UTC))
                .execute();
    }

    @Test
    void retryAfterIsHonestAndDeniedAttemptsDoNotExtendTheLock() {
        var policy = new BackoffPolicy(1000, 2, 300, 600, 5, 900); // no pair delay; ceiling 5; lock 15 min > window 10 min
        var check = new BackoffCheck(REPO, policy);
        Instant now = Instant.now();
        Instant fifth = now.minusSeconds(10);
        for (int i = 0; i < 5; i++) {
            failureAt(ID, fifth.minusSeconds(60L * (4 - i))); // the fifth (most recent) is 10 s ago
        }

        var denied = check.check(ID, "10.0.0.1", now);
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterSecs()).as("lock end = fifth failure + 900 s").isEqualTo(890);

        // Denied attempts are not recorded by the caller (spec §4). Simulate
        // five more denials: nothing written, so the timeline is unchanged.
        assertThat(check.check(ID, "10.0.0.1", now.plusSeconds(300)).retryAfterSecs()).isEqualTo(590);

        // Waiting exactly the advertised interval lets the caller through.
        assertThat(check.check(ID, "10.0.0.1", now.plusSeconds(890)).allowed())
                .as("Retry-After is honest — the assertion the whole change exists for").isTrue();
        assertThat(check.check(ID, "10.0.0.1", now.plusSeconds(889)).allowed()).isFalse();
    }

    @Test
    void aSuccessClearsTheWindow() {
        String id = "backoff-success-" + UUID.randomUUID().toString().substring(0, 6) + "@example.com";
        var policy = new BackoffPolicy(1000, 2, 300, 600, 3, 900);
        var check = new BackoffCheck(REPO, policy);
        Instant now = Instant.now();
        failureAt(id, now.minusSeconds(30));
        failureAt(id, now.minusSeconds(20));
        failureAt(id, now.minusSeconds(15));
        assertThat(check.check(id, "", now).allowed()).isFalse();
        REPO.recordAttempt(LoginAttempt.attempt(AttemptType.USER_LOGIN, AttemptOutcome.SUCCESS, null, id, null, "10.0.0.1", null));
        assertThat(check.check(id, "", now.plusSeconds(1)).allowed()).as("cutoff moves to the success").isTrue();
    }
}
