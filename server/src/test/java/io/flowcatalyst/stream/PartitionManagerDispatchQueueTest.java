package io.flowcatalyst.stream;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.Seed;
import io.flowcatalyst.testpg.TestPg;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.DB;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.code;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.queueRow;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.seedWriteRow;
import static org.assertj.core.api.Assertions.assertThat;

/// Dropping a `msg_dispatch_jobs` partition also deletes the `msg_dispatch_queue`
/// rows whose `job_created_at` falls in its range — those and no others — and
/// says so at WARN (dispatch-queue spec, step 2).
class PartitionManagerDispatchQueueTest {

    private static final DataSource DS = TestPg.dataSource();
    private static final PartitionManager.Config CONFIG =
            new PartitionManager.Config(true, 3, 90, 30, Duration.ofHours(24));

    private static String seedAt(String createdAt) {
        Instant at = Instant.parse(createdAt);
        return seedWriteRow(Seed.of(code("part")).withCreatedAt(at).withUpdatedAt(at));
    }

    @Test
    void droppingAPartitionRemovesItsQueueRowsAndNoOthers() throws Exception {
        try (Connection c = DS.getConnection(); Statement st = c.createStatement()) {
            for (String m : new String[]{"01", "02", "03"}) {
                st.execute("CREATE TABLE IF NOT EXISTS msg_dispatch_jobs_2020_" + m + " PARTITION OF msg_dispatch_jobs"
                        + " FOR VALUES FROM ('2020-" + m + "-01') TO ('2020-0" + (Integer.parseInt(m) + 1) + "-01')");
            }
        }
        String jan1 = seedAt("2020-01-05T10:00:00Z");
        String jan2 = seedAt("2020-01-31T23:59:59Z");
        String feb = seedAt("2020-02-10T10:00:00Z");
        String mar = seedAt("2020-03-10T10:00:00Z");
        String current = seedWriteRow(Seed.of(code("part")));
        for (String id : new String[]{jan1, jan2, feb, mar, current}) assertThat(queueRow(id)).isNotNull();

        var logger = (Logger) LoggerFactory.getLogger(PartitionManager.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try (Connection c = DS.getConnection()) {
            // 2020-06-01 minus 90 days = 2020-03-03: January and February have ended before it, March has not
            var manager = new PartitionManager(DS, CONFIG, () -> true, new Health("partition-queue-test"), Clock.systemUTC());
            int dropped = manager.dropOld(c, "msg_dispatch_jobs", Instant.parse("2020-06-01T00:00:00Z"));
            assertThat(dropped).isEqualTo(2);
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(queueRow(jan1)).as("January's partition is gone, and its queue rows with it").isNull();
        assertThat(queueRow(jan2)).as("the last second of the partition's range").isNull();
        assertThat(queueRow(feb)).isNull();
        assertThat(queueRow(mar)).as("March's partition survives, so does its queue row").isNotNull();
        assertThat(queueRow(current)).as("a job in another partition is untouched").isNotNull();
        assertThat(DB.fetchOne("SELECT to_regclass('msg_dispatch_jobs_2020_01')").get(0)).isNull();

        var warns = appender.list.stream().filter(e -> e.getLevel() == Level.WARN)
                .filter(e -> e.getFormattedMessage().contains("PENDING jobs")).toList();
        assertThat(warns).as("one WARN per partition that held queue rows").hasSize(2);
        assertThat(warns.stream().flatMap(e -> e.getKeyValuePairs().stream())
                .filter(kv -> kv.key.equals("queue_rows_removed")).map(kv -> kv.value).toList())
                .as("the rows removed per partition (January 2, February 1), in either order")
                .containsExactlyInAnyOrder(2, 1);
    }

    @Test
    void aPartitionWithNoQueueRowsDropsQuietly() throws Exception {
        try (Connection c = DS.getConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS msg_dispatch_jobs_2019_01 PARTITION OF msg_dispatch_jobs"
                    + " FOR VALUES FROM ('2019-01-01') TO ('2019-02-01')");
        }
        var logger = (Logger) LoggerFactory.getLogger(PartitionManager.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try (Connection c = DS.getConnection()) {
            var manager = new PartitionManager(DS, CONFIG, () -> true, new Health("partition-queue-test-2"), Clock.systemUTC());
            assertThat(manager.dropOld(c, "msg_dispatch_jobs", Instant.parse("2019-06-01T00:00:00Z"))).isEqualTo(1);
        } finally {
            logger.detachAppender(appender);
        }
        assertThat(appender.list).noneMatch(e -> e.getLevel() == Level.WARN);
    }
}
