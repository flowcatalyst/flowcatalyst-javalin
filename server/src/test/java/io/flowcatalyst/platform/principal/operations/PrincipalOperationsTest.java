package io.flowcatalyst.platform.principal.operations;

import io.flowcatalyst.platform.application.Application;
import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.platform.application.ApplicationType;
import io.flowcatalyst.platform.application.ClientConfigRepository;
import io.flowcatalyst.platform.client.Client;
import io.flowcatalyst.platform.client.ClientIdentifier;
import io.flowcatalyst.platform.client.ClientRepository;
import io.flowcatalyst.platform.principal.ClientAccessGrantRepository;
import io.flowcatalyst.platform.principal.PasswordResetEmailer;
import io.flowcatalyst.platform.principal.Principal;
import io.flowcatalyst.platform.principal.PrincipalRepository;
import io.flowcatalyst.platform.principal.RoleAssignment;
import io.flowcatalyst.platform.principal.UserScope;
import io.flowcatalyst.platform.role.Role;
import io.flowcatalyst.platform.role.RoleRepository;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.AuthContext;
import io.flowcatalyst.platform.shared.auth.PasswordHash;
import io.flowcatalyst.platform.shared.auth.Scope;
import io.flowcatalyst.platform.shared.encryption.Encryption;
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

/// The principal use cases against the embedded Postgres (spec §4–9):
/// validation, the per-resource authorization, persistence of the row and
/// its junctions, and the envelope's guarantee that a write lands together
/// with its `msg_events` and `aud_logs` rows — which never carry a password,
/// a hash or a secret. The pure transition rules are in `PrincipalTest`.
///
/// The fixture never truncates; emails are namespaced by a per-JVM suffix.
class PrincipalOperationsTest {

