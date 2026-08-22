package io.flowcatalyst.platform.role.operations;

import com.fasterxml.jackson.databind.JsonNode;
import io.flowcatalyst.platform.role.Role;
import io.flowcatalyst.platform.role.RoleRepository;
import io.flowcatalyst.platform.role.RoleSource;
import io.flowcatalyst.platform.role.operations.RoleEvents.RoleCreated;
import io.flowcatalyst.platform.seed.PlatformRoles;
import io.flowcatalyst.platform.seed.RoleDefinition;
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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;

import static io.flowcatalyst.db.generated.Tables.IAM_PRINCIPALS;
import static io.flowcatalyst.db.generated.Tables.IAM_PRINCIPAL_ROLES;
import static io.flowcatalyst.db.generated.Tables.IAM_ROLES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The role use cases against the embedded Postgres (spec §4–9):
/// validation, the source invariant, the SDK sync's application-access
/// authorization, persistence, and the envelope's guarantee that a role
/// write lands together with its `msg_events` and `aud_logs` rows. The pure
/// rules are covered by `RoleTest`; here each operation is exercised once
/// through the envelope.
///
/// The fixture never truncates, so every test owns its rows: application
/// codes are namespaced by a per-JVM suffix.
class RoleOperationsTest {

    private static final DataSource DS = TestPg.dataSource();
    private static final DSLContext DB = DSL.using(DS, SQLDialect.POSTGRES);
    private static final RoleRepository repo = new RoleRepository(DS);
    private static final UnitOfWork uow = new UnitOfWork(DS, new PlatformSink(Json.MAPPER));

