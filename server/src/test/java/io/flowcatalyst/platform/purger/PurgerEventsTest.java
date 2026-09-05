package io.flowcatalyst.platform.purger;

import io.flowcatalyst.platform.loginattempt.LoginAttemptRepository;
import io.flowcatalyst.platform.purger.jfr.PurgerStepEvent;
import io.flowcatalyst.testjfr.Recorded;
import io.flowcatalyst.testpg.TestPg;
import jdk.jfr.consumer.RecordedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// The `PurgerStep` flight-recorder event (`docs/spec/jfr-events.md` §4),
/// read back out of a real recording — the same `LoginAttemptRepository`
/// fixture as `PurgerTest`.
class PurgerEventsTest {

    private static final DataSource DS = TestPg.dataSource();

    @Test
    @DisplayName("a normal tick records both steps succeeded, with no error")
    void aNormalTickRecordsBothStepsSucceeded() throws Exception {
        var loginAttempts = new LoginAttemptRepository(DS);

        var events = Recorded.from(PurgerStepEvent.class, () -> Purger.tick(loginAttempts, Instant.now()));

        assertThat(events).hasSize(2);
        assertThat(events).extracting(e -> e.getString("step"))
                .containsExactlyInAnyOrder("login-attempt-partitions", "rate-limit-events");
        for (RecordedEvent event : events) {
            assertThat(event.getBoolean("succeeded")).as(event.getString("step") + " succeeded").isTrue();
            assertThat(event.getString("error")).as(event.getString("step") + " error").isNull();
        }
    }

    @Test
    @DisplayName("a step whose action throws records succeeded=false with the exception text, "
            + "and the other step's event is still present")
    void aFailingStepRecordsTheError() throws Exception {
        var loginAttempts = new LoginAttemptRepository(DS);

        // Instant.MAX's year (10^9) overflows YearMonth's range, so
        // ensureQuarterlyPartition throws a real DateTimeException before
        // ever touching the database — a genuine failing dependency, not a
        // mock, pinning "one table's problem never stops the rest of the
        // pass" (class doc, Purger) against an actual thrown exception.
        var events = Recorded.from(PurgerStepEvent.class, () -> Purger.tick(loginAttempts, Instant.MAX));

        assertThat(events).hasSize(2);
        var partitions = only(events, "login-attempt-partitions");
        assertThat(partitions.getBoolean("succeeded")).isFalse();
        assertThat(partitions.getString("error")).contains("DateTimeException");

        var rateLimit = only(events, "rate-limit-events");
        assertThat(rateLimit.getBoolean("succeeded")).as("an unrelated step's failure must not affect this one").isTrue();
        assertThat(rateLimit.getString("error")).isNull();
    }

    private static RecordedEvent only(List<RecordedEvent> events, String step) {
        var matches = events.stream().filter(e -> e.getString("step").equals(step)).toList();
        assertThat(matches).as("exactly one event for step " + step).hasSize(1);
        return matches.getFirst();
    }
}