    private static final DataSource DS = TestPg.dataSource();
    private static final DSLContext DB = DSL.using(DS, SQLDialect.POSTGRES);
    private static final PrincipalRepository repo = new PrincipalRepository(DS);
    private static final ClientAccessGrantRepository grants = new ClientAccessGrantRepository(DS);
    private static final RoleRepository roles = new RoleRepository(DS);
    private static final ClientRepository clients = new ClientRepository(DS);
    private static final ApplicationRepository applications = new ApplicationRepository(DS);
    private static final UnitOfWork uow = new UnitOfWork(DS, new PlatformSink(Json.MAPPER));

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);
    private static final String ACTOR = EntityType.PRINCIPAL.generate();
    private static final AuthContext ANCHOR = new AuthContext(ACTOR, Scope.ANCHOR, "anchor@x.io",
            List.of("*"), List.of(), List.of(), true, List.of("platform:*:*:*"));
    private static final ExecutionContext EC = ExecutionContext.of(ACTOR);

    // ── Fixture ────────────────────────────────────────────────────────────

    private static <C, E extends DomainEvent> E runAsAnchor(Operation<C, E> op, C cmd) {
        return Auth.runAs(ANCHOR, () -> op.run(uow, cmd, EC));
    }

    private static <C, E extends DomainEvent> E runAs(AuthContext ac, Operation<C, E> op, C cmd) {
        return Auth.runAs(ac, () -> op.run(uow, cmd, EC));
    }

    private static AuthContext clientAdmin(String clientId) {
        return new AuthContext(EntityType.PRINCIPAL.generate(), Scope.CLIENT, "admin@x.io", List.of(clientId), List.of(),
                List.of(), true, List.of("platform:iam:user:update"));
    }

    private static String email(String tag) {
        return tag + "-" + RUN + "@ops.test";
    }

    private static String createdUser(String tag, String scope, String clientId) {
        return runAsAnchor(CreateUser.of(repo), new CreateCommand(email(tag), null, scope, clientId, null, null)).userId();
    }

    private static Principal reload(String id) {
        return repo.findById(id).orElseThrow(() -> new AssertionError("principal " + id + " not found"));
    }

    private static String seedClient(String tag) {
        var c = Client.create("Client " + tag, ClientIdentifier.parse(tag + RUN));
        uow.inTransaction(tx -> { clients.persist(c, tx.dbTx()); return null; });
        return c.id();
    }

    private static Role seedRole(String app, String name) {
        var r = Role.create(app + RUN, name, name);
        uow.inTransaction(tx -> { roles.persist(r, tx.dbTx()); return null; });
        return r;
    }

    private static Application seedApplication(String tag, boolean active) {
        var a = Application.create(ApplicationType.APPLICATION, tag + RUN, "App " + tag);
        Application stored = active ? a : a.deactivate();
        uow.inTransaction(tx -> { applications.persist(stored, tx.dbTx()); return null; });
        return stored;
    }

    private static String seedService(String tag) {
        // serviceAccountId must fit iam_principals.service_account_id (varchar(17)),
        // so generate a real service-account id rather than composing one from the
        // tag; the tag stays human-readable in the name only.
        var p = Principal.newService(EntityType.SERVICE_ACCOUNT.generate(), "svc " + tag);
        uow.inTransaction(tx -> { repo.persist(p, tx.dbTx()); return null; });
        return p.id();
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

    private static Result<Record> eventsFor(String principalId, String type) {
        return DB.fetch("SELECT type, subject, source, message_group, data::text AS data, deduplication_id FROM msg_events WHERE subject = ? AND type = ?",
                PrincipalEvents.subjectFor(principalId), type);
    }

    private static Result<Record> auditsFor(String principalId, String operation) {
        return DB.fetch("SELECT entity_type, entity_id, operation, operation_json::text AS operation_json, principal_id FROM aud_logs WHERE entity_id = ? AND operation = ?",
                principalId, operation);
    }

    private static String storedHash(String principalId) {
        return DB.fetchOne("SELECT password_hash FROM iam_principals WHERE id = ?", principalId).get(0, String.class);
    }

    // ── Create ─────────────────────────────────────────────────────────────

    @Test
    void createWritesTheRowTheEventAndTheAuditWithoutThePassword() {
        String raw = " Create-" + RUN + "@Ops.Test ";
        var ev = runAsAnchor(CreateUser.of(repo), new CreateCommand(raw, " Ada ", "ANCHOR", null, "correct-horse-battery", null));

        assertThat(ev.userId()).startsWith("prn_");
        assertThat(ev.email()).isEqualTo("create-" + RUN + "@ops.test");
        assertThat(ev.subject()).isEqualTo(PrincipalEvents.subjectFor(ev.userId()));
        var got = reload(ev.userId());
        assertThat(got.name()).isEqualTo("Ada");
        assertThat(got.scope()).isEqualTo(UserScope.ANCHOR);
        assertThat(got.active()).isTrue();
        assertThat(got.userIdentity().providerOrInternal()).isEqualTo("INTERNAL");
        assertThat(PasswordHash.matches("correct-horse-battery", got.userIdentity().passwordHash())).isTrue();
        assertThat(DB.fetchOne("SELECT email_domain FROM iam_principals WHERE id = ?", got.id()).get(0, String.class)).isEqualTo("ops.test");

        var events = eventsFor(ev.userId(), PrincipalEvents.USER_CREATED);
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().get("source")).isEqualTo(PrincipalEvents.SOURCE);
        assertThat(events.getFirst().get("message_group")).isEqualTo(PrincipalEvents.groupFor(ev.userId()));
        assertThat(events.getFirst().get("data", String.class)).contains("\"principalId\"").doesNotContain("correct-horse");
        var audits = auditsFor(ev.userId(), "CreateCommand");
        assertThat(audits).hasSize(1);
        assertThat(audits.getFirst().get("entity_type")).isEqualTo("Principal");
        assertThat(audits.getFirst().get("principal_id")).isEqualTo(ACTOR);
        assertThat(audits.getFirst().get("operation_json", String.class)).contains("\"email\"").doesNotContain("password").doesNotContain("correct-horse");
    }

    @Test
    void createOidcUserHasNoPasswordAndDuplicateEmailConflicts() {
        var ev = runAsAnchor(CreateUser.of(repo), new CreateCommand(email("oidc"), null, "ANCHOR", null, "correct-horse-battery", "OIDC"));
        var got = reload(ev.userId());
        assertThat(got.userIdentity().hasPassword()).isFalse();
        assertThat(got.userIdentity().provider()).isEqualTo("OIDC");
        assertUseCaseError(() -> runAsAnchor(CreateUser.of(repo), new CreateCommand(email("oidc").toUpperCase(Locale.ROOT), null, "ANCHOR", null, null, null)),
                UseCaseError.Conflict.class, "EMAIL_EXISTS");
    }

    static Stream<Arguments> invalidCreates() {
        return Stream.of(
                Arguments.of(new CreateCommand("", null, "ANCHOR", null, null, null), "EMAIL_REQUIRED"),
                Arguments.of(new CreateCommand("plainaddress", null, "ANCHOR", null, null, null), "INVALID_EMAIL"),
                Arguments.of(new CreateCommand("u@x.io", null, null, null, null, null), "INVALID_SCOPE"),
                Arguments.of(new CreateCommand("u@x.io", null, "GLOBAL", null, null, null), "INVALID_SCOPE"),
                Arguments.of(new CreateCommand("u@x.io", null, "CLIENT", null, null, null), "CLIENT_REQUIRED"),
                Arguments.of(new CreateCommand("u@x.io", null, "PARTNER", null, null, null), "CLIENT_REQUIRED"),
                Arguments.of(new CreateCommand("u@x.io", null, "ANCHOR", null, "short", null), "PASSWORD_TOO_SHORT"),
                Arguments.of(new CreateCommand("u@x.io", "Ulla Ops", "ANCHOR", null, "ulla-ops-forever", null), "PASSWORD_CONTAINS_IDENTITY"));
    }

    @ParameterizedTest
    @MethodSource("invalidCreates")
    void createRejectsBadCommands(CreateCommand cmd, String code) {
        assertUseCaseError(() -> runAsAnchor(CreateUser.of(repo), cmd), UseCaseError.Validation.class, code);
    }

    @Test
    void createPortalUserIsInertAndEmitsCreated() {
        var ev = runAsAnchor(CreatePortalUser.of(repo), new CreatePortalUserCommand(email("portal"), "Portal Pat", "OIDC"));
        var got = reload(ev.userId());
        assertThat(got.scope()).isEqualTo(UserScope.CLIENT);
        assertThat(got.clientId()).isNull();
        assertThat(got.allApplications()).isFalse();
        assertThat(got.userIdentity().provider()).isEqualTo("OIDC");
        assertThat(auditsFor(ev.userId(), "CreatePortalUserCommand")).hasSize(1);
    }

    // ── Update / status / delete ───────────────────────────────────────────

    @Test
    void updateReplacesNameAndActiveAndRefusesAnEmailChange() {
        String id = createdUser("update", "ANCHOR", null);
        var ev = runAsAnchor(UpdateUser.of(repo), new UpdateCommand(id, " New Name ", false, email("update").toUpperCase(Locale.ROOT)));
        assertThat(ev.name()).isEqualTo("New Name");
        assertThat(reload(id).active()).isFalse();
        assertThat(eventsFor(id, PrincipalEvents.USER_UPDATED)).hasSize(1);
        assertThat(auditsFor(id, "UpdateCommand")).hasSize(1);
        assertUseCaseError(() -> runAsAnchor(UpdateUser.of(repo), new UpdateCommand(id, null, null, "other@x.io")), UseCaseError.Validation.class, "EMAIL_IMMUTABLE");
        assertUseCaseError(() -> runAsAnchor(UpdateUser.of(repo), new UpdateCommand(id, "  ", null, null)), UseCaseError.Validation.class, "NAME_REQUIRED");
        assertUseCaseError(() -> runAsAnchor(UpdateUser.of(repo), new UpdateCommand("prn_missing", "x", null, null)), UseCaseError.NotFound.class, "Principal_NOT_FOUND");
    }

    @Test
    void clientAdminsManageOnlyClientUsersOfTheirClient() {
        String clientA = seedClient("authA");
        String clientB = seedClient("authB");
        String inA = createdUser("inA", "CLIENT", clientA);
        String inB = createdUser("inB", "CLIENT", clientB);
        String partner = createdUser("partner", "PARTNER", clientA);
        var admin = clientAdmin(clientA);

        assertThat(runAs(admin, DeactivateUser.of(repo), new DeactivateCommand(inA)).userId()).isEqualTo(inA);
        // PR-4 (ruled 2026-09-01): a target outside the caller's client scope
        // answers the same not-found a missing id would, never 403 — a 403
        // would be an existence oracle over the principal table.
        assertUseCaseError(() -> runAs(admin, ActivateUser.of(repo), new ActivateCommand(inB)), UseCaseError.NotFound.class, "Principal_NOT_FOUND");
        assertUseCaseError(() -> runAs(admin, UpdateUser.of(repo), new UpdateCommand(partner, "x", null, null)), UseCaseError.Authorization.class, "FORBIDDEN");
        assertUseCaseError(() -> runAs(admin, AssignRoles.of(repo, roles), new AssignRolesCommand(partner, List.of())), UseCaseError.Authorization.class, "FORBIDDEN");
        assertUseCaseError(() -> runAs(null, DeleteUser.of(repo), new DeleteCommand(inA)), UseCaseError.Authorization.class, "UNAUTHENTICATED");
    }

    @Test
    void activateDeactivateAndDeleteRoundTrip() {
        String id = createdUser("status", "ANCHOR", null);
        runAsAnchor(DeactivateUser.of(repo), new DeactivateCommand(id));
        assertThat(reload(id).active()).isFalse();
        runAsAnchor(ActivateUser.of(repo), new ActivateCommand(id));
        assertThat(reload(id).active()).isTrue();
        assertThat(eventsFor(id, PrincipalEvents.USER_DEACTIVATED)).hasSize(1);
        assertThat(eventsFor(id, PrincipalEvents.USER_ACTIVATED)).hasSize(1);
        assertThat(auditsFor(id, "ActivateCommand")).hasSize(1);

        runAsAnchor(AssignRoles.of(repo, roles), new AssignRolesCommand(id, List.of(seedRole("delapp", "viewer").name())));
        var ev = runAsAnchor(DeleteUser.of(repo), new DeleteCommand(id));
        assertThat(ev.email()).isEqualTo(email("status"));
        assertThat(repo.findById(id)).isEmpty();
        assertThat(DB.fetchCount(DSL.table("iam_principal_roles"), DSL.field("principal_id").eq(id))).isZero();
        assertThat(eventsFor(id, PrincipalEvents.USER_DELETED)).hasSize(1);
        assertThat(auditsFor(id, "DeleteCommand")).hasSize(1);
    }

    // ── Password ───────────────────────────────────────────────────────────

    @Test
    void resetPasswordHashesStrictlyOrRelaxedAndNeverLeaksTheHash() {
        String id = createdUser("reset", "ANCHOR", null);
        runAsAnchor(ResetPassword.of(repo), new ResetPasswordCommand(id, "correct-horse-battery", null));
        String hash = storedHash(id);
        assertThat(PasswordHash.matches("correct-horse-battery", hash)).isTrue();
        assertThat(eventsFor(id, PrincipalEvents.PASSWORD_RESET)).hasSize(1);
        assertThat(eventsFor(id, PrincipalEvents.PASSWORD_RESET).getFirst().get("data", String.class)).doesNotContain(hash).doesNotContain("correct-horse");
        assertThat(auditsFor(id, "ResetPasswordCommand").getFirst().get("operation_json", String.class)).doesNotContain("correct-horse").doesNotContain("newPassword");

        runAsAnchor(ResetPassword.of(repo), new ResetPasswordCommand(id, "ab", false));
        assertThat(PasswordHash.matches("ab", storedHash(id))).isTrue();

        assertUseCaseError(() -> runAsAnchor(ResetPassword.of(repo), new ResetPasswordCommand(id, "seven77", null)), UseCaseError.Validation.class, "PASSWORD_TOO_SHORT");
        assertUseCaseError(() -> runAsAnchor(ResetPassword.of(repo), new ResetPasswordCommand(id, "a", false)), UseCaseError.Validation.class, "PASSWORD_TOO_SHORT");
        assertUseCaseError(() -> runAsAnchor(ResetPassword.of(repo), new ResetPasswordCommand(id, "reset-" + RUN + "-ops", null)), UseCaseError.Validation.class, "PASSWORD_CONTAINS_IDENTITY");
        assertUseCaseError(() -> runAsAnchor(ResetPassword.of(repo), new ResetPasswordCommand("prn_missing", "longenough99", null)), UseCaseError.NotFound.class, "Principal_NOT_FOUND");
        assertUseCaseError(() -> runAsAnchor(ResetPassword.of(repo), new ResetPasswordCommand(seedService("reset"), "longenough99", null)), UseCaseError.Conflict.class, "NOT_A_USER");
    }

    @Test
    void sendPasswordResetChecksEligibilityThenAsksTheEmailer() {
        String id = createdUser("sendreset", "ANCHOR", null);
        var sent = new java.util.ArrayList<String>();
        PasswordResetEmailer capture = (p, reset2fa) -> sent.add(p.id() + ":" + reset2fa);
        SendPasswordReset.run(repo, capture, new SendPasswordResetCommand(id, true));
        assertThat(sent).containsExactly(id + ":true");
        assertUseCaseError(() -> SendPasswordReset.run(repo, PasswordResetEmailer.notConfigured(), new SendPasswordResetCommand(id, false)),
                UseCaseError.Internal.class, "EMAILER_NOT_CONFIGURED");
        assertUseCaseError(() -> SendPasswordReset.run(repo, capture, new SendPasswordResetCommand(" ", false)), UseCaseError.Validation.class, "ID_REQUIRED");
        assertUseCaseError(() -> SendPasswordReset.run(repo, capture, new SendPasswordResetCommand("prn_missing", false)), UseCaseError.NotFound.class, "Principal_NOT_FOUND");
        assertUseCaseError(() -> SendPasswordReset.run(repo, capture, new SendPasswordResetCommand(seedService("sendreset"), false)), UseCaseError.Validation.class, "NOT_USER");
        PasswordResetEmailer failing = (p, r) -> { throw new IllegalStateException("smtp down"); };
        assertUseCaseError(() -> SendPasswordReset.run(repo, failing, new SendPasswordResetCommand(id, false)), UseCaseError.Internal.class, "EMAILER");
    }

    // ── Roles ──────────────────────────────────────────────────────────────

    @Test
    void assignRolesReplacesTheJunctionAndReportsTheDifference() {
        String id = createdUser("roles", "ANCHOR", null);
        Role a = seedRole("rolesapp", "a");
        Role b = seedRole("rolesapp", "b");
        var first = runAsAnchor(AssignRoles.of(repo, roles), new AssignRolesCommand(id, List.of(a.name())));
        assertThat(first.added()).containsExactly(a.name());
        var second = runAsAnchor(AssignRoles.of(repo, roles), new AssignRolesCommand(id, List.of(b.name())));
        assertThat(second.added()).containsExactly(b.name());
        assertThat(second.removed()).containsExactly(a.name());
        var got = reload(id);
        assertThat(got.roleNames()).containsExactly(b.name());
        assertThat(got.roles().getFirst().assignmentSource()).isEqualTo(RoleAssignment.ADMIN_ASSIGNED);
        assertThat(eventsFor(id, PrincipalEvents.ROLES_ASSIGNED)).hasSize(2);
        assertThat(auditsFor(id, "AssignRolesCommand")).hasSize(2);
        assertUseCaseError(() -> runAsAnchor(AssignRoles.of(repo, roles), new AssignRolesCommand(id, List.of("nope:missing"))), UseCaseError.Validation.class, "ROLE_NOT_FOUND");
        assertUseCaseError(() -> runAsAnchor(AssignRoles.of(repo, roles), new AssignRolesCommand(seedService("roles"), List.of())), UseCaseError.BusinessRule.class, "NOT_A_USER");
        assertUseCaseError(() -> runAsAnchor(AssignRoles.of(repo, roles), new AssignRolesCommand("prn_missing", List.of())), UseCaseError.NotFound.class, "User_NOT_FOUND");
        assertUseCaseError(() -> runAsAnchor(AssignRoles.of(repo, roles), new AssignRolesCommand(" ", List.of())), UseCaseError.Validation.class, "USER_ID_REQUIRED");
    }

    @Test
    void syncIdpRolesReplacesOnlyItsOwnSource() {
        String id = createdUser("idp", "ANCHOR", null);
        Role admin = seedRole("idpapp", "admin");
        Role g1 = seedRole("idpapp", "g1");
        Role g2 = seedRole("idpapp", "g2");
        runAsAnchor(AssignRoles.of(repo, roles), new AssignRolesCommand(id, List.of(admin.name())));
        runAsAnchor(SyncIdpRoles.of(repo, roles), new SyncIdpRolesCommand(id, List.of(g1.name())));
        var ev = runAsAnchor(SyncIdpRoles.of(repo, roles), new SyncIdpRolesCommand(id, List.of(g2.name(), admin.name())));
        assertThat(ev.roles()).containsExactly(admin.name(), g2.name());
        assertThat(ev.added()).containsExactly(g2.name());
        assertThat(ev.removed()).containsExactly(g1.name());
        assertThat(reload(id).roles()).extracting(RoleAssignment::role, RoleAssignment::assignmentSource)
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple(admin.name(), RoleAssignment.ADMIN_ASSIGNED),
                        org.assertj.core.groups.Tuple.tuple(g2.name(), RoleAssignment.IDP_SYNC));
        assertUseCaseError(() -> runAsAnchor(SyncIdpRoles.of(repo, roles), new SyncIdpRolesCommand(id, List.of("nope:x"))), UseCaseError.Validation.class, "ROLE_NOT_FOUND");
    }

    // ── Application access ─────────────────────────────────────────────────

    @Test
    void assignApplicationAccessRewritesTheJunctionAndTheFlag() {
        String id = createdUser("apps", "ANCHOR", null);
        Application a = seedApplication("appa", true);
        Application inactive = seedApplication("appi", false);
        var ev = runAsAnchor(AssignApplicationAccess.of(repo, applications), new AssignApplicationAccessCommand(id, List.of(a.id()), false));
        assertThat(ev.applicationIds()).containsExactly(a.id());
        assertThat(ev.added()).containsExactly(a.id());
        var got = reload(id);
        assertThat(got.accessibleApplicationIds()).containsExactly(a.id());
        assertThat(got.allApplications()).isFalse();
        assertThat(eventsFor(id, PrincipalEvents.APPLICATION_ACCESS_ASSIGNED).getFirst().get("data", String.class)).contains("\"userId\"");
        assertThat(auditsFor(id, "AssignApplicationAccessCommand")).hasSize(1);
        String svc = seedService("apps");
        assertThat(runAsAnchor(AssignApplicationAccess.of(repo, applications), new AssignApplicationAccessCommand(svc, List.of(a.id()), null)).userId()).isEqualTo(svc);
        assertUseCaseError(() -> runAsAnchor(AssignApplicationAccess.of(repo, applications), new AssignApplicationAccessCommand(id, List.of(inactive.id()), null)), UseCaseError.BusinessRule.class, "APPLICATION_INACTIVE");
        assertUseCaseError(() -> runAsAnchor(AssignApplicationAccess.of(repo, applications), new AssignApplicationAccessCommand(id, List.of("app_missing"), null)), UseCaseError.Validation.class, "APPLICATION_NOT_FOUND");
    }

    // ── Client access + association ────────────────────────────────────────

    @Test
    void grantAndRevokeClientAccessRoundTrip() {
        String clientId = seedClient("grant");
        String partner = createdUser("grant", "PARTNER", clientId);
        String other = seedClient("grant2");
        var granted = runAsAnchor(GrantClientAccess.of(repo, clients, grants), new GrantClientAccessCommand(partner, other));
        assertThat(granted.clientId()).isEqualTo(other);
        assertThat(reload(partner).assignedClients()).containsExactly(other);
        assertThat(grants.findByPrincipalAndClient(partner, other)).isPresent();
        assertThat(eventsFor(partner, PrincipalEvents.CLIENT_ACCESS_GRANTED)).hasSize(1);
        // The audit's entity_id is derived from the event subject, and
        // ClientAccessGranted's subject is subjectFor(principalId) — the
        // principal, never the grant row — so look the audit up by partner.
        assertThat(auditsFor(partner, "GrantClientAccessCommand")).hasSize(1);
        assertUseCaseError(() -> runAsAnchor(GrantClientAccess.of(repo, clients, grants), new GrantClientAccessCommand(partner, other)), UseCaseError.BusinessRule.class, "GRANT_EXISTS");
        assertUseCaseError(() -> runAsAnchor(GrantClientAccess.of(repo, clients, grants), new GrantClientAccessCommand(partner, "clt_missing")), UseCaseError.NotFound.class, "Client_NOT_FOUND");
        String clientUser = createdUser("grantc", "CLIENT", clientId);
        assertUseCaseError(() -> runAsAnchor(GrantClientAccess.of(repo, clients, grants), new GrantClientAccessCommand(clientUser, other)), UseCaseError.BusinessRule.class, "NOT_PARTNER_SCOPE");

        runAsAnchor(RevokeClientAccess.of(repo, grants), new RevokeClientAccessCommand(partner, other));
        assertThat(reload(partner).assignedClients()).isEmpty();
        assertThat(eventsFor(partner, PrincipalEvents.CLIENT_ACCESS_REVOKED)).hasSize(1);
        assertUseCaseError(() -> runAsAnchor(RevokeClientAccess.of(repo, grants), new RevokeClientAccessCommand(partner, other)), UseCaseError.NotFound.class, "Grant_NOT_FOUND");
    }

    @Test
    void clientAssociationAnchorChangeClientAndToPartner() {
        String home = seedClient("assocA");
        String next = seedClient("assocB");
        String id = createdUser("assoc", "CLIENT", home);
        runAsAnchor(SetClientAssociation.of(repo, clients), new SetClientAssociationCommand(id, next, ClientAssociationMode.CHANGE_CLIENT));
        assertThat(reload(id).clientId()).isEqualTo(next);
        runAsAnchor(SetClientAssociation.of(repo, clients), new SetClientAssociationCommand(id, home, ClientAssociationMode.TO_PARTNER));
        var partner = reload(id);
        assertThat(partner.scope()).isEqualTo(UserScope.PARTNER);
        assertThat(partner.clientId()).isNull();
        assertThat(partner.assignedClients()).as("old home + new client as grants").containsExactlyInAnyOrder(home, next);
        runAsAnchor(SetClientAssociation.of(repo, clients), new SetClientAssociationCommand(id, "*", null));
        assertThat(reload(id).scope()).isEqualTo(UserScope.ANCHOR);
        assertThat(eventsFor(id, PrincipalEvents.USER_UPDATED)).hasSize(3);
        assertThat(auditsFor(id, "SetClientAssociationCommand")).hasSize(3);
        assertUseCaseError(() -> runAsAnchor(SetClientAssociation.of(repo, clients), new SetClientAssociationCommand(id, next, null)), UseCaseError.Validation.class, "MODE_REQUIRED");
        assertUseCaseError(() -> runAsAnchor(SetClientAssociation.of(repo, clients), new SetClientAssociationCommand(id, "clt_missing", ClientAssociationMode.CHANGE_CLIENT)), UseCaseError.NotFound.class, "Client_NOT_FOUND");
        assertUseCaseError(() -> runAsAnchor(SetClientAssociation.of(repo, clients), new SetClientAssociationCommand(id, " ", null)), UseCaseError.Validation.class, "CLIENT_ID_REQUIRED");
        assertUseCaseError(() -> runAsAnchor(SetClientAssociation.of(repo, clients), new SetClientAssociationCommand(seedService("assoc"), "*", null)), UseCaseError.BusinessRule.class, "NOT_A_USER");
    }

    // ── Sync ───────────────────────────────────────────────────────────────

    private static final ClientConfigRepository clientConfigs = new ClientConfigRepository(DS);

    private static Operation<SyncPrincipalsCommand, PrincipalEvents.PrincipalsSynced> sync() {
        return SyncPrincipals.of(repo, roles, clientConfigs);
    }

    @Test
    void syncPrincipalsUpsertsMergesRolesCarriesHashesAndStripsUnlisted() {
        String app = "syncapp" + RUN;
        String existing = createdUser("syncold", "CLIENT", seedClient("sync"));
        String hashBefore = storedHash(existing);
        Role admin = seedRole("syncapp", "admin");
        Role viewer = seedRole("syncapp", "viewer");
        runAsAnchor(AssignRoles.of(repo, roles), new AssignRolesCommand(existing, List.of(admin.name())));
        String hash = "$2y$10$migratedbcrypt";
        var ev = runAsAnchor(sync(), new SyncPrincipalsCommand(app, List.of(
                new SyncPrincipalInput(email("syncold").toUpperCase(Locale.ROOT), "Old Renamed", List.of(viewer.name().toUpperCase(Locale.ROOT)), false, hash),
                new SyncPrincipalInput(email("syncnew"), "Brand New", List.of(viewer.name()), true, null)), false));
        assertThat(ev.created()).isEqualTo(1);
        assertThat(ev.updated()).isEqualTo(1);
        assertThat(ev.deactivated()).isZero();
        assertThat(ev.syncedEmails()).containsExactly(email("syncold"), email("syncnew"));
        assertThat(ev.subject()).isEqualTo("platform.principals." + app);
        var old = reload(existing);
        assertThat(old.name()).isEqualTo("Old Renamed");
        assertThat(old.active()).isFalse();
        assertThat(old.roleNames()).containsExactlyInAnyOrder(admin.name(), viewer.name());
        assertThat(storedHash(existing))
                .as("owner ruling 2026-09-25 (item 4): not even a super-admin's sync replaces an existing hash")
                .isEqualTo(hashBefore);
        assertThat(SyncPrincipals.passwordHashesIgnored(repo, new SyncPrincipalsCommand(app, List.of(
                new SyncPrincipalInput(email("syncold"), "x", List.of(), true, hash),
                new SyncPrincipalInput(email("syncnew2"), "y", List.of(), true, hash),
                new SyncPrincipalInput(email("syncnew"), "z", List.of(), true, null)), false)))
                .as("reported: only an existing principal that was sent a hash").containsExactly(email("syncold"));
        var fresh = repo.findByEmail(email("syncnew")).orElseThrow();
        assertThat(fresh.scope()).isEqualTo(UserScope.CLIENT);
        assertThat(fresh.roles().getFirst().assignmentSource()).isEqualTo(RoleAssignment.SDK_SYNC);
        assertThat(fresh.userIdentity().hasPassword()).isFalse();
        assertThat(DB.fetch("SELECT data::text AS data FROM msg_events WHERE type = ? AND subject = ?", PrincipalEvents.PRINCIPALS_SYNCED, "platform.principals." + app)
                .getFirst().get("data", String.class)).doesNotContain(hash);
        assertThat(auditsFor(existing, "SyncPrincipalsCommand").getFirst().get("operation_json", String.class)).doesNotContain(hash);

        var second = runAsAnchor(sync(), new SyncPrincipalsCommand(null, List.of(
                new SyncPrincipalInput(email("syncnew"), "Brand New", List.of(), true, null)), true));
        assertThat(second.deactivated()).isGreaterThanOrEqualTo(1);
        assertThat(second.subject()).isEqualTo("platform.principals");
        assertThat(reload(existing).roleNames()).as("SDK roles stripped, admin role kept, hash kept").containsExactly(admin.name());
        assertThat(storedHash(existing)).isEqualTo(hashBefore);
        assertUseCaseError(() -> runAsAnchor(sync(), new SyncPrincipalsCommand(null, List.of(), false)), UseCaseError.Validation.class, "PRINCIPALS_REQUIRED");
    }

    /// X-02(c) (ruled 2026-09-01): `removeUnlisted` strips only the SDK_SYNC
    /// roles belonging to THE SYNCING APPLICATION (role names are
    /// app-prefixed `app:role`) — a different application's SDK_SYNC role on
    /// a principal absent from THIS payload must survive. Asserts a count
    /// that must change (X's role is gone) alongside one that must NOT (Y
    /// keeps its role AND its `updatedAt` is untouched — a bug that swept
    /// every absent principal's SDK_SYNC roles would still leave Y's role
    /// name present if Y only had X's, but here Y's own role is a distinct
    /// value the old bug would have deleted).
    @Test
    void syncPrincipalsRemoveUnlistedStripsOnlyTheSyncingApplicationsSdkSyncRoles() {
        String userX = createdUser("x02cx", "CLIENT", seedClient("x02cx"));
        String userY = createdUser("x02cy", "CLIENT", seedClient("x02cy"));
        String roleX = seedRole("x02cappx", "viewer").name();
        String roleY = seedRole("x02cappy", "viewer").name();
        runAsAnchor(sync(), new SyncPrincipalsCommand("x02cappx" + RUN, List.of(
                new SyncPrincipalInput(email("x02cx"), "X", List.of(roleX), true, null)), false));
        runAsAnchor(sync(), new SyncPrincipalsCommand("x02cappy" + RUN, List.of(
                new SyncPrincipalInput(email("x02cy"), "Y", List.of(roleY), true, null)), false));
        var yBefore = reload(userY);
        assertThat(yBefore.roleNames()).containsExactly(roleY);

        // App X sweeps with removeUnlisted and an EMPTY payload — X is now
        // absent from its own app's payload, and so is Y (Y was never in it).
        // Under the old (unscoped) sweep this would strip Y's role too.
        var swept = runAsAnchor(sync(), new SyncPrincipalsCommand("x02cappx" + RUN, List.of(
                new SyncPrincipalInput(email("x02cz-not-present"), "Z", List.of(), true, null)), true));
        assertThat(swept.deactivated()).as("only X's own role is in this sweep's scope").isEqualTo(1);
        assertThat(reload(userX).roleNames()).as("X's role from the syncing application is stripped").isEmpty();
        var yAfter = reload(userY);
        assertThat(yAfter.roleNames()).as("a different application's role survives an unrelated app's sweep")
                .containsExactly(roleY);
        assertThat(yAfter.updatedAt()).as("Y's row must not even be touched, not just left with the same role")
                .isEqualTo(yBefore.updatedAt());
    }

    /// The same X-02(c) rule for a NAMED principal: application Y syncing a user
    /// replaces only Y's SDK_SYNC roles — application X's, from X's own sync,
    /// survive. Before, Y's sync replaced the whole SDK_SYNC set and stripped X's.
    /// Mutant: the unscoped `syncSourcedRoles(source, names)`.
    @Test
    void anApplicationsSyncOfANamedUserKeepsAnotherApplicationsSdkRoles() {
        String user = createdUser("x02cn", "CLIENT", seedClient("x02cn"));
        String roleX = seedRole("x02cnappx", "viewer").name();
        String roleY = seedRole("x02cnappy", "editor").name();
        runAsAnchor(sync(), new SyncPrincipalsCommand("x02cnappx" + RUN, List.of(
                new SyncPrincipalInput(email("x02cn"), "N", List.of(roleX), true, null)), false));
        runAsAnchor(sync(), new SyncPrincipalsCommand("x02cnappy" + RUN, List.of(
                new SyncPrincipalInput(email("x02cn"), "N", List.of(roleY), true, null)), false));

        assertThat(reload(user).roleNames()).as("each application's sync owns only its own roles")
                .containsExactlyInAnyOrder(roleX, roleY);

        // Y re-syncs the user with no roles: Y's is gone, X's is still there.
        runAsAnchor(sync(), new SyncPrincipalsCommand("x02cnappy" + RUN, List.of(
                new SyncPrincipalInput(email("x02cn"), "N", List.of(), true, null)), false));
        assertThat(reload(user).roleNames()).containsExactly(roleX);
    }

    /// X-02(d): a `removeUnlisted` sweep with no `applicationCode` (the
    /// platform-level route) is a platform-wide sweep of every application's
    /// SDK_SYNC roles, so it is refused for a non-anchor even though the
    /// coarse sync permission is otherwise unchecked by this operation.
    @Test
    void syncPrincipalsPlatformScopeRemoveUnlistedRefusesNonAnchor() {
        var nonAnchor = new AuthContext(EntityType.PRINCIPAL.generate(), Scope.CLIENT, "c@x.io", List.of("cli_x02d"),
                List.of(), List.of(), true, List.of());
        assertUseCaseError(() -> runAs(nonAnchor, sync(), new SyncPrincipalsCommand(null, List.of(
                        new SyncPrincipalInput(email("x02d-refused"), "D", List.of(), true, null)), true)),
                UseCaseError.Authorization.class, "ANCHOR_REQUIRED_FOR_PLATFORM_SWEEP");
        // The same non-anchor may still sync WITHOUT removeUnlisted, or WITH
        // an application code — neither is a platform-wide sweep.
        var ok = runAs(nonAnchor, sync(), new SyncPrincipalsCommand(null, List.of(
                new SyncPrincipalInput(email("x02d-ok"), "D", List.of(), true, null)), false));
        assertThat(ok.created()).isEqualTo(1);
    }

    // ── Sync authority (security-fixes S1.3) ───────────────────────────────

    /// A role that grants platform authority: `platform:`-named, no owning
    /// application id — what `platform:super-admin` is.
    private static Role seedPlatformRole(String tag) {
        var r = Role.create("platform", tag + RUN, tag).withPermissions(List.of("platform:*:*:*"));
        uow.inTransaction(tx -> { roles.persist(r, tx.dbTx()); return null; });
        return r;
    }

    /// A client admin who IS `principalId`, homed on `clientId`, holding the
    /// user permissions `platform:client-admin` holds.
    private static AuthContext clientAdminSelf(String principalId, String clientId) {
        return new AuthContext(principalId, Scope.CLIENT, "self@x.io", List.of(clientId), List.of(), List.of(), true,
                List.of("platform:iam:user:create", "platform:iam:user:update", "platform:iam:user:assign-roles"));
    }

    /// Pinned: a client admin cannot sync a platform role onto itself — not
    /// on the platform route (`PLATFORM_ROLE_FORBIDDEN`, the same rule as
    /// `PUT /api/principals/{id}/roles`) and not through an application's
    /// route (`ROLE_APP_FORBIDDEN`: the role is not that application's). The
    /// observable effect: its role set is exactly what it was.
    @Test
    void aClientAdminCannotSyncAPlatformRoleOntoItself() {
        String client = seedClient("s13self");
        String self = createdUser("s13self", "CLIENT", client);
        Role superAdmin = seedPlatformRole("s13sa");
        var ac = clientAdminSelf(self, client);

        assertUseCaseError(() -> runAs(ac, sync(), new SyncPrincipalsCommand(null, List.of(
                        new SyncPrincipalInput(email("s13self"), "Me", List.of(superAdmin.name()), true, null)), false)),
                UseCaseError.Authorization.class, "PLATFORM_ROLE_FORBIDDEN");
        assertUseCaseError(() -> runAs(ac, sync(), new SyncPrincipalsCommand("s13app" + RUN, List.of(
                        new SyncPrincipalInput(email("s13self"), "Me", List.of(superAdmin.name()), true, null)), false)),
                UseCaseError.Authorization.class, "ROLE_APP_FORBIDDEN");
        assertThat(reload(self).roleNames()).as("no platform role landed").isEmpty();
    }

    /// Role names must exist and, on an application's route, belong to that
    /// application — for an anchor super-admin too: an SDK credential cannot
    /// hand out another application's (or the platform's) roles.
    @Test
    void syncRefusesAnUnknownRoleAndAnotherApplicationsRole() {
        Role other = seedRole("s13oth", "admin");
        assertUseCaseError(() -> runAsAnchor(sync(), new SyncPrincipalsCommand("s13mine" + RUN, List.of(
                        new SyncPrincipalInput(email("s13unknown"), "U", List.of("s13mine" + RUN + ":nope"), true, null)), false)),
                UseCaseError.Validation.class, "UNKNOWN_ROLE");
        assertUseCaseError(() -> runAsAnchor(sync(), new SyncPrincipalsCommand("s13mine" + RUN, List.of(
                        new SyncPrincipalInput(email("s13other"), "O", List.of(other.name()), true, null)), false)),
                UseCaseError.Authorization.class, "ROLE_APP_FORBIDDEN");
        assertThat(repo.findByEmail(email("s13unknown"))).as("refused batch creates nothing").isEmpty();
        assertThat(repo.findByEmail(email("s13other"))).isEmpty();
    }

    /// Pinned: a client admin cannot set an anchor user's password hash (the
    /// anchor is outside a client admin's remit — `SYNC_TARGET_FORBIDDEN`,
    /// hash unchanged); and an anchor that is not a super-admin may sync an
    /// existing user's name but its hash is NOT applied — only a new
    /// principal takes a hash from a non-super-admin.
    @Test
    void aSyncCannotTakeOverAnExistingAccountByItsPasswordHash() {
        String client = seedClient("s13hash");
        String anchorUser = runAsAnchor(CreateUser.of(repo), new CreateCommand(email("s13anchor"), null, "ANCHOR", null,
                "correct-horse-battery", null)).userId();
        String before = storedHash(anchorUser);
        String attackerHash = "$2y$10$attackercontrolledhashvalue";

        assertUseCaseError(() -> runAs(clientAdmin(client), sync(), new SyncPrincipalsCommand(null, List.of(
                        new SyncPrincipalInput(email("s13anchor"), "Owned", List.of(), true, attackerHash)), false)),
                UseCaseError.Authorization.class, "SYNC_TARGET_FORBIDDEN");
        assertThat(storedHash(anchorUser)).isEqualTo(before);
        assertThat(reload(anchorUser).name()).isNotEqualTo("Owned");

        var iamAdmin = new AuthContext(EntityType.PRINCIPAL.generate(), Scope.ANCHOR, "iam@x.io", List.of(), List.of(),
                List.of(), true, List.of("platform:iam:user:update"));
        var ev = runAs(iamAdmin, sync(), new SyncPrincipalsCommand(null, List.of(
                new SyncPrincipalInput(email("s13anchor"), "Renamed", List.of(), true, attackerHash),
                new SyncPrincipalInput(email("s13fresh"), "Fresh", List.of(), true, attackerHash)), false));
        assertThat(ev.updated()).isEqualTo(1);
        assertThat(reload(anchorUser).name()).as("the rest of the entry applies").isEqualTo("Renamed");
        assertThat(storedHash(anchorUser)).as("a non-super-admin never replaces an existing hash").isEqualTo(before);
        assertThat(storedHash(repo.findByEmail(email("s13fresh")).orElseThrow().id()))
                .as("a principal the sync creates takes the hash").isEqualTo(attackerHash);
    }

    /// Pinned: a client admin cannot deactivate a principal outside its reach
    /// — naming it refuses the batch (`SYNC_TARGET_FORBIDDEN`, still active),
    /// and an app-scoped `removeUnlisted` sweep skips it while still sweeping
    /// the in-reach user (the counter that must change, `deactivated == 1`).
    @Test
    void aClientAdminCannotDeactivateOrSweepAPrincipalOutsideItsReach() {
        String mine = seedClient("s13mine");
        String theirs = seedClient("s13theirs");
        String inReach = createdUser("s13in", "CLIENT", mine);
        String outOfReach = createdUser("s13out", "CLIENT", theirs);
        String app = "s13sweep" + RUN;
        String appRole = seedRole("s13sweep", "viewer").name();
        runAsAnchor(sync(), new SyncPrincipalsCommand(app, List.of(
                new SyncPrincipalInput(email("s13in"), "In", List.of(appRole), true, null),
                new SyncPrincipalInput(email("s13out"), "Out", List.of(appRole), true, null)), false));

        assertUseCaseError(() -> runAs(clientAdmin(mine), sync(), new SyncPrincipalsCommand(null, List.of(
                        new SyncPrincipalInput(email("s13out"), "Out", List.of(), false, null)), false)),
                UseCaseError.Authorization.class, "SYNC_TARGET_FORBIDDEN");
        assertThat(reload(outOfReach).active()).isTrue();

        var swept = runAs(clientAdmin(mine), sync(), new SyncPrincipalsCommand(app, List.of(
                new SyncPrincipalInput(email("s13sweep-new"), "N", List.of(), true, null)), true));
        assertThat(swept.deactivated()).as("only the in-reach user is swept").isEqualTo(1);
        assertThat(reload(inReach).roleNames()).isEmpty();
        assertThat(reload(outOfReach).roleNames()).as("out of reach: untouched").containsExactly(appRole);
    }

    // ── Developer credential ───────────────────────────────────────────────

    @Test
    void developerCredentialIsIssuedOnceEncryptedAndRevocable() {
        String id = createdUser("dev", "ANCHOR", null);
        var secrets = DeveloperSecrets.withEncryption(Encryption.withKey(Encryption.generateKey()));
        // The sink is a local: the plaintext reaches exactly one caller and
        // dies with the frame, where it used to sit in a process-wide map for
        // two minutes — including when the commit that stored its encrypted
        // form failed.
        var disclosed = new java.util.concurrent.atomic.AtomicReference<String>();
        assertUseCaseError(() -> runAsAnchor(SetDeveloperCredential.of(repo, secrets, disclosed::set), new SetDeveloperCredentialCommand(id)), UseCaseError.BusinessRule.class, "NOT_A_DEVELOPER");
        assertThat(disclosed.get()).as("a rejected request never reaches the minting path").isNull();
        if (roles.findByName(SetDeveloperCredential.DEVELOPER_ROLE).isEmpty()) {
            var r = Role.create("platform", "developer", "Developer");
            uow.inTransaction(tx -> { roles.persist(r, tx.dbTx()); return null; });
        }
        runAsAnchor(AssignRoles.of(repo, roles), new AssignRolesCommand(id, List.of(SetDeveloperCredential.DEVELOPER_ROLE)));
        var ev = runAsAnchor(SetDeveloperCredential.of(repo, secrets, disclosed::set), new SetDeveloperCredentialCommand(id));
        String plaintext = disclosed.get();
        assertThat(plaintext).as("disclosed to the caller that asked").isNotBlank();
        var got = reload(id);
        assertThat(got.hasDeveloperSecret()).isTrue();
        assertThat(got.userIdentity().devClientSecretRef()).isNotEqualTo(plaintext);
        assertThat(eventsFor(id, PrincipalEvents.DEVELOPER_CREDENTIAL_SET).getFirst().get("data", String.class)).doesNotContain(plaintext).contains("\"userId\"");
        assertThat(auditsFor(id, "SetDeveloperCredentialCommand").getFirst().get("operation_json", String.class)).doesNotContain(plaintext);
        assertThat(ev.userId()).isEqualTo(id);

        var self = new AuthContext(id, Scope.CLIENT, email("dev"), List.of(), List.of(), List.of(), false, List.of());
        runAs(self, RevokeDeveloperCredential.of(repo), new RevokeDeveloperCredentialCommand(id));
        assertThat(reload(id).hasDeveloperSecret()).isFalse();
        assertThat(eventsFor(id, PrincipalEvents.DEVELOPER_CREDENTIAL_REVOKED)).hasSize(1);
        assertUseCaseError(() -> runAsAnchor(SetDeveloperCredential.of(repo, DeveloperSecrets.unconfigured(), disclosed::set), new SetDeveloperCredentialCommand(id)), UseCaseError.Internal.class, "SECRET");
        var stranger = new AuthContext(EntityType.PRINCIPAL.generate(), Scope.CLIENT, "s@x.io", List.of(), List.of(), List.of(), false, List.of());
        var refused = new java.util.concurrent.atomic.AtomicReference<String>();
        // PR-4 (ruled 2026-09-01): blockNonClientTarget ("wrong kind of
        // administrator") now runs before the scope check, so a non-anchor
        // targeting a non-CLIENT-scope (here ANCHOR) principal is FORBIDDEN,
        // not ANCHOR_REQUIRED — both are 403, but the block check is the one
        // that fires first (ledger PR-3/PR-4's requireUserAdmin ordering).
        assertUseCaseError(() -> runAs(stranger, SetDeveloperCredential.of(repo, secrets, refused::set), new SetDeveloperCredentialCommand(id)), UseCaseError.Authorization.class, "FORBIDDEN");
        assertThat(refused.get()).as("an unauthorised caller never reaches the minting path").isNull();
    }

    // ── Repository reads ───────────────────────────────────────────────────

    @Test
    void versionIsTheLaterOfRowAndRoleUpdates() {
        String id = createdUser("version", "ANCHOR", null);
        var before = repo.lookupVersion(id).orElseThrow();
        Role r = seedRole("verapp", "late");
        runAsAnchor(AssignRoles.of(repo, roles), new AssignRolesCommand(id, List.of(r.name())));
        assertThat(repo.lookupVersion(id).orElseThrow()).isAfterOrEqualTo(before);
        assertThat(repo.lookupVersion("prn_missing")).isEmpty();
        assertThat(repo.findByRole(r.name())).extracting(Principal::id).containsExactly(id);
        assertThat(repo.findUsersByEmailDomain("OPS.TEST")).extracting(Principal::id).contains(id);
        assertThat(repo.findAll()).extracting(Principal::id).contains(id);
    }
}
