package io.flowcatalyst.platform.platformconfig.operations;

import com.fasterxml.jackson.databind.JsonNode;
import io.flowcatalyst.platform.platformconfig.ConfigAccess;
import io.flowcatalyst.platform.platformconfig.ConfigAccessRepository;
import io.flowcatalyst.platform.platformconfig.ConfigCoordinate;
import io.flowcatalyst.platform.platformconfig.ConfigScope;
import io.flowcatalyst.platform.platformconfig.ConfigValueType;
import io.flowcatalyst.platform.platformconfig.PlatformConfig;
import io.flowcatalyst.platform.platformconfig.PlatformConfigRepository;
import io.flowcatalyst.platform.platformconfig.operations.PlatformConfigEvents.AccessGranted;
import io.flowcatalyst.platform.platformconfig.operations.PlatformConfigEvents.PropertySet;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.AuthContext;
import io.flowcatalyst.platform.shared.auth.Scope;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.shared.platformsink.PlatformSink;
import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.sdk.usecase.DomainEvent;
import io.flowcatalyst.sdk.usecase.ExecutionContext;
import io.flowcatalyst.sdk.usecase.UseCaseError;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.testpg.TestPg;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.sql.DataSource;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The platform-config use cases against the embedded Postgres (spec §5–9):
/// validation, the grant-based authorization of `SetProperty`, persistence
/// (upsert-by-natural-key for both aggregates), and the envelope's guarantee
/// that a write lands together with its `msg_events` and `aud_logs` rows.
///
/// The fixture never truncates, so every test owns its rows: application
/// codes are namespaced by a per-JVM suffix.
class PlatformConfigOperationsTest {

