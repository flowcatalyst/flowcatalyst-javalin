package io.flowcatalyst.platform.platformconfig.operations;

import tools.jackson.databind.JsonNode;
import io.flowcatalyst.platform.platformconfig.ConfigCoordinate;
import io.flowcatalyst.platform.platformconfig.ConfigScope;
import io.flowcatalyst.platform.platformconfig.ConfigValueType;
import io.flowcatalyst.platform.platformconfig.PlatformConfig;
import io.flowcatalyst.platform.platformconfig.PlatformConfigRepository;
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

/// The platform-config use cases against the embedded Postgres
/// (`docs/spec/config-permissions.md` §A): validation, the
/// `CONFIG_MANAGE`-gated authorization of `SetProperty`, persistence
/// (upsert-by-natural-key), and the envelope's guarantee that a write lands
/// together with its `msg_events` and `aud_logs` rows. The access-grant
/// aggregate (`GrantAccess`/`RevokeAccess`) is withdrawn by this spec —
/// permissions always come from roles, never a per-application grant table.
///
/// The fixture never truncates, so every test owns its rows: application
/// codes are namespaced by a per-JVM suffix.
@SuppressWarnings("deprecation")
class PlatformConfigOperationsTest {

    private static final DataSource DS = TestPg.dataSource();
    private static final DSLContext DB = DSL.using(DS, SQLDialect.POSTGRES);
    private static final PlatformConfigRepository configs = new PlatformConfigRepository(DS);
    private static final UnitOfWork uow = new UnitOfWork(DS, new PlatformSink(Json.MAPPER));

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);
    private static final String PRINCIPAL = EntityType.PRINCIPAL.generate();
    /// Holds `CONFIG_MANAGE` (the seeded `platform:admin` shape, spec §A.4) —
    /// the fixture's "may write" principal. Scope is irrelevant to the gate
    /// now (permissions always come from roles); kept ANCHOR only because
    /// that is what the audit rows below were already asserting on.
    private static final AuthContext MANAGER = new AuthContext(PRINCIPAL, Scope.ANCHOR, "manager@x.io",
            List.of("*"), List.of(), List.of(), true, List.of("platform:admin:config:manage"));
    private static final ExecutionContext EC = ExecutionContext.of(PRINCIPAL);

    // ── Fixture ────────────────────────────────────────────────────────────

    private static <C, E extends DomainEvent> E runAsManager(Operation<C, E> op, C cmd) {
        return Auth.runAs(MANAGER, () -> op.run(uow, cmd, EC));
    }

    /// A CLIENT-scoped principal holding exactly `permissions`.
    private static AuthContext clientWithPermissions(String... permissions) {
        return new AuthContext(EntityType.PRINCIPAL.generate(), Scope.CLIENT, "c@x.io", List.of("cli_" + RUN),
                List.of(), List.of(), true, List.of(permissions));
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

    private static PropertySet setAsManager(SetPropertyCommand cmd) {
        return runAsManager(SetProperty.of(configs), cmd);
    }

    private static PlatformConfig reload(String id) {
        return configs.findById(id).orElseThrow(() -> new AssertionError("config " + id + " not found"));
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
        var ev = setAsManager(new SetPropertyCommand(app, "smtp", "host", "mail.example.com", null, "SMTP relay host", null));

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
        // docs/spec/audit-redaction.md: no valueType means "keep the current type", which may be
        // SECRET, so the value is masked unless the command says PLAIN explicitly (an explicit
        // PLAIN value is still recorded — PlatformConfigApiTest pins that).
        assertThat(opJson.get("value").asText()).isEqualTo("***");
    }

    /// A second set on the same coordinate updates the row in place.
    @Test
    void setOnAnExistingCoordinateUpdatesInPlace() {
        String app = app("pcupsert");
        var first = setAsManager(set(app, "smtp", "password", "hunter2"));
        var second = setAsManager(new SetPropertyCommand(app, "smtp", "password", "correct-horse", "SECRET", null, null));
        assertThat(second.configId()).as("upsert reuses the row's id").isEqualTo(first.configId());

        var got = reload(first.configId());
        assertThat(got.value()).isEqualTo("correct-horse");
        assertThat(got.valueType()).isEqualTo(ConfigValueType.SECRET);

        var third = setAsManager(set(app, "smtp", "password", "again"));
        assertThat(third.configId()).isEqualTo(first.configId());
        assertThat(reload(first.configId()).valueType()).as("absent valueType keeps SECRET").isEqualTo(ConfigValueType.SECRET);
        assertThat(eventsFor(first.configId(), PlatformConfigEvents.PROPERTY_SET)).hasSize(3);
    }

    @Test
    void setForAClientIsASeparateClientScopedCoordinate() {
        String app = app("pcclient");
        var global = setAsManager(set(app, "ui", "colour", "blue"));
        var client = setAsManager(new SetPropertyCommand(app, "ui", "colour", "red", null, null, "cli_" + RUN));
        assertThat(client.configId()).isNotEqualTo(global.configId());

        var got = reload(client.configId());
        assertThat(got.scope()).isEqualTo(ConfigScope.CLIENT);
        assertThat(got.clientId()).isEqualTo("cli_" + RUN);
        assertThat(configs.findByCoordinate(ConfigCoordinate.global(app, "ui", "colour")).map(PlatformConfig::value)).contains("blue");
        assertThat(configs.findByCoordinate(ConfigCoordinate.of(app, "ui", "colour", "cli_" + RUN)).map(PlatformConfig::value)).contains("red");
        assertThat(configs.findByApplication(app)).extracting(PlatformConfig::id).containsExactlyInAnyOrder(global.configId(), client.configId());
    }

    /// Owner ruling 2026-09-06 #19 (X-06 at the wire): an unknown valueType is refused before anything is read or written.
    @Test
    void unknownValueTypeIsRefused() {
        String app = app("pcbanana");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> setAsManager(new SetPropertyCommand(app, "s", "p", "v", "BANANA", null, null)))
                .isInstanceOf(io.flowcatalyst.sdk.usecase.UseCaseException.class)
                .extracting(t -> ((io.flowcatalyst.sdk.usecase.UseCaseException) t).error().code())
                .isEqualTo("INVALID_VALUE_TYPE");
        assertThat(configs.findByCoordinate(ConfigCoordinate.global(app, "s", "p"))).isEmpty();
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
        assertUseCaseError(() -> setAsManager(cmd), UseCaseError.Validation.class, "FIELD_REQUIRED");
        assertThatThrownBy(() -> setAsManager(cmd)).hasMessageContaining(message);
    }

    @Test
    void setAcceptsAnEmptyValue() {
        var ev = setAsManager(set(app("pcempty"), "s", "p", ""));
        assertThat(reload(ev.configId()).value()).isEmpty();
    }

    /// The use case's authorization (spec §A.2, "Error handling"): `CONFIG_MANAGE`
    /// gates the write, checked in the authorize phase — never scope, never a
    /// per-application grant. `CONFIG_VIEW` alone, no permission at all, and no
    /// principal are all refused before anything is written; `CONFIG_MANAGE`
    /// succeeds for any application (spec §A.2's "no per-application restriction").
    @Test
    void setRequiresConfigManagePermission() {
        String app = app("pcauthz");

        assertUseCaseError(() -> runAs(clientWithPermissions(), SetProperty.of(configs), set(app, "s", "noperm", "x")),
                UseCaseError.Authorization.class, "PERMISSION_REQUIRED");
        assertUseCaseError(() -> runAs(clientWithPermissions("platform:admin:config:view"), SetProperty.of(configs), set(app, "s", "viewonly", "x")),
                UseCaseError.Authorization.class, "PERMISSION_REQUIRED");
        assertThatThrownBy(() -> runAs(clientWithPermissions("platform:admin:config:view"), SetProperty.of(configs), set(app, "s", "viewonly", "x")))
                .hasMessageContaining("platform:admin:config:manage");
        assertUseCaseError(() -> SetProperty.of(configs).run(uow, set(app, "s", "anon", "x"), ExecutionContext.of(null)),
                UseCaseError.Authorization.class, "UNAUTHENTICATED");
        assertThat(configs.findByApplication(app)).isEmpty();

        var ev = runAs(clientWithPermissions("platform:admin:config:manage"), SetProperty.of(configs), set(app, "s", "granted", "x"));
        assertThat(reload(ev.configId()).value()).isEqualTo("x");
    }
}
