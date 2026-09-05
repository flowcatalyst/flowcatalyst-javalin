package io.flowcatalyst.platform.scheduler.jobs;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.flowcatalyst.platform.scheduledjob.InstanceStatus;
import io.flowcatalyst.platform.scheduledjob.ScheduledJob;
import io.flowcatalyst.platform.scheduledjob.ScheduledJobCode;
import io.flowcatalyst.platform.scheduledjob.ScheduledJobInstance;
import io.flowcatalyst.platform.scheduledjob.ScheduledJobInstanceRepository;
import io.flowcatalyst.platform.scheduledjob.ScheduledJobRepository;
import io.flowcatalyst.platform.scheduledjob.TriggerKind;
import io.flowcatalyst.platform.scheduledjob.cron.CronExpression;
import io.flowcatalyst.platform.scheduler.jobs.jfr.JobFiredEvent;
import io.flowcatalyst.platform.serviceaccount.ServiceAccountRepository;
import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.sdk.usecase.jdbc.DbTx;
import io.flowcatalyst.testjfr.Recorded;
import io.flowcatalyst.testpg.TestPg;
import jdk.jfr.consumer.RecordedEvent;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.net.http.HttpClient;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static io.flowcatalyst.db.generated.Tables.IAM_SERVICE_ACCOUNTS;
import static org.assertj.core.api.Assertions.assertThat;

/// The `JobFired` flight-recorder event (`docs/spec/jfr-events.md` §3), read
/// back out of a real recording — same fixture as `JobDispatcherTest`
/// (embedded Postgres + a stub HTTP target).
class JobFiredEventTest {

    private static final DataSource DS = TestPg.dataSource();
    private static final DSLContext DB = DSL.using(DS, SQLDialect.POSTGRES);
    private static final ScheduledJobRepository JOBS = new ScheduledJobRepository(DS);
    private static final ScheduledJobInstanceRepository INSTANCES = new ScheduledJobInstanceRepository(DS);
    private static final ServiceAccountRepository SERVICE_ACCOUNTS = new ServiceAccountRepository(DS, Optional.empty());

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toLowerCase(Locale.ROOT);
    private static final List<String> JOB_IDS = new ArrayList<>();
    private static final List<String> INSTANCE_IDS = new ArrayList<>();
    private static final List<String> SERVICE_ACCOUNT_IDS = new ArrayList<>();
    private static int seq = 0;

    @AfterAll
    static void cleanup() {
        DB.deleteFrom(io.flowcatalyst.db.generated.Tables.MSG_SCHEDULED_JOB_INSTANCES)
                .where(io.flowcatalyst.db.generated.Tables.MSG_SCHEDULED_JOB_INSTANCES.ID.in(INSTANCE_IDS)).execute();
        DB.deleteFrom(io.flowcatalyst.db.generated.Tables.MSG_SCHEDULED_JOBS)
                .where(io.flowcatalyst.db.generated.Tables.MSG_SCHEDULED_JOBS.ID.in(JOB_IDS)).execute();
        DB.deleteFrom(IAM_SERVICE_ACCOUNTS).where(IAM_SERVICE_ACCOUNTS.ID.in(SERVICE_ACCOUNT_IDS)).execute();
    }

    // ── Fixtures (copied from JobDispatcherTest's idiom) ────────────────────