    private static final DataSource DS = TestPg.dataSource();
    private static final DSLContext DB = DSL.using(DS, SQLDialect.POSTGRES);
    private static final PlatformConfigRepository configs = new PlatformConfigRepository(DS);
    private static final ConfigAccessRepository grants = new ConfigAccessRepository(DS);
    private static final UnitOfWork uow = new UnitOfWork(DS, new PlatformSink(Json.MAPPER));

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);
    private static final String PRINCIPAL = EntityType.PRINCIPAL.generate();
    private static final AuthContext ANCHOR = new AuthContext(PRINCIPAL, Scope.ANCHOR, "anchor@x.io",
            List.of("*"), List.of(), List.of(), true, List.of());
    private static final ExecutionContext EC = ExecutionContext.of(PRINCIPAL);

    // ── Fixture ────────────────────────────────────────────────────────────

    private static <C, E extends DomainEvent> E runAsAnchor(Operation<C, E> op, C cmd) {
        return Auth.runAs(ANCHOR, () -> op.run(uow, cmd, EC));
    }

    /// A CLIENT-scoped principal holding `roles` (no anchor, no permissions).
    private static AuthContext clientWithRoles(String... roles) {
        return new AuthContext(EntityType.PRINCIPAL.generate(), Scope.CLIENT, "c@x.io", List.of("cli_" + RUN),
                List.of(roles), List.of(), true, List.of());
    }

    private static <C, E extends DomainEvent> E runAs(AuthContext ac, Operation<C, E> op, C cmd) {
        return Auth.runAs(ac, () -> op.run(uow, cmd, ExecutionContext.of(ac.principalId())));
    }

    private static String app(String tag) {
        return tag + RUN;
    }

    private static SetPropertyCommand set(String app, String section, String property, String value) {
        return new SetPropertyCommand(app, section, property, value, null, null, null);
    }

    private static PropertySet setAsAnchor(SetPropertyCommand cmd) {
        return runAsAnchor(SetProperty.of(configs, grants), cmd);
    }

    private static AccessGranted granted(String app, String role, boolean canWrite) {
        return runAsAnchor(GrantAccess.of(grants), new GrantAccessCommand(app, role, canWrite));
    }

    private static PlatformConfig reload(String id) {
        return configs.findById(id).orElseThrow(() -> new AssertionError("config " + id + " not found"));
    }

    private static ConfigAccess reloadGrant(String id) {
        return grants.findById(id).orElseThrow(() -> new AssertionError("grant " + id + " not found"));
    }

    private static void assertUseCaseError(ThrowingCallable call, Class<? extends UseCaseError> kind, String code) {
        assertThatThrownBy(call)
                .isInstanceOf(UseCaseException.class)
                .extracting(t -> ((UseCaseException) t).error())
                .satisfies(err -> {
                    assertThat(err).as("error kind").isInstanceOf(kind);
                    assertThat(err.code()).as("error code").isEqualTo(code);
                });
    }

    private static JsonNode json(String s) {
        try {
            return Json.MAPPER.readTree(s);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    /// `msg_events` rows of one type on the aggregate's subject.
    private static Result<Record> eventsFor(String id, String type) {
        return DB.fetch("SELECT type, subject, source, message_group, data::text AS data, deduplication_id FROM msg_events WHERE subject = ? AND type = ?",
                PlatformConfigEvents.subjectFor(id), type);
    }

    /// `aud_logs` rows for one aggregate and command.
    private static Result<Record> auditsFor(String id, String operation) {
        return DB.fetch("SELECT entity_type, entity_id, operation, operation_json::text AS operation_json, principal_id FROM aud_logs WHERE entity_id = ? AND operation = ?",
                id, operation);
    }

    // ── SetProperty ────────────────────────────────────────────────────────

    @Test
    void setWritesTheRowTheEventAndTheAuditTogether() {
        String app = app("pcset");
        var ev = setAsAnchor(new SetPropertyCommand(app, "smtp", "host", "mail.example.com", null, "SMTP relay host", null));

        assertThat(ev.configId()).startsWith("pcf_");
        assertThat(ev.applicationCode()).isEqualTo(app);
        assertThat(ev.section()).isEqualTo("smtp");
        assertThat(ev.property()).isEqualTo("host");
        assertThat(ev.eventType()).isEqualTo(PlatformConfigEvents.PROPERTY_SET);
        assertThat(ev.source()).isEqualTo(PlatformConfigEvents.SOURCE);
        assertThat(ev.subject()).isEqualTo(PlatformConfigEvents.subjectFor(ev.configId()));
        assertThat(ev.messageGroup()).isEqualTo(PlatformConfigEvents.messageGroupFor(ev.configId()));

        var got = reload(ev.configId());
        assertThat(got.value()).isEqualTo("mail.example.com");
        assertThat(got.scope()).as("no clientId → GLOBAL").isEqualTo(ConfigScope.GLOBAL);
        assertThat(got.clientId()).isNull();
        assertThat(got.valueType()).as("defaults to PLAIN").isEqualTo(ConfigValueType.PLAIN);
        assertThat(got.description()).isEqualTo("SMTP relay host");
        assertThat(configs.findByCoordinate(ConfigCoordinate.global(app, "smtp", "host"))).contains(got);

        var events = eventsFor(ev.configId(), PlatformConfigEvents.PROPERTY_SET);
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().get("source")).isEqualTo(PlatformConfigEvents.SOURCE);
        assertThat(events.getFirst().get("message_group")).isEqualTo(PlatformConfigEvents.messageGroupFor(ev.configId()));
        assertThat(events.getFirst().get("deduplication_id")).isEqualTo(PlatformConfigEvents.PROPERTY_SET + "-" + ev.eventId());
        var data = json(events.getFirst().get("data", String.class));
        assertThat(data.get("configId").asText()).isEqualTo(ev.configId());
        assertThat(data.get("applicationCode").asText()).isEqualTo(app);
        assertThat(data.get("section").asText()).isEqualTo("smtp");
        assertThat(data.get("property").asText()).isEqualTo("host");
        assertThat(data.has("value")).as("the payload never carries the value").isFalse();

        var audits = auditsFor(ev.configId(), "SetPropertyCommand");
        assertThat(audits).hasSize(1);
        assertThat(audits.getFirst().get("entity_type")).isEqualTo("Platformconfig");
        assertThat(audits.getFirst().get("principal_id")).isEqualTo(PRINCIPAL);
        var opJson = json(audits.getFirst().get("operation_json", String.class));
        assertThat(opJson.get("applicationCode").asText()).isEqualTo(app);
        assertThat(opJson.get("value").asText()).isEqualTo("mail.example.com");
    }

    /// A second set on the same coordinate updates the row in place.
    @Test
    void setOnAnExistingCoordinateUpdatesInPlace() {
        String app = app("pcupsert");
        var first = setAsAnchor(set(app, "smtp", "password", "hunter2"));
        var second = setAsAnchor(new SetPropertyCommand(app, "smtp", "password", "correct-horse", "SECRET", null, null));
        assertThat(second.configId()).as("upsert reuses the row's id").isEqualTo(first.configId());

        var got = reload(first.configId());
        assertThat(got.value()).isEqualTo("correct-horse");
        assertThat(got.valueType()).isEqualTo(ConfigValueType.SECRET);

        var third = setAsAnchor(set(app, "smtp", "password", "again"));
        assertThat(third.configId()).isEqualTo(first.configId());
        assertThat(reload(first.configId()).valueType()).as("absent valueType keeps SECRET").isEqualTo(ConfigValueType.SECRET);
        assertThat(eventsFor(first.configId(), PlatformConfigEvents.PROPERTY_SET)).hasSize(3);
    }

    @Test
    void setForAClientIsASeparateClientScopedCoordinate() {
        String app = app("pcclient");
        var global = setAsAnchor(set(app, "ui", "colour", "blue"));
        var client = setAsAnchor(new SetPropertyCommand(app, "ui", "colour", "red", null, null, "cli_" + RUN));
        assertThat(client.configId()).isNotEqualTo(global.configId());

        var got = reload(client.configId());
        assertThat(got.scope()).isEqualTo(ConfigScope.CLIENT);
        assertThat(got.clientId()).isEqualTo("cli_" + RUN);
        assertThat(configs.findByCoordinate(ConfigCoordinate.global(app, "ui", "colour")).map(PlatformConfig::value)).contains("blue");
        assertThat(configs.findByCoordinate(ConfigCoordinate.of(app, "ui", "colour", "cli_" + RUN)).map(PlatformConfig::value)).contains("red");
        assertThat(configs.findByApplication(app)).extracting(PlatformConfig::id).containsExactlyInAnyOrder(global.configId(), client.configId());
    }

    @Test
    void unknownValueTypeIsStoredAsPlain() {
        var ev = setAsAnchor(new SetPropertyCommand(app("pcbanana"), "s", "p", "v", "BANANA", null, null));
        assertThat(reload(ev.configId()).valueType()).isEqualTo(ConfigValueType.PLAIN);
    }

    static Stream<Arguments> malformedSetCommands() {
        return Stream.of(
                Arguments.of("missing applicationCode", new SetPropertyCommand(null, "s", "p", "v", null, null, null), "applicationCode is required"),
                Arguments.of("blank applicationCode", new SetPropertyCommand("  ", "s", "p", "v", null, null, null), "applicationCode is required"),
                Arguments.of("missing section", new SetPropertyCommand("a", null, "p", "v", null, null, null), "section is required"),
                Arguments.of("missing property", new SetPropertyCommand("a", "s", "", "v", null, null, null), "property is required"),
                Arguments.of("missing value", new SetPropertyCommand("a", "s", "p", null, null, null, null), "value is required"));
    }

    @ParameterizedTest(name = "{0} → FIELD_REQUIRED")
    @MethodSource("malformedSetCommands")
    void setRejectsAMalformedCommand(String label, SetPropertyCommand cmd, String message) {
        assertUseCaseError(() -> setAsAnchor(cmd), UseCaseError.Validation.class, "FIELD_REQUIRED");
        assertThatThrownBy(() -> setAsAnchor(cmd)).hasMessageContaining(message);
    }

    @Test
    void setAcceptsAnEmptyValue() {
        var ev = setAsAnchor(set(app("pcempty"), "s", "p", ""));
        assertThat(reload(ev.configId()).value()).isEmpty();
    }

    /// The use case's authorization: anchors pass; a non-anchor needs a role
    /// with a write grant on the application — a read-only grant or no grant
    /// is refused before anything is written.
    @Test
    void setRequiresAnchorOrAWriteGrantOnTheApplication() {
        String app = app("pcauthz");
        granted(app, "pc-reader-" + RUN, false);
        granted(app, "pc-writer-" + RUN, true);

        assertUseCaseError(() -> runAs(clientWithRoles(), SetProperty.of(configs, grants), set(app, "s", "nogrant", "x")),
                UseCaseError.Authorization.class, "FORBIDDEN");
        assertUseCaseError(() -> runAs(clientWithRoles("pc-reader-" + RUN), SetProperty.of(configs, grants), set(app, "s", "readonly", "x")),
                UseCaseError.Authorization.class, "FORBIDDEN");
        assertThatThrownBy(() -> runAs(clientWithRoles("pc-reader-" + RUN), SetProperty.of(configs, grants), set(app, "s", "readonly", "x")))
                .hasMessageContaining("No write access to platform config for " + app);
        assertUseCaseError(() -> SetProperty.of(configs, grants).run(uow, set(app, "s", "anon", "x"), ExecutionContext.of(null)),
                UseCaseError.Authorization.class, "UNAUTHENTICATED");
        assertThat(configs.findByApplication(app)).isEmpty();

        var ev = runAs(clientWithRoles("other-" + RUN, "pc-writer-" + RUN), SetProperty.of(configs, grants), set(app, "s", "granted", "x"));
        assertThat(reload(ev.configId()).value()).isEqualTo("x");
    }

    @Test
    void grantChecksAnswerByRoleSet() {
        String app = app("pccheck");
        granted(app, "pc-r-" + RUN, false);
        granted(app, "pc-w-" + RUN, true);
        assertThat(grants.canRead(app, List.of("pc-r-" + RUN))).isTrue();
        assertThat(grants.canWrite(app, List.of("pc-r-" + RUN))).isFalse();
        assertThat(grants.canWrite(app, List.of("nope", "pc-w-" + RUN))).isTrue();
        assertThat(grants.canRead(app, List.of())).as("no roles never passes").isFalse();
        assertThat(grants.canRead(app("pcother"), List.of("pc-r-" + RUN))).as("grants are per application").isFalse();
    }

    // ── GrantAccess ────────────────────────────────────────────────────────

    @Test
    void grantWritesTheRowTheEventAndTheAuditTogether() {
        String app = app("pcgrant");
        var ev = granted(app, "auditor", false);

        assertThat(ev.accessId()).startsWith("cfa_");
        assertThat(ev.applicationCode()).isEqualTo(app);
        assertThat(ev.roleCode()).isEqualTo("auditor");
        assertThat(ev.canWrite()).isFalse();
        assertThat(ev.eventType()).isEqualTo(PlatformConfigEvents.ACCESS_GRANTED);
        assertThat(ev.messageGroup()).isEqualTo(PlatformConfigEvents.messageGroupFor(ev.accessId()));

        var got = reloadGrant(ev.accessId());
        assertThat(got.canRead()).as("a grant always confers read").isTrue();
        assertThat(got.canWrite()).isFalse();
        assertThat(grants.findByRole(app, "auditor")).contains(got);
        assertThat(grants.findByApplication(app)).containsExactly(got);

        var events = eventsFor(ev.accessId(), PlatformConfigEvents.ACCESS_GRANTED);
        assertThat(events).hasSize(1);
        var data = json(events.getFirst().get("data", String.class));
        assertThat(data.get("accessId").asText()).isEqualTo(ev.accessId());
        assertThat(data.get("roleCode").asText()).isEqualTo("auditor");
        assertThat(data.get("canWrite").asBoolean()).isFalse();

        var audits = auditsFor(ev.accessId(), "GrantAccessCommand");
        assertThat(audits).hasSize(1);
        assertThat(audits.getFirst().get("entity_type")).isEqualTo("Platformconfig");
        assertThat(json(audits.getFirst().get("operation_json", String.class)).get("roleCode").asText()).isEqualTo("auditor");
    }

    /// Granting the same (app, role) twice updates the existing grant in place.
    @Test
    void grantingTheSamePairAgainUpdatesInPlace() {
        String app = app("pcregrant");
        var first = granted(app, "operator", false);
        var second = granted(app, "operator", true);
        assertThat(second.accessId()).isEqualTo(first.accessId());
        assertThat(second.canWrite()).isTrue();
        assertThat(reloadGrant(first.accessId()).canWrite()).as("second grant escalates").isTrue();

        var third = granted(app, "operator", false);
        assertThat(third.accessId()).isEqualTo(first.accessId());
        assertThat(reloadGrant(first.accessId()).canWrite()).as("and de-escalates").isFalse();
        assertThat(reloadGrant(first.accessId()).canRead()).isTrue();
        assertThat(grants.findByApplication(app)).hasSize(1);
    }

    static Stream<Arguments> malformedGrantCommands() {
        return Stream.of(
                Arguments.of("missing applicationCode", new GrantAccessCommand(null, "r", false), "APPLICATION_REQUIRED"),
                Arguments.of("blank applicationCode", new GrantAccessCommand(" ", "r", false), "APPLICATION_REQUIRED"),
                Arguments.of("missing roleCode", new GrantAccessCommand("a", null, true), "ROLE_REQUIRED"),
                Arguments.of("blank roleCode", new GrantAccessCommand("a", "", true), "ROLE_REQUIRED"));
    }

    @ParameterizedTest(name = "{0} → {2}")
    @MethodSource("malformedGrantCommands")
    void grantRejectsAMalformedCommand(String label, GrantAccessCommand cmd, String expectedCode) {
        assertUseCaseError(() -> runAsAnchor(GrantAccess.of(grants), cmd), UseCaseError.Validation.class, expectedCode);
    }

    // ── RevokeAccess ───────────────────────────────────────────────────────

    @Test
    void revokeDeletesTheGrantWithEventAndAudit() {
        String app = app("pcrevoke");
        var seeded = granted(app, "ops", true);

        var ev = runAsAnchor(RevokeAccess.of(grants), new RevokeAccessCommand(seeded.accessId()));
        assertThat(ev.accessId()).isEqualTo(seeded.accessId());
        assertThat(ev.applicationCode()).isEqualTo(app);
        assertThat(ev.roleCode()).isEqualTo("ops");
        assertThat(ev.eventType()).isEqualTo(PlatformConfigEvents.ACCESS_REVOKED);

        assertThat(grants.findById(seeded.accessId())).as("revoked grant is gone").isEmpty();
        assertThat(grants.canWrite(app, List.of("ops"))).isFalse();

        var events = eventsFor(seeded.accessId(), PlatformConfigEvents.ACCESS_REVOKED);
        assertThat(events).hasSize(1);
        assertThat(json(events.getFirst().get("data", String.class)).get("roleCode").asText()).isEqualTo("ops");
        assertThat(auditsFor(seeded.accessId(), "RevokeAccessCommand")).hasSize(1);
    }

    @Test
    void revokeRejectsABlankIdAndAnUnknownGrant() {
        assertUseCaseError(() -> runAsAnchor(RevokeAccess.of(grants), new RevokeAccessCommand(null)),
                UseCaseError.Validation.class, "ID_REQUIRED");
        assertUseCaseError(() -> runAsAnchor(RevokeAccess.of(grants), new RevokeAccessCommand("cfa_doesnotexist1")),
                UseCaseError.NotFound.class, "PlatformConfigAccess_NOT_FOUND");
        assertThatThrownBy(() -> runAsAnchor(RevokeAccess.of(grants), new RevokeAccessCommand("cfa_doesnotexist1")))
                .hasMessageContaining("PlatformConfigAccess not found: cfa_doesnotexist1");
    }
}