    /// Per-JVM namespace so names never collide with another run on the same database.
    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);
    private static final String PRINCIPAL = EntityType.PRINCIPAL.generate();
    private static final AuthContext ANCHOR = new AuthContext(PRINCIPAL, Scope.ANCHOR, "anchor@x.io",
            List.of("*"), List.of(), List.of(), true, List.of());
    private static final ExecutionContext EC = ExecutionContext.of(PRINCIPAL);

    // ── Fixture ────────────────────────────────────────────────────────────

    /// Drives `op` through the full envelope as an all-applications anchor —
    /// the common case; the SDK-sync authorization test binds its own context.
    private static <C, E extends DomainEvent> E runAsAnchor(Operation<C, E> op, C cmd) {
        return Auth.runAs(ANCHOR, () -> op.run(uow, cmd, EC));
    }

    /// `{tag}{RUN}` — the application code namespaces the test.
    private static String app(String tag) {
        return tag + RUN;
    }

    private static RoleCreated created(String applicationCode, String roleName, String displayName, String... permissions) {
        return runAsAnchor(CreateRole.of(repo),
                new CreateCommand(applicationCode, roleName, displayName, null, List.of(permissions), false));
    }

    private static Role reload(String id) {
        return repo.findById(id).orElseThrow(() -> new AssertionError("role " + id + " not found"));
    }

    private static Role byName(String name) {
        return repo.findByName(name).orElseThrow(() -> new AssertionError("role " + name + " not found"));
    }

    /// Inserts an `iam_roles` row directly: `CODE` rows are not creatable
    /// through the public operations, so the immutability guard can only be
    /// reached by seeding at the SQL level (as the seeder does).
    private static String seedRawRole(String name, String displayName, RoleSource source, String applicationId) {
        String id = EntityType.ROLE.generate();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        DB.insertInto(IAM_ROLES)
                .set(IAM_ROLES.ID, id)
                .set(IAM_ROLES.APPLICATION_ID, applicationId)
                .set(IAM_ROLES.NAME, name)
                .set(IAM_ROLES.DISPLAY_NAME, displayName)
                .set(IAM_ROLES.APPLICATION_CODE, name.substring(0, name.indexOf(':')))
                .set(IAM_ROLES.SOURCE, source.name())
                .set(IAM_ROLES.CLIENT_MANAGED, false)
                .set(IAM_ROLES.CREATED_AT, now)
                .set(IAM_ROLES.UPDATED_AT, now)
                .execute();
        return id;
    }

    /// A bare principal plus an `iam_principal_roles` assignment (the
    /// junction has no foreign key on `role_name`).
    private static String seedPrincipalHolding(String roleName) {
        String principalId = EntityType.PRINCIPAL.generate();
        DB.insertInto(IAM_PRINCIPALS)
                .set(IAM_PRINCIPALS.ID, principalId)
                .set(IAM_PRINCIPALS.TYPE, "USER")
                .set(IAM_PRINCIPALS.SCOPE, "PLATFORM")
                .set(IAM_PRINCIPALS.NAME, "Role Ops Test User")
                .set(IAM_PRINCIPALS.ACTIVE, true)
                .set(IAM_PRINCIPALS.EMAIL, principalId.toLowerCase(Locale.ROOT) + "@example.com")
                .execute();
        DB.insertInto(IAM_PRINCIPAL_ROLES)
                .set(IAM_PRINCIPAL_ROLES.PRINCIPAL_ID, principalId)
                .set(IAM_PRINCIPAL_ROLES.ROLE_NAME, roleName)
                .set(IAM_PRINCIPAL_ROLES.ASSIGNMENT_SOURCE, "MANUAL")
                .execute();
        return principalId;
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
    private static Result<Record> eventsFor(String roleId, String type) {
        return DB.fetch("SELECT type, subject, source, message_group, data::text AS data, deduplication_id FROM msg_events WHERE subject = ? AND type = ?",
                RoleEvents.subjectFor(roleId), type);
    }

    /// `aud_logs` rows for one aggregate and command.
    private static Result<Record> auditsFor(String roleId, String operation) {
        return DB.fetch("SELECT entity_type, entity_id, operation, operation_json::text AS operation_json, principal_id FROM aud_logs WHERE entity_id = ? AND operation = ?",
                roleId, operation);
    }

    // ── Create ─────────────────────────────────────────────────────────────

    @Test
    void createWritesTheRowThePermissionsTheEventAndTheAuditTogether() {
        String application = app("rolecrt");
        var ev = runAsAnchor(CreateRole.of(repo), new CreateCommand(application, "editor", "Document Editor",
                "Edits documents", List.of(application + ":doc:edit:*", application + ":doc:read:*", application + ":doc:edit:*"), true));

        assertThat(ev.roleId()).startsWith("rol_");
        assertThat(ev.name()).isEqualTo(application + ":editor");
        assertThat(ev.eventType()).isEqualTo(RoleEvents.CREATED);
        assertThat(ev.source()).isEqualTo(RoleEvents.SOURCE);
        assertThat(ev.subject()).isEqualTo(RoleEvents.subjectFor(ev.roleId()));
        assertThat(ev.messageGroup()).isEqualTo("platform:role:" + ev.roleId());

        var got = reload(ev.roleId());
        assertThat(got.name()).isEqualTo(application + ":editor");
        assertThat(got.displayName()).isEqualTo("Document Editor");
        assertThat(got.description()).isEqualTo("Edits documents");
        assertThat(got.applicationCode()).isEqualTo(application);
        assertThat(got.applicationId()).isNull();
        assertThat(got.source()).as("admin-created roles are DATABASE-sourced").isEqualTo(RoleSource.DATABASE);
        assertThat(got.clientManaged()).isTrue();
        assertThat(got.permissions()).as("de-duplicated and sorted")
                .containsExactly(application + ":doc:edit:*", application + ":doc:read:*");

        var events = eventsFor(ev.roleId(), RoleEvents.CREATED);
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().get("source")).isEqualTo(RoleEvents.SOURCE);
        assertThat(events.getFirst().get("message_group")).isEqualTo("platform:role:" + ev.roleId());
        assertThat(events.getFirst().get("deduplication_id")).isEqualTo(RoleEvents.CREATED + "-" + ev.eventId());
        var data = json(events.getFirst().get("data", String.class));
        assertThat(data.get("roleId").asText()).isEqualTo(ev.roleId());
        assertThat(data.get("name").asText()).isEqualTo(application + ":editor");
        assertThat(data.fieldNames()).toIterable().containsExactlyInAnyOrder("roleId", "name");

        var audits = auditsFor(ev.roleId(), "CreateCommand");
        assertThat(audits).hasSize(1);
        assertThat(audits.getFirst().get("entity_type")).isEqualTo("Role");
        assertThat(audits.getFirst().get("principal_id")).isEqualTo(PRINCIPAL);
        var opJson = json(audits.getFirst().get("operation_json", String.class));
        assertThat(opJson.get("applicationCode").asText()).isEqualTo(application);
        assertThat(opJson.get("roleName").asText()).isEqualTo("editor");
        assertThat(opJson.get("displayName").asText()).isEqualTo("Document Editor");
    }

    static Stream<Arguments> malformedCreateCommands() {
        return Stream.of(
                Arguments.of("missing application", new CreateCommand(null, "x", "X", null, null, false), "APPLICATION_REQUIRED"),
                Arguments.of("blank application", new CreateCommand("  ", "x", "X", null, null, false), "APPLICATION_REQUIRED"),
                Arguments.of("missing role name", new CreateCommand("app", null, "X", null, null, false), "ROLE_NAME_REQUIRED"),
                Arguments.of("missing display name", new CreateCommand("app", "x", " ", null, null, false), "DISPLAY_NAME_REQUIRED"));
    }

    @ParameterizedTest(name = "{0} → {2}")
    @MethodSource("malformedCreateCommands")
    void createRejectsAMalformedCommand(String label, CreateCommand cmd, String expectedCode) {
        assertUseCaseError(() -> runAsAnchor(CreateRole.of(repo), cmd), UseCaseError.Validation.class, expectedCode);
    }

    @Test
    void createRejectsADuplicateName() {
        String application = app("roledup");
        created(application, "editor", "First");
        assertThatThrownBy(() -> runAsAnchor(CreateRole.of(repo), new CreateCommand(application, "editor", "Second", null, null, false)))
                .isInstanceOf(UseCaseException.class)
                .extracting(t -> ((UseCaseException) t).error())
                .satisfies(err -> {
                    assertThat(err).isInstanceOf(UseCaseError.Conflict.class);
                    assertThat(err.code()).isEqualTo("ROLE_EXISTS");
                    assertThat(err.message()).isEqualTo("Role '" + application + ":editor' already exists");
                });
    }

    // ── Update ─────────────────────────────────────────────────────────────

    @Test
    void updateReplacesSuppliedFieldsTrimsTheDisplayNameAndKeepsTheName() {
        String application = app("roleupd");
        var seeded = created(application, "viewer", "Before", application + ":doc:read:*");

        var ev = runAsAnchor(UpdateRole.of(repo), new UpdateCommand(seeded.roleId(), "  After  ", "after",
                List.of(application + ":doc:read:*", application + ":doc:list:*"), true));
        assertThat(ev.roleId()).isEqualTo(seeded.roleId());
        assertThat(ev.name()).isEqualTo(application + ":viewer");
        assertThat(ev.eventType()).isEqualTo(RoleEvents.UPDATED);

        var got = reload(seeded.roleId());
        assertThat(got.displayName()).as("display name is trimmed").isEqualTo("After");
        assertThat(got.description()).isEqualTo("after");
        assertThat(got.clientManaged()).isTrue();
        assertThat(got.permissions()).containsExactly(application + ":doc:list:*", application + ":doc:read:*");
        assertThat(got.name()).as("name is immutable on update").isEqualTo(application + ":viewer");

        // Absent fields are untouched; an empty permission list clears the set.
        runAsAnchor(UpdateRole.of(repo), new UpdateCommand(seeded.roleId(), null, null, List.of(), null));
        var cleared = reload(seeded.roleId());
        assertThat(cleared.displayName()).isEqualTo("After");
        assertThat(cleared.description()).isEqualTo("after");
        assertThat(cleared.clientManaged()).isTrue();
        assertThat(cleared.permissions()).isEmpty();

        assertThat(eventsFor(seeded.roleId(), RoleEvents.UPDATED)).hasSize(2);
        assertThat(auditsFor(seeded.roleId(), "UpdateCommand")).hasSize(2);
    }

    @Test
    void updateRejectsMissingIdBlankDisplayNameOrUnknownRow() {
        assertUseCaseError(() -> runAsAnchor(UpdateRole.of(repo), new UpdateCommand(null, "X", null, null, null)),
                UseCaseError.Validation.class, "ID_REQUIRED");
        assertUseCaseError(() -> runAsAnchor(UpdateRole.of(repo), new UpdateCommand("rol_doesnotexist1", "  ", null, null, null)),
                UseCaseError.Validation.class, "DISPLAY_NAME_REQUIRED");
        assertUseCaseError(() -> runAsAnchor(UpdateRole.of(repo), new UpdateCommand("rol_doesnotexist1", "X", null, null, null)),
                UseCaseError.NotFound.class, "Role_NOT_FOUND");
    }

    @Test
    void updateRefusesACodeRoleAndPersistsNothing() {
        String id = seedRawRole(app("roleimm") + ":update-target", "Immutable Upd", RoleSource.CODE, null);
        assertUseCaseError(() -> runAsAnchor(UpdateRole.of(repo), new UpdateCommand(id, "Hacked", null, null, null)),
                UseCaseError.Conflict.class, "CODE_ROLE_IMMUTABLE");
        assertThat(reload(id).displayName()).as("refused update must not persist").isEqualTo("Immutable Upd");
        assertThat(eventsFor(id, RoleEvents.UPDATED)).isEmpty();
    }

    // ── Delete ─────────────────────────────────────────────────────────────

    @Test
    void deleteRemovesTheRowAndItsPermissions() {
        String application = app("roledel");
        var seeded = created(application, "doomed", "Doomed", application + ":x:y:z");

        var ev = runAsAnchor(DeleteRole.of(repo), new DeleteCommand(seeded.roleId()));
        assertThat(ev.roleId()).isEqualTo(seeded.roleId());
        assertThat(ev.name()).isEqualTo(application + ":doomed");

        assertThat(repo.findById(seeded.roleId())).as("deleted row must be gone").isEmpty();
        assertThat(DB.fetch("SELECT permission FROM iam_role_permissions WHERE role_id = ?", seeded.roleId()))
                .as("permission rows are deleted with the role").isEmpty();
        assertThat(eventsFor(seeded.roleId(), RoleEvents.DELETED)).hasSize(1);
        assertThat(auditsFor(seeded.roleId(), "DeleteCommand")).hasSize(1);
    }

    @Test
    void deleteRejectsMissingIdUnknownRowOrCodeRole() {
        assertUseCaseError(() -> runAsAnchor(DeleteRole.of(repo), new DeleteCommand(" ")),
                UseCaseError.Validation.class, "ID_REQUIRED");
        assertUseCaseError(() -> runAsAnchor(DeleteRole.of(repo), new DeleteCommand("rol_doesnotexist1")),
                UseCaseError.NotFound.class, "Role_NOT_FOUND");

        String id = seedRawRole(app("roleimm") + ":delete-target", "Immutable Del", RoleSource.CODE, null);
        assertUseCaseError(() -> runAsAnchor(DeleteRole.of(repo), new DeleteCommand(id)),
                UseCaseError.Conflict.class, "CODE_ROLE_IMMUTABLE");
        assertThat(repo.findById(id)).as("refused delete must leave the row in place").isPresent();
    }

    // ── Grant / revoke (lookup by name) ────────────────────────────────────

    @Test
    void grantAndRevokeByNamePersistAndAlwaysEmit() {
        String application = app("roleperm");
        String name = application + ":operator";
        var seeded = created(application, "operator", "Operator", application + ":base:read:*");

        var granted = runAsAnchor(GrantPermission.of(repo), new GrantPermissionCommand(name, application + ":job:run:*"));
        assertThat(granted.roleId()).isEqualTo(seeded.roleId());
        assertThat(granted.roleName()).isEqualTo(name);
        assertThat(granted.permission()).isEqualTo(application + ":job:run:*");
        assertThat(granted.eventType()).isEqualTo(RoleEvents.PERMISSION_GRANTED);
        assertThat(byName(name).permissions()).containsExactly(application + ":base:read:*", application + ":job:run:*");

        // Re-grant: no duplicate row, but the event and audit are still written.
        runAsAnchor(GrantPermission.of(repo), new GrantPermissionCommand(name, application + ":job:run:*"));
        assertThat(byName(name).permissions()).hasSize(2);
        var grants = eventsFor(seeded.roleId(), RoleEvents.PERMISSION_GRANTED);
        assertThat(grants).hasSize(2);
        var data = json(grants.getFirst().get("data", String.class));
        assertThat(data.fieldNames()).toIterable().containsExactlyInAnyOrder("roleId", "roleName", "permission");
        assertThat(auditsFor(seeded.roleId(), "GrantPermissionCommand")).hasSize(2);

        var revoked = runAsAnchor(RevokePermission.of(repo), new RevokePermissionCommand(name, application + ":job:run:*"));
        assertThat(revoked.permission()).isEqualTo(application + ":job:run:*");
        assertThat(revoked.eventType()).isEqualTo(RoleEvents.PERMISSION_REVOKED);
        assertThat(byName(name).permissions()).containsExactly(application + ":base:read:*");
        assertThat(eventsFor(seeded.roleId(), RoleEvents.PERMISSION_REVOKED)).hasSize(1);
        assertThat(auditsFor(seeded.roleId(), "RevokePermissionCommand")).hasSize(1);
    }

    static Stream<Arguments> malformedPermissionCommands() {
        return Stream.of(
                Arguments.of("grant: missing role name", GrantPermission.of(repo), new GrantPermissionCommand(" ", "a:b:c:d"), UseCaseError.Validation.class, "ROLE_NAME_REQUIRED"),
                Arguments.of("grant: missing permission", GrantPermission.of(repo), new GrantPermissionCommand("nope:nope", null), UseCaseError.Validation.class, "PERMISSION_REQUIRED"),
                Arguments.of("grant: unknown role", GrantPermission.of(repo), new GrantPermissionCommand("rolepermerr:ghost", "a:b:c:d"), UseCaseError.NotFound.class, "Role_NOT_FOUND"),
                Arguments.of("revoke: missing role name", RevokePermission.of(repo), new RevokePermissionCommand(null, "a:b:c:d"), UseCaseError.Validation.class, "ROLE_NAME_REQUIRED"),
                Arguments.of("revoke: missing permission", RevokePermission.of(repo), new RevokePermissionCommand("nope:nope", ""), UseCaseError.Validation.class, "PERMISSION_REQUIRED"),
                Arguments.of("revoke: unknown role", RevokePermission.of(repo), new RevokePermissionCommand("rolepermerr:ghost", "a:b:c:d"), UseCaseError.NotFound.class, "Role_NOT_FOUND"));
    }

    @ParameterizedTest(name = "{0} → {4}")
    @MethodSource("malformedPermissionCommands")
    <C, E extends DomainEvent> void grantAndRevokeRejectMissingFieldsOrUnknownRole(
            String label, Operation<C, E> op, C cmd, Class<? extends UseCaseError> kind, String code) {
        assertUseCaseError(() -> runAsAnchor(op, cmd), kind, code);
    }

    // ── SyncRoles (application-scoped SDK sync) ────────────────────────────

    @Test
    void syncRolesRejectsAMissingApplicationCodeOrEmptyBatch() {
        assertUseCaseError(() -> runAsAnchor(SyncRoles.of(repo), new SyncRolesCommand(null, "app_x", List.of(), false)),
                UseCaseError.Validation.class, "APPLICATION_CODE_REQUIRED");
        assertUseCaseError(() -> runAsAnchor(SyncRoles.of(repo), new SyncRolesCommand(app("rolesyncval"), "app_x", List.of(), false)),
                UseCaseError.Validation.class, "ROLES_REQUIRED");
    }

    /// The use case's resource-level authorization: a principal without
    /// access to the target application is denied before any write (the
    /// coarse "may sync roles" permission is the handler's separate gate).
    @Test
    void syncRolesRequiresAccessToTheTargetApplication() {
        String application = app("rolesyncnoaccess");
        String appId = EntityType.APPLICATION.generate();
        var cmd = new SyncRolesCommand(application, appId, List.of(new SyncRoleInput("Editor", null, null, null, false)), false);

        var otherApp = new AuthContext(EntityType.PRINCIPAL.generate(), Scope.CLIENT, "c@x.io", List.of("cli_x"),
                List.of(), List.of("app_other"), false, List.of("platform:iam:role:manage"));
        assertThatThrownBy(() -> Auth.runAs(otherApp, () -> SyncRoles.of(repo).run(uow, cmd, ExecutionContext.of(otherApp.principalId()))))
                .isInstanceOf(UseCaseException.class)
                .extracting(t -> ((UseCaseException) t).error())
                .satisfies(err -> {
                    assertThat(err).isInstanceOf(UseCaseError.Authorization.class);
                    assertThat(err.code()).isEqualTo("FORBIDDEN");
                    assertThat(err.message()).isEqualTo("Not authorised for application '" + application + "'");
                });

        assertUseCaseError(() -> SyncRoles.of(repo).run(uow, cmd, ExecutionContext.of(null)),
                UseCaseError.Authorization.class, "UNAUTHENTICATED");
        assertThat(repo.findByApplicationId(appId)).as("denied before anything is written").isEmpty();

        // Explicit access to exactly this application suffices.
        var thisApp = new AuthContext(EntityType.PRINCIPAL.generate(), Scope.CLIENT, "c@x.io", List.of("cli_x"),
                List.of(), List.of(appId), false, List.of("platform:iam:role:manage"));
        var ev = Auth.runAs(thisApp, () -> SyncRoles.of(repo).run(uow, cmd, ExecutionContext.of(thisApp.principalId())));
        assertThat(ev.created()).isEqualTo(1);
    }

    /// The SDK-sync behaviour pin: canonical names, upsert counts,
    /// permission preservation on an empty list, and `removeUnlisted`
    /// touching only SDK-sourced rows.
    @Test
    void syncRolesUpsertsPreservesPermissionsAndRemovesOnlySdkRows() {
        String application = app("rolesyncapp");
        String appId = EntityType.APPLICATION.generate();
        // A DATABASE row in the same application scope: sync must never touch it, even when named in the batch.
        seedRawRole(application + ":manual", "Manual Row", RoleSource.DATABASE, appId);

        var first = runAsAnchor(SyncRoles.of(repo), new SyncRolesCommand(application, appId, List.of(
                new SyncRoleInput("Editor", "Doc Editor", "edits", List.of(application + ":doc:edit:*"), true),
                new SyncRoleInput(application + ":Viewer", null, null, List.of(), false)), false));
        assertThat(first.created()).isEqualTo(2);
        assertThat(first.updated()).isZero();
        assertThat(first.removed()).isZero();
        assertThat(first.total()).isEqualTo(2);
        assertThat(first.applicationCode()).isEqualTo(application);
        assertThat(first.syncedCodes()).as("canonical names: {appCode}:{lowercased, prefix-stripped name}")
                .containsExactly(application + ":editor", application + ":viewer");
        assertThat(first.eventType()).isEqualTo(RoleEvents.SYNCED);
        assertThat(first.subject()).isEqualTo(RoleEvents.SYNC_SUBJECT);
        assertThat(first.messageGroup()).isEqualTo(RoleEvents.SYNC_MESSAGE_GROUP);

        var editor = byName(application + ":editor");
        assertThat(editor.source()).isEqualTo(RoleSource.SDK);
        assertThat(editor.applicationId()).as("fresh SDK rows are stamped with the app id").isEqualTo(appId);
        assertThat(editor.displayName()).isEqualTo("Doc Editor");
        assertThat(editor.description()).isEqualTo("edits");
        assertThat(editor.clientManaged()).isTrue();
        assertThat(editor.permissions()).containsExactly(application + ":doc:edit:*");
        assertThat(byName(application + ":viewer").displayName()).as("omitted displayName falls back to the raw name")
                .isEqualTo(application + ":Viewer");

        // Re-sync with no permissions on the editor row: stored permissions are preserved.
        var second = runAsAnchor(SyncRoles.of(repo), new SyncRolesCommand(application, appId, List.of(
                new SyncRoleInput("Editor", null, null, List.of(), false)), false));
        assertThat(second.created()).isZero();
        assertThat(second.updated()).isEqualTo(1);
        assertThat(second.removed()).isZero();
        editor = byName(application + ":editor");
        assertThat(editor.permissions()).as("an empty list must preserve the stored permissions")
                .containsExactly(application + ":doc:edit:*");
        assertThat(editor.displayName()).isEqualTo("Editor");
        assertThat(editor.clientManaged()).isFalse();

        // removeUnlisted prunes the unlisted SDK row (viewer) but skips the DATABASE row named in the batch.
        var third = runAsAnchor(SyncRoles.of(repo), new SyncRolesCommand(application, appId, List.of(
                new SyncRoleInput("Editor", null, null, List.of(), false),
                new SyncRoleInput("Manual", null, null, List.of(), false)), true));
        assertThat(third.created()).as("existing non-SDK row must not be re-created").isZero();
        assertThat(third.updated()).as("only the SDK row counts as updated").isEqualTo(1);
        assertThat(third.removed()).isEqualTo(1);
        assertThat(repo.findByName(application + ":viewer")).as("unlisted SDK row must be deleted").isEmpty();
        var manual = byName(application + ":manual");
        assertThat(manual.source()).isEqualTo(RoleSource.DATABASE);
        assertThat(manual.displayName()).as("non-SDK row must not be updated either").isEqualTo("Manual Row");

        // Per-row events + rollups, each with an audit row naming the sync command.
        assertThat(eventsFor(editor.id(), RoleEvents.CREATED)).hasSize(1);
        assertThat(eventsFor(editor.id(), RoleEvents.UPDATED)).hasSize(2);
        assertThat(auditsFor(editor.id(), "SyncRolesCommand")).hasSize(3);
        var rollup = DB.fetch("SELECT data::text AS data, message_group FROM msg_events WHERE id = ?", first.eventId());
        assertThat(rollup).hasSize(1);
        assertThat(rollup.getFirst().get("message_group")).isEqualTo(RoleEvents.SYNC_MESSAGE_GROUP);
        var data = json(rollup.getFirst().get("data", String.class));
        assertThat(data.get("created").asInt()).isEqualTo(2);
        assertThat(data.get("total").asInt()).isEqualTo(2);
        assertThat(data.get("applicationCode").asText()).isEqualTo(application);
        assertThat(data.get("syncedCodes")).hasSize(2);
    }

    /// `removeUnlisted` refuses to drop a role that principals still hold —
    /// the junction has no foreign key, so a silent delete would orphan the
    /// assignments. The whole sync aborts (nothing else persists).
    @Test
    void syncRolesRefusesToRemoveAnAssignedRoleAndAbortsTheBatch() {
        String application = app("rolesyncasgn");
        String appId = EntityType.APPLICATION.generate();
        runAsAnchor(SyncRoles.of(repo), new SyncRolesCommand(application, appId,
                List.of(new SyncRoleInput("Held", null, null, List.of(), false)), false));
        seedPrincipalHolding(application + ":held");

        assertThatThrownBy(() -> runAsAnchor(SyncRoles.of(repo), new SyncRolesCommand(application, appId,
                List.of(new SyncRoleInput("Other", null, null, List.of(), false)), true)))
                .isInstanceOf(UseCaseException.class)
                .extracting(t -> ((UseCaseException) t).error())
                .satisfies(err -> {
                    assertThat(err).isInstanceOf(UseCaseError.BusinessRule.class);
                    assertThat(err.code()).isEqualTo("ROLE_HAS_ASSIGNMENTS");
                    assertThat(err.message()).isEqualTo("Cannot remove role '" + application + ":held' — 1 principal(s) still hold it. Strip the assignments before syncing.");
                });
        assertThat(repo.findByName(application + ":held")).as("assigned role must survive the refused sync").isPresent();
        assertThat(repo.findByName(application + ":other")).as("refused sync must be atomic").isEmpty();
    }

    // ── SyncPlatformRoles (static CODE catalogue) ──────────────────────────

    /// Every call passes the real catalogue plus the test's own entries, so
    /// production rows are never swept; a stale row is swept only once no
    /// principal holds it.
    @Test
    void syncPlatformRolesFollowsTheCatalogueLifecycle() {
        String application = app("roleplat");
        List<RoleDefinition> catalogue = PlatformRoles.all();
        int n = catalogue.size();
        var anchorCtx = ANCHOR;

        // The shared database may already carry the catalogue (and CODE rows seeded by other tests
        // that may be swept); only our own rows and the production catalogue are asserted on.
        var a = def(application, "sync-a", "Sync A");
        var b = def(application, "sync-b", "Sync B");

        var first = Auth.runAs(anchorCtx, () -> SyncPlatformRoles.of(repo, withTest(catalogue, a, b))
                .run(uow, new SyncPlatformRolesCommand(), EC));
        assertThat(first.created() + first.updated()).as("every catalogue entry plus the two test roles is touched")
                .isEqualTo(n + 2);
        assertThat(first.created()).isGreaterThanOrEqualTo(2);
        assertThat(first.total()).isEqualTo(n + 2);
        assertThat(first.applicationCode()).isNull();
        assertThat(first.syncedCodes()).isEmpty();
        assertThat(first.subject()).isEqualTo(RoleEvents.SYNC_SUBJECT);
        assertThat(first.messageGroup()).isEqualTo(RoleEvents.SYNC_MESSAGE_GROUP);

        var syncA = byName(application + ":sync-a");
        assertThat(syncA.source()).as("platform-synced rows are CODE-sourced").isEqualTo(RoleSource.CODE);
        assertThat(syncA.description()).isEqualTo("platform-sync test role");
        assertThat(syncA.permissions()).containsExactly(application + ":thing:read:*");
        assertThat(syncA.applicationId()).isNull();
        assertThat(auditsFor(syncA.id(), "SyncPlatformRolesCommand")).hasSize(1);
        var rollup = DB.fetch("SELECT data::text AS data FROM msg_events WHERE id = ?", first.eventId());
        var data = json(rollup.getFirst().get("data", String.class));
        assertThat(data.has("applicationCode")).isFalse();
        assertThat(data.has("syncedCodes")).isFalse();
        assertThat(data.get("total").asInt()).isEqualTo(n + 2);

        // sync-b gains a live assignment; sync-a drifts in the catalogue. Re-sync without sync-b:
        // every existing catalogue role is re-upserted, the drift is restored, and the
        // stale-but-assigned sync-b is skipped (warn, not an error).
        seedPrincipalHolding(application + ":sync-b");
        var second = Auth.runAs(anchorCtx, () -> SyncPlatformRoles.of(repo, withTest(catalogue, def(application, "sync-a", "Sync A v2")))
                .run(uow, new SyncPlatformRolesCommand(), EC));
        assertThat(second.created()).isZero();
        assertThat(second.updated()).as("every existing catalogue role is re-upserted").isEqualTo(n + 1);
        assertThat(byName(application + ":sync-a").displayName()).as("drifted rows are updated from the catalogue")
                .isEqualTo("Sync A v2");
        assertThat(repo.findByName(application + ":sync-b")).as("stale CODE role with live assignments survives").isPresent();

        // Drop the assignment: the stale row is now sweepable.
        DB.deleteFrom(IAM_PRINCIPAL_ROLES).where(IAM_PRINCIPAL_ROLES.ROLE_NAME.eq(application + ":sync-b")).execute();
        var third = Auth.runAs(anchorCtx, () -> SyncPlatformRoles.of(repo, withTest(catalogue, def(application, "sync-a", "Sync A v2")))
                .run(uow, new SyncPlatformRolesCommand(), EC));
        assertThat(third.removed()).isGreaterThanOrEqualTo(1);
        assertThat(repo.findByName(application + ":sync-b")).isEmpty();

        // A catalogue name held by a non-CODE row is skipped, not overwritten.
        created(application, "taken", "Operator Row");
        var fourth = Auth.runAs(anchorCtx, () -> SyncPlatformRoles.of(repo, withTest(catalogue, a, def(application, "taken", "Catalogue Row")))
                .run(uow, new SyncPlatformRolesCommand(), EC));
        assertThat(fourth.created()).isZero();
        assertThat(byName(application + ":taken").displayName()).isEqualTo("Operator Row");
        assertThat(byName(application + ":taken").source()).isEqualTo(RoleSource.DATABASE);

        // Safety pin: the real platform catalogue is intact throughout.
        var admin = byName("platform:admin");
        assertThat(admin.source()).isEqualTo(RoleSource.CODE);
        assertThat(admin.permissions()).contains("platform:admin:client:view");
    }

    private static RoleDefinition def(String applicationCode, String roleName, String displayName) {
        return new RoleDefinition(applicationCode + ":" + roleName, displayName, "platform-sync test role",
                applicationCode, RoleDefinition.SOURCE_CODE, List.of(applicationCode + ":thing:read:*"));
    }

    private static List<RoleDefinition> withTest(List<RoleDefinition> catalogue, RoleDefinition... extra) {
        var out = new ArrayList<>(catalogue);
        out.addAll(List.of(extra));
        return out;
    }

    // ── Repository reads ───────────────────────────────────────────────────

    @Test
    void repositoryReadsAreOrderedByNameAndFilterBySourceApplicationAndShortName() {
        String application = app("rolelist");
        String appId = EntityType.APPLICATION.generate();
        var b = created(application, "b-role", "B");
        var a = created(application, "a-role", "A", application + ":x:y:z");
        runAsAnchor(SyncRoles.of(repo), new SyncRolesCommand(application, appId,
                List.of(new SyncRoleInput("c-role", null, null, List.of(), false)), false));
        String c = application + ":c-role";

        assertThat(repo.findAll().stream().map(Role::name).filter(n -> n.startsWith(application + ":")).toList())
                .as("ordered by name").containsExactly(application + ":a-role", application + ":b-role", c);
        assertThat(repo.findAll().stream().filter(r -> r.id().equals(a.roleId())).findFirst().orElseThrow().permissions())
                .as("lists hydrate permissions").containsExactly(application + ":x:y:z");

        assertThat(repo.findBySource(RoleSource.SDK)).extracting(Role::name).contains(c)
                .doesNotContain(application + ":a-role");
        assertThat(repo.findBySource(RoleSource.DATABASE)).extracting(Role::id).contains(a.roleId(), b.roleId());
        assertThat(repo.findByApplicationId(appId)).extracting(Role::name).containsExactly(c);
        assertThat(repo.findByApplicationId("app_none" + RUN)).isEmpty();
        assertThat(repo.applicationCodes()).contains(application).isSorted();

        assertThat(repo.findByShortNameInApps("c-role", List.of("app_other", appId))).map(Role::name).contains(c);
        assertThat(repo.findByShortNameInApps("a-role", List.of(appId))).as("admin rows carry no application id").isEmpty();
        assertThat(repo.findByShortNameInApps("", List.of(appId))).isEmpty();
        assertThat(repo.findByShortNameInApps("c-role", List.of())).isEmpty();

        assertThat(repo.countAssignments(c)).isZero();
        seedPrincipalHolding(c);
        assertThat(repo.countAssignments(c)).isEqualTo(1);
    }
}
