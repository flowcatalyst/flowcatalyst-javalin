package io.flowcatalyst.platform.function.api;

import tools.jackson.databind.JsonNode;
import io.flowcatalyst.platform.application.Application;
import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.platform.application.ApplicationType;
import io.flowcatalyst.platform.client.ClientRepository;
import io.flowcatalyst.platform.function.ClientPolicyRepository;
import io.flowcatalyst.platform.function.FunctionHostRepository;
import io.flowcatalyst.platform.function.FunctionLimits;
import io.flowcatalyst.platform.function.FunctionRepository;
import io.flowcatalyst.platform.function.FunctionSettingsRepository;
import io.flowcatalyst.platform.function.FunctionVersionRepository;
import io.flowcatalyst.platform.function.TriggerObjectRepository;
import io.flowcatalyst.platform.function.artifact.Signatures;
import io.flowcatalyst.platform.function.operations.TriggerSync;
import io.flowcatalyst.platform.dispatchpool.DispatchPoolRepository;
import io.flowcatalyst.platform.scheduledjob.ScheduledJobRepository;
import io.flowcatalyst.platform.subscription.SubscriptionRepository;
import io.flowcatalyst.platform.shared.TestHttp;
import io.flowcatalyst.platform.shared.auth.Authenticator;
import io.flowcatalyst.platform.shared.auth.ClaimsResolver;
import io.flowcatalyst.platform.shared.auth.JwtVerifier;
import io.flowcatalyst.platform.shared.auth.SigningKeys;
import io.flowcatalyst.platform.shared.encryption.Decryption;
import io.flowcatalyst.platform.shared.encryption.Encryption;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.shared.platformsink.PlatformSink;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import io.flowcatalyst.testpg.TestPg;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/// X1 (spec `function-context.md` §4): a secret value is in **no** HTTP
/// response, `msg_events` row, `aud_logs` row, log line or `toString` —
/// set, replace and delete — and the stored `fn_secrets.value_ref` is not
/// the plaintext but decrypts to it. Every assertion searches for the
/// literal marker string across the ENTIRE captured artefact rather than a
/// specific field, so a mutant that leaks the value through a field this
/// test did not anticipate (a different response field, a different audit
/// column) still fails it.
@SuppressWarnings("deprecation")
class FunctionSettingsApiTest {

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);

    private static final ApplicationRepository applications = new ApplicationRepository(TestPg.dataSource());
    private static final ClientRepository clients = new ClientRepository(TestPg.dataSource());
    private static final FunctionRepository functions = new FunctionRepository(TestPg.dataSource());
    private static final FunctionVersionRepository versions = new FunctionVersionRepository(TestPg.dataSource());
    private static final FunctionHostRepository hosts = new FunctionHostRepository(TestPg.dataSource());
    private static final ClientPolicyRepository policies = new ClientPolicyRepository(TestPg.dataSource());
    private static final TriggerObjectRepository triggerObjects = new TriggerObjectRepository(TestPg.dataSource());
    private static final SubscriptionRepository subscriptions = new SubscriptionRepository(TestPg.dataSource());
    private static final DispatchPoolRepository dispatchPools = new DispatchPoolRepository(TestPg.dataSource());
    private static final ScheduledJobRepository scheduledJobs = new ScheduledJobRepository(TestPg.dataSource());
    // A real key — this test class exists to prove a plaintext value never leaks WHEN
    // encryption is configured; FunctionApiTest#secretRoutesAre503... covers the unconfigured case (X4).
    private static final Encryption ENCRYPTION = Encryption.withKey(Encryption.generateKey());
    private static final FunctionSettingsRepository settings =
            new FunctionSettingsRepository(TestPg.dataSource(), Optional.of(ENCRYPTION));
    private static final UnitOfWork uow = new UnitOfWork(TestPg.dataSource(), new PlatformSink(Json.MAPPER));
    private static final DSLContext DB = DSL.using(TestPg.dataSource(), SQLDialect.POSTGRES);

    private static final String[] ANCHOR = {
            Authenticator.TEST_PRINCIPAL, "usr_anchor_" + RUN, Authenticator.TEST_SCOPE, "ANCHOR",
            Authenticator.TEST_PERMISSIONS, "platform:*:*:*"};

    private static TestHttp http;

    @BeforeAll
    static void start() {
        var keys = SigningKeys.generateEphemeral();
        var verifier = new JwtVerifier(new JwtVerifier.Config("http://localhost:8080", new JwtVerifier.RsaKeys(keys.publicKey())));
        var auth = new Authenticator(verifier, ClaimsResolver.none(), Authenticator.Config.of(true));
        http = TestHttp.routes(routes -> {
            HttpError.install(routes);
            routes.before("/api/*", auth);
            FunctionApi.register(routes, new FunctionApi.State(functions, applications, clients, uow, versions, hosts,
                    policies, FunctionLimits.defaults(), new Signatures.Off(), TriggerSync.none(), triggerObjects,
                    subscriptions, dispatchPools, scheduledJobs, settings, Optional.of(ENCRYPTION)));
        });
    }

    @AfterAll
    static void stop() {
        http.close();
    }

    // ── Log capture: one appender for the whole test, checked per-test ──────

    private ch.qos.logback.classic.Logger rootLogger;
    private ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> captured;

    @BeforeEach
    void captureLogs() {
        rootLogger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(
                ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
        captured = new ch.qos.logback.core.read.ListAppender<>();
        captured.start();
        rootLogger.addAppender(captured);
    }

    @AfterEach
    void stopCapturingLogs() {
        rootLogger.detachAppender(captured);
    }

    // ── Fixtures ───────────────────────────────────────────────────────────

    private static JsonNode json(HttpResponse<String> r) {
        try {
            return Json.MAPPER.readTree(r.body());
        } catch (Exception e) {
            throw new IllegalStateException("not JSON: " + r.body(), e);
        }
    }

    private static String testApplication(String tag, String code) {
        Application a = Application.create(ApplicationType.APPLICATION, code, "Function Settings Api " + tag);
        uow.inTransaction(tx -> {
            applications.persist(a, tx.dbTx());
            return null;
        });
        return a.id();
    }

    private static String createFunction(String tag) {
        String appCode = "fs-" + tag + "-" + RUN;
        testApplication(tag, appCode);
        var r = http.post("/api/functions",
                "{\"applicationCode\":\"" + appCode + "\",\"serviceName\":\"svc\",\"name\":\"fn\",\"runtime\":\"jvm\"}", ANCHOR);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        return appCode + ".svc.fn";
    }

    private static String functionIdOf(String address) {
        return functions.findByAddress(io.flowcatalyst.platform.function.FunctionAddress.parse(address))
                .orElseThrow().id();
    }

    // ── The literal search: every HTTP response, every row, every log line ──

    /// Calls every `/api/functions/{address}*` read route this test class
    /// registers and returns their bodies together with every currently
    /// captured log line's formatted message — the full "no HTTP response,
    /// no log line" half of X1's search.
    private static List<String> everyHttpResponseBodyAndLogLine(String address,
            ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> captured) {
        List<String> out = new java.util.ArrayList<>();
        out.add(http.get("/api/functions/" + address, ANCHOR).body());
        out.add(http.get("/api/functions", ANCHOR).body());
        out.add(http.get("/api/functions/" + address + "/config", ANCHOR).body());
        out.add(http.get("/api/functions/" + address + "/secrets", ANCHOR).body());
        out.add(http.get("/api/functions/" + address + "/status", ANCHOR).body());
        out.add(http.get("/api/functions/" + address + "/versions", ANCHOR).body());
        out.add(http.get("/api/functions/" + address + "/aliases", ANCHOR).body());
        out.add(http.get("/api/function-pools", ANCHOR).body());
        for (var e : captured.list) {
            out.add(e.getFormattedMessage());
            // MDC-carried values too — a marker stuffed into an MDC key would otherwise slip past.
            out.add(String.valueOf(e.getMDCPropertyMap()));
        }
        return out;
    }

    /// Every `msg_events` row, every text-bearing column concatenated —
    /// broader than any one column name this test happens to know about.
    private static List<String> everyMsgEventsRowAsText() {
        Result<Record> rows = DB.fetch("""
                SELECT id, spec_version, type, source, subject, data::text AS data,
                       correlation_id, causation_id, deduplication_id, message_group,
                       client_id, context_data::text AS context_data
                FROM msg_events
                """);
        return rowsAsText(rows);
    }

    /// Every `aud_logs` row, every text-bearing column concatenated —
    /// `operation_json` is where a command's serialised form lands.
    private static List<String> everyAuditRowAsText() {
        Result<Record> rows = DB.fetch("""
                SELECT id, entity_type, entity_id, operation, operation_json::text AS operation_json,
                       principal_id, application_id, client_id
                FROM aud_logs
                """);
        return rowsAsText(rows);
    }

    private static List<String> rowsAsText(Result<Record> rows) {
        List<String> out = new java.util.ArrayList<>();
        for (Record r : rows) {
            for (int i = 0; i < r.size(); i++) {
                Object v = r.get(i);
                if (v != null) {
                    out.add(v.toString());
                }
            }
        }
        return out;
    }

    private static void assertNowhere(String marker, List<String> haystacks, String label) {
        for (String h : haystacks) {
            assertThat(h).as("mutant: the secret leaked into " + label).doesNotContain(marker);
        }
    }

    /// Every artefact the marker must be absent from, for one point in the
    /// test's timeline: HTTP responses (a fresh round of reads), every
    /// `msg_events`/`aud_logs` row (the WHOLE table — cheap here, since this
    /// test's fixtures are the only rows a fresh embedded database has), and
    /// every log line captured so far.
    private void assertMarkerNowhere(String marker, String address) {
        assertNowhere(marker, everyHttpResponseBodyAndLogLine(address, captured), "an HTTP response or a log line");
        assertNowhere(marker, everyMsgEventsRowAsText(), "a msg_events row");
        assertNowhere(marker, everyAuditRowAsText(), "an aud_logs row");
    }

    // ── X1: set ──────────────────────────────────────────────────────────

    @Test
    void x1SetReplaceAndDeleteNeverLeakTheSecretValueAnywhere() {
        String address = createFunction("x1");
        String functionId = functionIdOf(address);
        String key = "API_KEY";

        String markerSet = "X1-SET-" + UUID.randomUUID();
        var put1 = http.put("/api/functions/" + address + "/secrets/" + key,
                "{\"value\":\"" + markerSet + "\"}", ANCHOR);
        assertThat(put1.statusCode()).as(put1.body()).isEqualTo(204);
        assertThat(put1.body()).as("mutant: echo the value back in the 204 body").isEmpty();

        assertMarkerNowhere(markerSet, address);
        assertStoredAndDecryptable(functionId, key, markerSet);

        // ── replace ──────────────────────────────────────────────────────
        String markerReplace = "X1-REPLACE-" + UUID.randomUUID();
        var put2 = http.put("/api/functions/" + address + "/secrets/" + key,
                "{\"value\":\"" + markerReplace + "\"}", ANCHOR);
        assertThat(put2.statusCode()).as(put2.body()).isEqualTo(204);

        assertMarkerNowhere(markerReplace, address);
        assertMarkerNowhere(markerSet, address); // the old value must not linger anywhere either
        assertStoredAndDecryptable(functionId, key, markerReplace);
        assertThat(decryptStored(functionId, key)).as("mutant: replace appends instead of overwriting")
                .isEqualTo(markerReplace);

        // ── delete ───────────────────────────────────────────────────────
        var del = http.delete("/api/functions/" + address + "/secrets/" + key, ANCHOR);
        assertThat(del.statusCode()).as(del.body()).isEqualTo(204);

        assertMarkerNowhere(markerReplace, address);
        assertThat(settings.hasSecret(functionId, key)).as("mutant: delete leaves the row behind").isFalse();

        // A second delete of the same (now absent) key is a 404, not a silent 204.
        var del2 = http.delete("/api/functions/" + address + "/secrets/" + key, ANCHOR);
        assertThat(del2.statusCode()).isEqualTo(404);
    }

    /// `fn_secrets.value_ref` is not the plaintext, but decrypting it gives
    /// the plaintext back (spec X1's second half).
    private static void assertStoredAndDecryptable(String functionId, String key, String expectedPlaintext) {
        String stored = DB.fetchOne("SELECT value_ref FROM fn_secrets WHERE function_id = ? AND key = ?",
                functionId, key).get(0, String.class);
        assertThat(stored).as("mutant: store plaintext instead of encrypting").doesNotContain(expectedPlaintext);
        assertThat(decryptStored(functionId, key))
                .as("mutant: the stored ref does not actually decrypt back to the value").isEqualTo(expectedPlaintext);
    }

    // ── secret value limits (spec §1: "value ≤ 8 KiB, non-empty") ──────────

    @Test
    void secretValueOverTheByteLimitIs400SettingTooLarge() {
        String address = createFunction("secbig");
        String tooLong = "x".repeat(io.flowcatalyst.platform.function.operations.SetFunctionSecret.MAX_VALUE_BYTES + 1);

        var r = http.put("/api/functions/" + address + "/secrets/BIG", "{\"value\":\"" + tooLong + "\"}", ANCHOR);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(400);
        assertThat(json(r).get("error").asString()).isEqualTo("SETTING_TOO_LARGE");
    }

    @Test
    void secretEmptyValueIs400SettingValueRequired() {
        String address = createFunction("secempty");

        var r = http.put("/api/functions/" + address + "/secrets/EMPTY", "{\"value\":\"\"}", ANCHOR);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(400);
        assertThat(json(r).get("error").asString()).isEqualTo("SETTING_VALUE_REQUIRED");
    }

    private static String decryptStored(String functionId, String key) {
        String stored = DB.fetchOne("SELECT value_ref FROM fn_secrets WHERE function_id = ? AND key = ?",
                functionId, key).get(0, String.class);
        return switch (ENCRYPTION.decrypt(stored)) {
            case Decryption.Plaintext(var pt) -> pt;
            case Decryption.External _, Decryption.Failed _ -> throw new AssertionError("value_ref did not decrypt: " + stored);
        };
    }
}
