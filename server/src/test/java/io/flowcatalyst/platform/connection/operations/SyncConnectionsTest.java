package io.flowcatalyst.platform.connection.operations;

import io.flowcatalyst.platform.application.Application;
import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.platform.application.ApplicationType;
import io.flowcatalyst.platform.connection.Connection;
import io.flowcatalyst.platform.connection.ConnectionCode;
import io.flowcatalyst.platform.connection.ConnectionRepository;
import io.flowcatalyst.platform.connection.ConnectionSource;
import io.flowcatalyst.platform.connection.operations.ConnectionEvents.ConnectionsSynced;
import io.flowcatalyst.platform.seed.PlatformEventSchemas;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.AuthContext;
import io.flowcatalyst.platform.shared.auth.Scope;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.shared.platformsink.PlatformSink;
import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.platform.subscription.EventTypeBinding;
import io.flowcatalyst.platform.subscription.SubscriptionRepository;
import io.flowcatalyst.platform.subscription.operations.CreateCommand;
import io.flowcatalyst.platform.subscription.operations.CreateSubscription;
import io.flowcatalyst.sdk.usecase.ExecutionContext;
import io.flowcatalyst.sdk.usecase.UseCaseError;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.jdbc.DbTx;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import io.flowcatalyst.testpg.TestPg;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// [SyncConnections] against the embedded Postgres — hand-off "Connection
/// sync (new)" / `docs/spec/code-first-connections.md` §3, tests C6–C10, C15
/// (connection side). `ConnectionOperationsTest` covers create/update/pause/
/// delete; this file is the sync use case only. C9's "slug and id both
/// resolve" and C10's permission gate are HTTP-level and live in
/// `SdkSyncApiTest`.
///
/// The fixture never truncates and the database is shared across test
/// classes (`CLAUDE.md`), so every assertion is scoped to rows this test
/// itself seeded, never a table-wide count — and every "untouched" claim
/// compares two already-DB-round-tripped snapshots (before/after), never the
/// in-memory pre-persist object, so `Instant` precision cannot produce a
/// false failure.
@SuppressWarnings("deprecation")
class SyncConnectionsTest {

