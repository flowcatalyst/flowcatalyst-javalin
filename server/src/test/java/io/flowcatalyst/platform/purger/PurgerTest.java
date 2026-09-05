package io.flowcatalyst.platform.purger;

import io.flowcatalyst.platform.loginattempt.LoginAttemptRepository;
import io.flowcatalyst.testpg.TestPg;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/// [Purger] against a real embedded Postgres (`docs/spec/scheduled-job-scheduler.md`
/// §4): the login-attempt quarterly-partition maintenance (the one step this
/// port implements against real DDL) and the lifecycle. The rate-limit-event
/// step is pinned as a genuine no-op — see [#rateLimitStepIsANamedNoOp] — not
/// tested as "prunes expired rows" because it deliberately does not: spec §4,
/// "leave a named no-op step ... if not [written by Java]".
class PurgerTest {

    private static final DataSource DS = TestPg.dataSource();
    private static final LoginAttemptRepository LOGIN_ATTEMPTS = new LoginAttemptRepository(DS);

    private static boolean tableExists(String name) {
        try (Connection conn = DS.getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT to_regclass(?)")) {
            stmt.setString(1, name);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() && rs.getString(1) != null;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static void dropIfExists(String name) {
        try (Connection conn = DS.getConnection(); var stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS " + name);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    // ── Quarterly partitions: created idempotently ──────────────────────────

    @Test
    void ensureQuarterlyPartitionCreatesThisQuarterAndNextIdempotently() {
        // A run-unique future year so this never collides with the quarters
        // the V5 migration pre-creates around "real now", or with another
        // run of this same test.
        int year = 2200 + (int) (UUID.randomUUID().getMostSignificantBits() % 50 + 50);
        Instant now = Instant.parse(year + "-05-15T00:00:00Z"); // Q2
        Instant nextQuarter = now.atZone(ZoneOffset.UTC).plusMonths(3).toInstant(); // Q3
        String thisQuarterName = "iam_login_attempts_" + year + "_q2";
        String nextQuarterName = "iam_login_attempts_" + year + "_q3";
        try {
            LOGIN_ATTEMPTS.ensureQuarterlyPartition(now);
            LOGIN_ATTEMPTS.ensureQuarterlyPartition(nextQuarter);
            assertThat(tableExists(thisQuarterName)).as(thisQuarterName).isTrue();
            assertThat(tableExists(nextQuarterName)).as(nextQuarterName).isTrue();

            // Idempotent: a second pass over the same two quarters must not throw.
            LOGIN_ATTEMPTS.ensureQuarterlyPartition(now);
            LOGIN_ATTEMPTS.ensureQuarterlyPartition(nextQuarter);
            assertThat(tableExists(thisQuarterName)).isTrue();
            assertThat(tableExists(nextQuarterName)).isTrue();
        } finally {
            dropIfExists(thisQuarterName);
            dropIfExists(nextQuarterName);
        }
    }

    // ── dropPartitionsOlderThan: an old quarter dropped, the default kept ───

    @Test
    void dropPartitionsOlderThanDropsAnOldQuarterButNeverTheDefaultPartition() {
        // Our own old quarter (spec-mandated: never drop a partition that
        // might hold another test's rows) — 1900 predates every other
        // fixture in this suite by construction.
        String oldQuarterName = "iam_login_attempts_1900_q1";
        LOGIN_ATTEMPTS.ensureQuarterlyPartition(Instant.parse("1900-02-01T00:00:00Z"));
        assertThat(tableExists(oldQuarterName)).as("the fixture created it").isTrue();
        assertThat(tableExists("iam_login_attempts_default")).as("the default partition already exists").isTrue();

        LOGIN_ATTEMPTS.dropPartitionsOlderThan(Instant.parse("1950-01-01T00:00:00Z"));

        assertThat(tableExists(oldQuarterName)).as("older than the cutoff -> dropped").isFalse();
        assertThat(tableExists("iam_login_attempts_default")).as("the default partition is never a match").isTrue();
    }

    // ── The rate-limit step: a genuine no-op ────────────────────────────────

    @Test
    void rateLimitStepIsANamedNoOp() {
        // iam_rate_limit_events is not written by any Java path yet (spec §4);
        // the step must do nothing at all, not silently delete rows a future
        // writer produces. Insert a row well outside any real retention
        // window and confirm the tick leaves it alone.
        try (Connection conn = DS.getConnection(); var stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO iam_rate_limit_events (bucket, key, occurred_at) "
                    + "VALUES ('purger-test', 'k', '1999-01-01T00:00:00Z')");
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        Purger.tick(LOGIN_ATTEMPTS, Instant.now());

        Long count;
        try (Connection conn = DS.getConnection(); var stmt = conn.createStatement();
             var rs = stmt.executeQuery("SELECT count(*) FROM iam_rate_limit_events WHERE bucket = 'purger-test'")) {
            rs.next();
            count = rs.getLong(1);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        assertThat(count).as("a step with nothing to write must not delete anything either").isEqualTo(1L);
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    @Test
    void startAndCloseCompletePromptly() throws InterruptedException {
        var purger = Purger.start(DS, Duration.ofMillis(50), Clock.systemUTC());
        Thread.sleep(120); // let it actually enter its sleep at least once

        long startNanos = System.nanoTime();
        purger.close();
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

        assertThat(elapsedMs).as("close() must interrupt, not wait out the tick interval").isLessThan(5_000);
    }
}