    private static String persistJob(ScheduledJob job) {
        try (Connection conn = DS.getConnection()) {
            conn.setAutoCommit(false);
            JOBS.persist(job, DbTx.wrapForBootstrap(conn));
            conn.commit();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        JOB_IDS.add(job.id());
        return job.id();
    }

    private static ScheduledJob job(String targetUrl, int deliveryMaxAttempts, String applicationId) {
        var code = ScheduledJobCode.parse("jfr-dispatch-" + RUN + "-" + (seq++));
        var def = new ScheduledJob.Definition("jfr event test job", null,
                List.of(CronExpression.parse("0 0 0 1 1 *")), "UTC", null, false, false,
                null, deliveryMaxAttempts, targetUrl);
        ScheduledJob j = ScheduledJob.create(code, def);
        if (applicationId != null) {
            j = j.withApplicationId(applicationId);
        }
        return j;
    }

    private static String queuedInstance(String jobId, String jobCode) {
        String id = EntityType.SCHEDULED_JOB_INSTANCE.generate();
        var now = java.time.Instant.now();
        var inst = new ScheduledJobInstance(id, jobId, null, jobCode, TriggerKind.MANUAL, null, now, null, null,
                InstanceStatus.QUEUED, 0, null, null, null, null, now);
        INSTANCES.insert(inst);
        INSTANCE_IDS.add(id);
        return id;
    }

    private static String activeServiceAccount(String applicationId, String token, String signingSecret) {
        String id = EntityType.SERVICE_ACCOUNT.generate();
        DB.insertInto(IAM_SERVICE_ACCOUNTS)
                .set(IAM_SERVICE_ACCOUNTS.ID, id)
                .set(IAM_SERVICE_ACCOUNTS.CODE, "jfr-svc-" + RUN + "-" + (seq++))
                .set(IAM_SERVICE_ACCOUNTS.NAME, "jfr event test service account")
                .set(IAM_SERVICE_ACCOUNTS.APPLICATION_ID, applicationId)
                .set(IAM_SERVICE_ACCOUNTS.ACTIVE, true)
                .set(IAM_SERVICE_ACCOUNTS.WH_AUTH_TYPE, "BEARER_TOKEN")
                .set(IAM_SERVICE_ACCOUNTS.WH_AUTH_TOKEN_REF, token)
                .set(IAM_SERVICE_ACCOUNTS.WH_SIGNING_SECRET_REF, signingSecret)
                .execute();
        SERVICE_ACCOUNT_IDS.add(id);
        return id;
    }

    private static JobDispatcher dispatcher() {
        return new JobDispatcher(JOBS, INSTANCES, HttpClient.newHttpClient(), Duration.ofSeconds(5),
                applicationId -> OutboundCredentials.resolve(SERVICE_ACCOUNTS, applicationId), () -> true, 32,
                Clock.systemUTC());
    }

    // ── Delivered: statusCode, terminal=true, signed reflects the credentials branch ──

    @Test
    @DisplayName("a delivered firing records DELIVERED, terminal=true, the HTTP status and signed=true when the "
            + "application has a signing secret")
    void deliveredFiringIsRecorded() throws Exception {
        HttpServer server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext("/hook", exchange -> {
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            });
            server.start();
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";

            String applicationId = EntityType.APPLICATION.generate();
            activeServiceAccount(applicationId, "tok-" + RUN, "sec-" + RUN);
            String jobId = persistJob(job(baseUrl, 3, applicationId));
            String jobCode = JOBS.findById(jobId).orElseThrow().code();
            String instanceId = queuedInstance(jobId, jobCode);

            var events = Recorded.from(JobFiredEvent.class, () -> dispatcher().dispatchOnce());

            var fired = only(events, instanceId);
            assertThat(fired.getString("jobCode")).isEqualTo(jobCode);
            assertThat(fired.getInt("attempt")).isEqualTo(1);
            assertThat(fired.getString("outcome")).isEqualTo("DELIVERED");
            assertThat(fired.getBoolean("terminal")).isTrue();
            assertThat(fired.getInt("statusCode")).isEqualTo(200);
            assertThat(fired.getBoolean("signed")).as("the application has a signing secret").isTrue();

            assertThat(INSTANCES.findById(instanceId).orElseThrow().status()).isEqualTo(InstanceStatus.DELIVERED);
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("an unsigned delivery (no application linkage) records signed=false")
    void unsignedDeliveryRecordsSignedFalse() throws Exception {
        HttpServer server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext("/hook", exchange -> {
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
            });
            server.start();
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";

            String jobId = persistJob(job(baseUrl, 3, null));
            String jobCode = JOBS.findById(jobId).orElseThrow().code();
            String instanceId = queuedInstance(jobId, jobCode);

            var events = Recorded.from(JobFiredEvent.class, () -> dispatcher().dispatchOnce());

            var fired = only(events, instanceId);
            assertThat(fired.getString("outcome")).isEqualTo("DELIVERED");
            assertThat(fired.getBoolean("signed")).isFalse();
        } finally {
            server.stop(0);
        }
    }

    // ── Failed: non-2xx, non-terminal ────────────────────────────────────────

    @Test
    @DisplayName("a non-2xx, non-terminal failure records FAILED, terminal=false and the HTTP status")
    void nonTerminalFailureIsRecorded() throws Exception {
        HttpServer server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext("/hook", exchange -> {
                byte[] body = "boom".getBytes();
                exchange.sendResponseHeaders(500, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";

            String jobId = persistJob(job(baseUrl, 3, null));
            String jobCode = JOBS.findById(jobId).orElseThrow().code();
            String instanceId = queuedInstance(jobId, jobCode);

            var events = Recorded.from(JobFiredEvent.class, () -> dispatcher().dispatchOnce());

            var fired = only(events, instanceId);
            assertThat(fired.getString("outcome")).isEqualTo("FAILED");
            assertThat(fired.getBoolean("terminal")).as("attempt 1 of 3").isFalse();
            assertThat(fired.getInt("statusCode")).isEqualTo(500);
            assertThat(fired.getInt("attempt")).isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }

    // ── A delivered mark that fails to persist records no event at all ──────

    /// A CHECK constraint forbidding exactly the `DELIVERED` status, so
    /// `markInFlight` (which writes `IN_FLIGHT`) still succeeds and only the
    /// terminal `markDelivered` call fails — a genuine repository failure,
    /// not a mock, pinning "committed after the mark call returned; if it
    /// threw, do not commit" (the doc on `JobDispatcher.deliver`).
    private static final String NO_DELIVERED_CONSTRAINT = "jfr_test_no_delivered";

    private static void addNoDeliveredConstraint() {
        try (Connection c = DS.getConnection(); Statement st = c.createStatement()) {
            st.execute("ALTER TABLE msg_scheduled_job_instances DROP CONSTRAINT IF EXISTS " + NO_DELIVERED_CONSTRAINT);
            // NOT VALID: skip scanning the shared, never-truncated table
            // (CONVENTIONS §6) for pre-existing DELIVERED rows from other
            // tests — the constraint still applies to every write from here
            // on, which is all this test needs.
            st.execute("ALTER TABLE msg_scheduled_job_instances ADD CONSTRAINT " + NO_DELIVERED_CONSTRAINT
                    + " CHECK (status <> 'DELIVERED') NOT VALID");
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static void dropNoDeliveredConstraint() {
        try (Connection c = DS.getConnection(); Statement st = c.createStatement()) {
            st.execute("ALTER TABLE msg_scheduled_job_instances DROP CONSTRAINT IF EXISTS " + NO_DELIVERED_CONSTRAINT);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("a delivered mark that fails to persist records no event at all")
    void failedDeliveredMarkRecordsNoEvent() throws Exception {
        HttpServer server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext("/hook", exchange -> {
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            });
            server.start();
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";

            String jobId = persistJob(job(baseUrl, 3, null));
            String jobCode = JOBS.findById(jobId).orElseThrow().code();
            String instanceId = queuedInstance(jobId, jobCode);

            addNoDeliveredConstraint();
            try {
                var events = Recorded.from(JobFiredEvent.class, () -> dispatcher().dispatchOnce());

                assertThat(events.stream().filter(e -> e.getString("instanceId").equals(instanceId)).toList())
                        .as("markDelivered threw, so nothing is known to have happened to the row")
                        .isEmpty();
            } finally {
                dropNoDeliveredConstraint();
            }
        } finally {
            server.stop(0);
        }
    }

    // ── Orphan: instance references a job that no longer exists ─────────────

    @Test
    @DisplayName("an orphan instance records ORPHAN, terminal=true, attempt=0, statusCode=0")
    void orphanFiringIsRecorded() throws Exception {
        String instanceId = queuedInstance("sjb_" + UUID.randomUUID().toString().replace("-", "").substring(0, 13),
                "gone-code-" + RUN);

        var events = Recorded.from(JobFiredEvent.class, () -> dispatcher().dispatchOnce());

        var fired = only(events, instanceId);
        assertThat(fired.getString("jobCode")).isEqualTo("gone-code-" + RUN);
        assertThat(fired.getString("outcome")).isEqualTo("ORPHAN");
        assertThat(fired.getBoolean("terminal")).isTrue();
        assertThat(fired.getInt("attempt")).isEqualTo(0);
        assertThat(fired.getInt("statusCode")).isEqualTo(0);
        assertThat(fired.getBoolean("signed")).isFalse();
    }

    private static RecordedEvent only(List<RecordedEvent> events, String instanceId) {
        var matches = events.stream().filter(e -> e.getString("instanceId").equals(instanceId)).toList();
        assertThat(matches).as("exactly one event for instance " + instanceId).hasSize(1);
        return matches.getFirst();
    }
}