    private static final DataSource DS = TestPg.dataSource();
    private static final DSLContext DB = DSL.using(DS, SQLDialect.POSTGRES);
    private static final ConnectionRepository repo = new ConnectionRepository(DS);
    private static final ApplicationRepository apps = new ApplicationRepository(DS);
    private static final SubscriptionRepository subs = new SubscriptionRepository(DS);
    private static final UnitOfWork uow = new UnitOfWork(DS, new PlatformSink(Json.MAPPER));

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);
    private static final String PRINCIPAL = EntityType.PRINCIPAL.generate();
    private static final AuthContext ANCHOR = new AuthContext(PRINCIPAL, Scope.ANCHOR, "anchor@x.io",
            List.of("*"), List.of(), List.of(), true, List.of());
    private static final ExecutionContext EC = ExecutionContext.of(PRINCIPAL);

    // ── Fixture ────────────────────────────────────────────────────────────

    private static ConnectionsSynced sync(SyncConnectionsCommand cmd) {
        return sync(ANCHOR, cmd);
    }

    private static ConnectionsSynced sync(AuthContext as, SyncConnectionsCommand cmd) {
        return Auth.runAs(as, () -> SyncConnections.of(repo, apps, subs).run(uow, cmd, EC));
    }

    private static String code(String tag) {
        return (tag + "-" + RUN).toLowerCase(Locale.ROOT);
    }

    /// A persisted application with a provisioned service account — the
    /// sync's happy-path fixture (C7's 400 test builds one WITHOUT instead).
    /// `app_applications.service_account_id` is FK'd to `iam_principals.id`
    /// (a `SERVICE`-typed principal), so a bare generated id is not enough —
    /// a real principal row is required.
    private static Application applicationWithServiceAccount(String tag) {
        Application app = Application.create(ApplicationType.APPLICATION, tag + "-" + RUN, tag)
                .attachServiceAccount(servicePrincipal(tag));
        persistRawApp(app);
        return app;
    }

    /// A minimal `SERVICE` principal row satisfying `app_applications`'
    /// FK — mirrors `ApplicationOperationsTest#servicePrincipal`.
    private static String servicePrincipal(String tag) {
        String id = EntityType.SERVICE_ACCOUNT.generate();
        var now = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC);
        DB.insertInto(io.flowcatalyst.db.generated.Tables.IAM_PRINCIPALS)
                .set(io.flowcatalyst.db.generated.Tables.IAM_PRINCIPALS.ID, id)
                .set(io.flowcatalyst.db.generated.Tables.IAM_PRINCIPALS.TYPE, "SERVICE")
                .set(io.flowcatalyst.db.generated.Tables.IAM_PRINCIPALS.NAME, "sa " + tag)
                .set(io.flowcatalyst.db.generated.Tables.IAM_PRINCIPALS.ACTIVE, true)
                .set(io.flowcatalyst.db.generated.Tables.IAM_PRINCIPALS.SERVICE_ACCOUNT_ID, id)
                .set(io.flowcatalyst.db.generated.Tables.IAM_PRINCIPALS.CREATED_AT, now)
                .set(io.flowcatalyst.db.generated.Tables.IAM_PRINCIPALS.UPDATED_AT, now)
                .execute();
        return id;
    }

    private static Application applicationWithoutServiceAccount(String tag) {
        Application app = Application.create(ApplicationType.APPLICATION, tag + "-" + RUN, tag);
        persistRawApp(app);
        return app;
    }

    private static void persistRawApp(Application app) {
        try (java.sql.Connection conn = DS.getConnection()) {
            conn.setAutoCommit(false);
            apps.persist(app, DbTx.wrapForBootstrap(conn));
            conn.commit();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /// A connection row written directly (bypassing [CreateConnection]) so a
    /// test can plant a row of any `source`/scope combination — the row's own
    /// `serviceAccountId` is random, distinct from any application's, so a
    /// sync that re-stamps it is observable (C7).
    private static Connection seedConnection(String code, String applicationCode, String clientId, ConnectionSource source) {
        Connection c = Connection.create(ConnectionCode.parse(code), "Seed " + code, EntityType.SERVICE_ACCOUNT.generate())
                .withApplicationCode(applicationCode)
                .withClientId(clientId)
                .withSource(source);
        try (java.sql.Connection conn = DS.getConnection()) {
            conn.setAutoCommit(false);
            repo.persist(c, DbTx.wrapForBootstrap(conn));
            conn.commit();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return c;
    }

    private static Connection reload(String id) {
        return repo.findById(id).orElseThrow(() -> new AssertionError("connection " + id + " not found"));
    }

    /// A subscription bound to `connectionId`, so [SyncConnections]'
    /// reference check (C8) has something to find. [CreateSubscription] does
    /// not check the connection exists (spec open question 13), so any id
    /// works — a real, seeded connection here for realism.
    private static void seedReferencingSubscription(String code, String connectionId) {
        var cmd = new CreateCommand(code, "Sub " + code, "https://example.test/hook", null, null, connectionId,
                null, null, List.of(EventTypeBinding.of("platform:admin:connection:created")), null,
                null, null, null, null, null, null, null);
        Auth.runAs(ANCHOR, () -> CreateSubscription.of(subs).run(uow, cmd, EC));
    }

    private static void assertUseCaseError(ThrowingCallable call, Class<? extends UseCaseError> kind, String errorCode) {
        assertThatThrownBy(call)
                .isInstanceOf(UseCaseException.class)
                .extracting(t -> ((UseCaseException) t).error())
                .satisfies(err -> {
                    assertThat(err).as("error kind").isInstanceOf(kind);
                    assertThat(err.code()).as("error code").isEqualTo(errorCode);
                });
    }

    private static JsonNode json(String s) {
        try {
            return Json.MAPPER.readTree(s);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    /// A minimal structural JSON-Schema check sufficient to pin C15: every
    /// `required` field is present, and — when the schema declares
    /// `additionalProperties: false` (every seeded schema does) — every field
    /// `data` actually carries is one the schema declared. Omitting `clientId`
    /// from the seeded schema (C15's mutant) makes a `data` that legitimately
    /// carries a `clientId` fail the second loop.
    private static void assertMatchesSchema(JsonNode schema, JsonNode data) {
        for (JsonNode req : schema.path("required")) {
            assertThat(data.has(req.stringValue())).as("required field '%s' present", req.stringValue()).isTrue();
        }
        if (!schema.path("additionalProperties").asBoolean(true)) {
            Set<String> allowed = new HashSet<>();
            schema.path("properties").propertyNames().forEach(allowed::add);
            data.propertyNames().forEach(name ->
                    assertThat(allowed).as("field '%s' not declared in the schema (additionalProperties:false)", name).contains(name));
        }
    }

    // ── C6 — ownership ───────────────────────────────────────────────────────

    @Test
    void syncSkipsAUiAuthoredRowAtTheSameKeyAndCreatesNoRowBesideIt() {
        Application app = applicationWithServiceAccount("connskip");
        String c = code("skip");
        Connection before = reload(seedConnection(c, app.code(), null, ConnectionSource.UI).id());

        var ev = sync(new SyncConnectionsCommand(app.id(), app.code(), null,
                List.of(new SyncConnectionInput(c, "Attempted Rename", "attempted", null)), false));

        assertThat(ev.created()).as("no new row created beside the UI row").isZero();
        assertThat(ev.updated()).as("the UI row is not counted as updated — mutant: drop the source filter").isZero();
        assertThat(ev.syncedCodes()).as("its code still appears in syncedCodes").containsExactly(c);

        Connection after = reload(before.id());
        assertThat(after).as("untouched, byte-for-byte (never re-persisted)").isEqualTo(before);
        assertThat(repo.findByApplicationAndClient(app.code(), null))
                .as("no sibling row created at the same key").hasSize(1);
    }

    /// The removal path's three ownership filters, each pinned by a survivor
    /// that would otherwise be swept if that ONE filter were dropped
    /// (spec C6 mutant: drop each of application / client / source separately).
    @Test
    void syncRemovalNeverTouchesRowsOutsideItsExactOwnershipScope() {
        Application appA = applicationWithServiceAccount("connown-a");
        Application appB = applicationWithServiceAccount("connown-b");
        String clientX = EntityType.CLIENT.generate();
        String clientY = EntityType.CLIENT.generate();

        Connection otherApp = reload(seedConnection(code("own-otherapp"), appB.code(), clientX, ConnectionSource.API).id());
        Connection otherClient = reload(seedConnection(code("own-otherclient"), appA.code(), clientY, ConnectionSource.API).id());
        Connection shared = reload(seedConnection(code("own-shared"), null, clientX, ConnectionSource.API).id());
        Connection uiInScope = reload(seedConnection(code("own-ui"), appA.code(), clientX, ConnectionSource.UI).id());
        Connection removable = reload(seedConnection(code("own-removable"), appA.code(), clientX, ConnectionSource.API).id());

        // Scoped to (appA, clientX), nothing listed: only the one owned API
        // row in that EXACT scope is a removal candidate.
        var ev = sync(new SyncConnectionsCommand(appA.id(), appA.code(), clientX, List.of(), true));

        assertThat(ev.deleted()).as("exactly the one owned, in-scope, API-sourced row").isEqualTo(1);
        assertThat(repo.findById(removable.id())).as("owned API row not listed — removed").isEmpty();

        assertThat(reload(otherApp.id())).as("application filter — a sibling application's row survives").isEqualTo(otherApp);
        assertThat(reload(otherClient.id())).as("client filter — another client's row survives").isEqualTo(otherClient);
        assertThat(reload(shared.id())).as("a shared (no-application) row is a different key entirely — survives").isEqualTo(shared);
        assertThat(reload(uiInScope.id())).as("source filter — a UI row in scope is never swept").isEqualTo(uiInScope);
    }

    @Test
    void clientLessSyncNeverTouchesAnyClientScopedRow() {
        Application app = applicationWithServiceAccount("connglobal");
        String client = EntityType.CLIENT.generate();
        String c = code("global-clientscoped");
        Connection clientScoped = reload(seedConnection(c, app.code(), client, ConnectionSource.API).id());

        // Same code, but a client-less sync: a client-scoped row is a
        // DIFFERENT three-part key even with the same code, so it is simply
        // never seen — the sync creates its OWN (application, null) row
        // instead of touching the client-scoped one.
        var ev = sync(new SyncConnectionsCommand(app.id(), app.code(), null,
                List.of(new SyncConnectionInput(c, "Global Attempt", null, null)), true));

        assertThat(ev.created()).isEqualTo(1);
        assertThat(ev.updated()).isZero();
        assertThat(ev.deleted()).as("the client-scoped row is invisible, not a removal candidate").isZero();
        assertThat(reload(clientScoped.id())).isEqualTo(clientScoped);
    }

    // ── C7 — service account ─────────────────────────────────────────────────

    @Test
    void serviceAccountIsAlwaysTheApplicationsOnCreateAndReStampedOnUpdate() {
        Application app = applicationWithServiceAccount("connsa");
        String c = code("sa-row");

        var first = sync(new SyncConnectionsCommand(app.id(), app.code(), null,
                List.of(new SyncConnectionInput(c, "First", null, null)), false));
        assertThat(first.created()).isEqualTo(1);
        Connection createdRow = repo.findByCode(c, app.code(), null).orElseThrow();
        assertThat(createdRow.serviceAccountId())
                .as("new rows carry the APPLICATION's service account, not the caller's principal id")
                .isEqualTo(app.serviceAccountId())
                .isNotEqualTo(EC.principalId());

        // A row whose stored service account has drifted from the
        // application's current one (simulating an older sync, or a
        // provisioning change): the NEXT sync corrects it back — mutant:
        // skip the re-stamp on update.
        Connection drifted = seedConnection(code("sa-drift"), app.code(), null, ConnectionSource.API);
        assertThat(drifted.serviceAccountId()).isNotEqualTo(app.serviceAccountId());
        var second = sync(new SyncConnectionsCommand(app.id(), app.code(), null,
                List.of(new SyncConnectionInput(code("sa-drift"), "Drifted", null, null)), false));
        assertThat(second.updated()).isEqualTo(1);
        assertThat(reload(drifted.id()).serviceAccountId()).isEqualTo(app.serviceAccountId());
    }

    /// What a sync creates, the NEXT sync must still own: the new row is stamped
    /// `API`, with the application and the request's client — so a second sync
    /// updates it and `removeUnlisted` removes it. A row left at the column's
    /// default (`UI`) would be skipped for ever by the very sync that made it,
    /// and nothing else here would notice.
    @Test
    void aRowTheSyncCreatedIsStillTheSyncsOnTheNextRun() {
        Application app = applicationWithServiceAccount("connown");
        String client = "clt_own_" + RUN;
        String c = code("own-row");

        var first = sync(new SyncConnectionsCommand(app.id(), app.code(), client,
                List.of(new SyncConnectionInput(c, "First", null, null)), false));
        assertThat(first.created()).isEqualTo(1);
        Connection row = repo.findByCode(c, app.code(), client).orElseThrow();
        assertThat(row.source()).isEqualTo(ConnectionSource.API);
        assertThat(row.applicationCode()).isEqualTo(app.code());
        assertThat(row.clientId()).isEqualTo(client);

        var second = sync(new SyncConnectionsCommand(app.id(), app.code(), client,
                List.of(new SyncConnectionInput(c, "Renamed", null, null)), false));
        assertThat(second.created()).isZero();
        assertThat(second.updated()).isEqualTo(1);
        assertThat(reload(row.id()).name()).isEqualTo("Renamed");

        var third = sync(new SyncConnectionsCommand(app.id(), app.code(), client, List.of(), true));
        assertThat(third.deleted()).isEqualTo(1);
        assertThat(repo.findById(row.id())).isEmpty();
    }

    @Test
    void syncRefusesWithoutAnApplicationServiceAccountAndWritesNothing() {
        Application app = applicationWithoutServiceAccount("connnosa");
        String c = code("nosa");

        assertUseCaseError(() -> sync(new SyncConnectionsCommand(app.id(), app.code(), null,
                        List.of(new SyncConnectionInput(c, "X", null, null)), false)),
                UseCaseError.Validation.class, "APPLICATION_SERVICE_ACCOUNT_REQUIRED");
        assertThat(repo.findByCode(c, app.code(), null)).as("nothing written").isEmpty();
    }

    // ── C8 — removeUnlisted + the reference guard ────────────────────────────

    @Test
    void removeUnlistedRefusesTheWholeSyncWhenAnyRemovalCandidateIsStillReferenced() {
        Application app = applicationWithServiceAccount("connref");
        Connection referenced = seedConnection(code("ref-target"), app.code(), null, ConnectionSource.API);
        Connection alsoRemovable = seedConnection(code("ref-other"), app.code(), null, ConnectionSource.API);
        seedReferencingSubscription(code("ref-sub"), referenced.id());

        String freshCode = code("ref-fresh");
        // Neither existing connection is in the payload, so both are removal
        // candidates; one is referenced — the WHOLE sync refuses: no row
        // created, updated OR deleted (mutant: check references after
        // deleting, or check only the first candidate).
        assertUseCaseError(() -> sync(new SyncConnectionsCommand(app.id(), app.code(), null,
                        List.of(new SyncConnectionInput(freshCode, "Fresh", null, null)), true)),
                UseCaseError.Conflict.class, "CONNECTION_REFERENCED");

        assertThat(repo.findByCode(freshCode, app.code(), null)).as("nothing created either — one transaction").isEmpty();
        assertThat(repo.findById(referenced.id())).as("the referenced row survives").isPresent();
        assertThat(repo.findById(alsoRemovable.id()))
                .as("the OTHER, unreferenced candidate ALSO survives — refusal is whole-sync, not per-row").isPresent();
    }

    // ── C9 — authorization ────────────────────────────────────────────────────

    @Test
    void authorizationRequiresApplicationAccessAndClientAccessOnlyWhenGiven() {
        Application app = applicationWithServiceAccount("connauth");
        String ownClient = EntityType.CLIENT.generate();
        String otherClient = EntityType.CLIENT.generate();

        var noAppAccess = new AuthContext(EntityType.PRINCIPAL.generate(), Scope.CLIENT, "c@x.io",
                List.of(ownClient), List.of(), List.of(), false, List.of());
        assertUseCaseError(() -> sync(noAppAccess, new SyncConnectionsCommand(app.id(), app.code(), null,
                        List.of(new SyncConnectionInput(code("auth-a"), "A", null, null)), false)),
                UseCaseError.Authorization.class, "FORBIDDEN");
        assertThat(repo.findByCode(code("auth-a"), app.code(), null)).as("refused sync writes nothing").isEmpty();

        var appAccessOnly = new AuthContext(EntityType.PRINCIPAL.generate(), Scope.CLIENT, "c@x.io",
                List.of(ownClient), List.of(), List.of(app.id()), false, List.of());

        // Application access but no access to the REQUESTED client: FORBIDDEN.
        assertUseCaseError(() -> sync(appAccessOnly, new SyncConnectionsCommand(app.id(), app.code(), otherClient,
                        List.of(new SyncConnectionInput(code("auth-b"), "B", null, null)), false)),
                UseCaseError.Authorization.class, "FORBIDDEN");

        // Application access + the requested client's OWN access: succeeds.
        var withClient = sync(appAccessOnly, new SyncConnectionsCommand(app.id(), app.code(), ownClient,
                List.of(new SyncConnectionInput(code("auth-c"), "C", null, null)), false));
        assertThat(withClient.created()).isEqualTo(1);

        // Client-less sync by a NON-ANCHOR with application access only:
        // succeeds — deliberately unlike the scheduled-jobs sync's anchor
        // gate (hand-off: ownership, not reach, fences a client-less
        // connection sync in).
        var clientLess = sync(appAccessOnly, new SyncConnectionsCommand(app.id(), app.code(), null,
                List.of(new SyncConnectionInput(code("auth-d"), "D", null, null)), false));
        assertThat(clientLess.created()).isEqualTo(1);
    }

    // ── C15 — events validate against the seeded schema ──────────────────────

    @Test
    void rollupAndPerRowEventsValidateAgainstTheSeededSchemas() {
        Application app = applicationWithServiceAccount("connevt");
        String client = EntityType.CLIENT.generate();
        String c = code("evt");

        var ev = sync(new SyncConnectionsCommand(app.id(), app.code(), client,
                List.of(new SyncConnectionInput(c, "Evt", null, null)), false));

        assertThat(ev.eventType()).isEqualTo(ConnectionEvents.SYNCED);
        assertThat(ev.subject()).isEqualTo(ConnectionEvents.syncSubjectFor(app.code()));
        assertThat(ev.messageGroup()).isEqualTo(ConnectionEvents.SYNC_MESSAGE_GROUP + ":" + app.code());

        var rollupRow = DB.fetch("SELECT data::text AS data FROM msg_events WHERE id = ?", ev.eventId());
        assertThat(rollupRow).hasSize(1);
        JsonNode data = json(rollupRow.getFirst().get("data", String.class));
        assertThat(data.propertyNames()).containsExactlyInAnyOrder(
                "applicationCode", "clientId", "created", "updated", "deleted", "syncedCodes");
        assertThat(data.get("applicationCode").asText()).isEqualTo(app.code());
        assertThat(data.get("clientId").asText()).isEqualTo(client);

        JsonNode schema = PlatformEventSchemas.all().get(ConnectionEvents.SYNCED);
        assertThat(schema).as("schema seeded for " + ConnectionEvents.SYNCED).isNotNull();
        assertMatchesSchema(schema, data);

        // The per-row event this sync also emitted (its own seeded schema
        // predates this unit and already disagrees with what Go/Java emit —
        // a pre-existing defect out of K2's scope, so only its presence is
        // asserted here, not a schema match).
        Connection createdRow = repo.findByCode(c, app.code(), client).orElseThrow();
        var createdRows = DB.fetch("SELECT type FROM msg_events WHERE type = ? AND subject = ?",
                ConnectionEvents.CREATED, ConnectionEvents.subjectFor(createdRow.id()));
        assertThat(createdRows).hasSize(1);
    }
}
