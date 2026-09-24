package io.flowcatalyst.platform.bff.api;

import io.flowcatalyst.platform.audit.AuditLog;
import io.flowcatalyst.platform.audit.AuditLogRepository;
import io.flowcatalyst.platform.shared.TestHttp;
import io.flowcatalyst.platform.shared.auth.Authenticator;
import io.flowcatalyst.platform.shared.auth.ClaimsResolver;
import io.flowcatalyst.platform.shared.auth.JwtVerifier;
import io.flowcatalyst.platform.shared.auth.SigningKeys;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.shared.platformsink.PlatformSink;
import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import io.flowcatalyst.testpg.TestPg;
import tools.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// `POST /bff/audit-logs/redact-existing` end to end
/// (`docs/spec/audit-redaction.md` "Temporary: redact existing rows from
/// the dashboard"): a webhook-secret row and a SECRET config-value row are
/// redacted, a PLAIN value and an unrelated row are left byte-identical, the
/// counts are right, a second run redacts nothing, and the gate refuses a
/// non-anchor caller.
class AuditLogsBffTest {

    private static final AuditLogRepository AUDIT_LOGS = new AuditLogRepository(TestPg.dataSource());
    private static final UnitOfWork UOW = new UnitOfWork(TestPg.dataSource(), new PlatformSink(Json.MAPPER));
    private static TestHttp http;

    private static final String[] ANCHOR_WITH_AUDIT_VIEW = {
            Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
            Authenticator.TEST_SCOPE, "ANCHOR",
            Authenticator.TEST_PERMISSIONS, "platform:admin:audit-log:view"};
    /// Anchor-scoped but missing the audit-log permission (spec: anchor-only
    /// PLUS the read permission — anchor alone is not enough).
    private static final String[] ANCHOR_WITHOUT_AUDIT_VIEW = {
            Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
            Authenticator.TEST_SCOPE, "ANCHOR",
            Authenticator.TEST_PERMISSIONS, "platform:messaging:event-type:view"};
    /// Holds the audit-log permission but is CLIENT-scoped, not anchor.
    private static final String[] CLIENT_WITH_AUDIT_VIEW = {
            Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
            Authenticator.TEST_SCOPE, "CLIENT",
            Authenticator.TEST_CLIENTS, "clt_alb_test",
            Authenticator.TEST_PERMISSIONS, "platform:admin:audit-log:view"};

    @BeforeAll
    static void start() {
        var keys = SigningKeys.generateEphemeral();
        var verifier = new JwtVerifier(new JwtVerifier.Config("http://localhost:8080", new JwtVerifier.RsaKeys(keys.publicKey())));
        var auth = new Authenticator(verifier, ClaimsResolver.none(), Authenticator.Config.of(true));
        http = TestHttp.routes(routes -> {
            HttpError.install(routes);
            routes.before("/bff/*", auth);
            AuditLogsBff.register(routes, new AuditLogsBff.State(AUDIT_LOGS, UOW));
        });
    }

    @AfterAll
    static void stop() {
        http.close();
    }

    private static JsonNode json(HttpResponse<String> r) {
        try {
            return Json.MAPPER.readTree(r.body());
        } catch (Exception e) {
            throw new IllegalStateException("not JSON: " + r.body(), e);
        }
    }

    private static void seed(String id, String operation, String entityType, String operationJson) {
        AUDIT_LOGS.insertBatch(List.of(new AuditLog(id, entityType, id, operation,
                Json.MAPPER.readTree(operationJson), "prn_TESTACTOR0000001", null, null, null, Instant.now())));
    }

    /// The full script in one test (this class's database, like every
    /// `TestPg` class, is shared across `@Test` methods — a fresh row-count
    /// assertion split across methods would be order-dependent).
    @Test
    void redactsSecretsLeavesPlainAndUnrelatedRowsUntouchedAndIsIdempotent() {
        String webhookRowId = EntityType.AUDIT_LOG.generate();
        String secretConfigRowId = EntityType.AUDIT_LOG.generate();
        String plainConfigRowId = EntityType.AUDIT_LOG.generate();
        String unrelatedRowId = EntityType.AUDIT_LOG.generate();
        String unrelatedOperationJson = "{\"name\":\"some-role\",\"permissions\":[\"a\",\"b\"]}";

        seed(webhookRowId, "CreateCommand", "ServiceAccount",
                "{\"code\":\"sa-x\",\"webhookCredentials\":{\"authType\":\"BEARER_TOKEN\",\"token\":\"leaked-token\"}}");
        seed(secretConfigRowId, "SetPropertyCommand", "PlatformConfig",
                "{\"applicationCode\":\"app\",\"property\":\"key\",\"value\":\"leaked-secret\",\"valueType\":\"SECRET\"}");
        seed(plainConfigRowId, "SetPropertyCommand", "PlatformConfig",
                "{\"applicationCode\":\"app\",\"property\":\"plain\",\"value\":\"visible\",\"valueType\":\"PLAIN\"}");
        seed(unrelatedRowId, "RoleCreated", "Role", unrelatedOperationJson);
        // "token" only in a VALUE: the SQL pre-filter (a superset) picks the row up, the exact
        // rule leaves it alone — scanned, not redacted.
        String lookalikeRowId = EntityType.AUDIT_LOG.generate();
        String lookalikeJson = "{\"description\":\"rotate the token monthly\"}";
        seed(lookalikeRowId, "UpdateCommand", "Application", lookalikeJson);

        var first = http.post("/bff/audit-logs/redact-existing", "{}", ANCHOR_WITH_AUDIT_VIEW);
        assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
        assertThat(json(first).get("scanned").asInt())
                .as("candidates only: the webhook, both config rows and the look-alike — not the unrelated row")
                .isEqualTo(4);
        assertThat(json(first).get("redacted").asInt()).as("only the webhook and SECRET rows changed").isEqualTo(2);

        assertThat(AUDIT_LOGS.findById(webhookRowId).orElseThrow().operationJson().toString())
                .as("the webhook token never survives the sweep")
                .doesNotContain("leaked-token")
                .contains("\"token\":\"***\"");
        assertThat(AUDIT_LOGS.findById(secretConfigRowId).orElseThrow().operationJson().toString())
                .as("the SECRET config value never survives the sweep")
                .doesNotContain("leaked-secret")
                .contains("\"value\":\"***\"");
        assertThat(AUDIT_LOGS.findById(plainConfigRowId).orElseThrow().operationJson().get("value").asString())
                .as("a PLAIN value is left alone").isEqualTo("visible");
        assertThat(AUDIT_LOGS.findById(lookalikeRowId).orElseThrow().operationJson().toString())
                .as("a secret-looking word in a value is not a secret key").isEqualTo(Json.MAPPER.readTree(lookalikeJson).toString());
        assertThat(AUDIT_LOGS.findById(unrelatedRowId).orElseThrow().operationJson().toString())
                .as("a row with no secret-shaped field is byte-identical, not merely equivalent")
                .isEqualTo(Json.MAPPER.readTree(unrelatedOperationJson).toString());

        // One audit row of its own for the sweep (spec: operation = RedactExistingAuditLogs).
        var ownRow = AUDIT_LOGS.findWithFilters(new AuditLogRepository.ListFilter(null, null, null, null, null, null), 500, 0)
                .stream().filter(r -> "RedactExistingAuditLogs".equals(r.operation())).findFirst();
        assertThat(ownRow).as("the sweep wrote its own audit row").isPresent();
        assertThat(ownRow.get().operationJson().get("scanned").asInt()).isEqualTo(4);
        assertThat(ownRow.get().operationJson().get("redacted").asInt()).isEqualTo(2);

        // Idempotent: a second run redacts nothing (mutant: update every row regardless of
        // change -> the byte-identical assertion above would already have failed, and this
        // would report redacted > 0 too).
        var second = http.post("/bff/audit-logs/redact-existing", "{}", ANCHOR_WITH_AUDIT_VIEW);
        assertThat(second.statusCode()).as(second.body()).isEqualTo(200);
        assertThat(json(second).get("redacted").asInt()).as("the second run redacts nothing").isEqualTo(0);
    }

    @Test
    void anchorAloneIsNotEnoughWithoutTheAuditLogPermission() {
        var r = http.post("/bff/audit-logs/redact-existing", "{}", ANCHOR_WITHOUT_AUDIT_VIEW);
        assertThat(r.statusCode()).isEqualTo(403);
        assertThat(json(r).get("error").asString()).isEqualTo("PERMISSION_REQUIRED");
    }

    @Test
    void theAuditLogPermissionAloneIsNotEnoughWithoutAnchor() {
        var r = http.post("/bff/audit-logs/redact-existing", "{}", CLIENT_WITH_AUDIT_VIEW);
        assertThat(r.statusCode()).isEqualTo(403);
        assertThat(json(r).get("error").asString()).isEqualTo("ANCHOR_REQUIRED");
    }
}
