package io.flowcatalyst.platform.portalapp;

import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.platform.client.Client;
import io.flowcatalyst.platform.client.ClientIdentifier;
import io.flowcatalyst.platform.client.ClientRepository;
import io.flowcatalyst.platform.oauthclient.ClientType;
import io.flowcatalyst.platform.oauthclient.OAuthClient;
import io.flowcatalyst.platform.oauthclient.OAuthClientRepository;
import io.flowcatalyst.platform.portalidentity.PortalAppGrantSource;
import io.flowcatalyst.platform.portalidentity.PortalIdentity;
import io.flowcatalyst.platform.portalidentity.PortalIdentityRepository;
import io.flowcatalyst.platform.portalidentity.PortalIdentitySource;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.shared.platformsink.PlatformSink;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import io.flowcatalyst.testpg.TestPg;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/// `PortalAppRepository` against the embedded Postgres (spec `portal-apps.md`
/// §1, §2.4, §10): the §2.4 OAuth-client join, `userCounts`, and the FK
/// cascades onto `portal_identity_apps` from both sides.
///
/// The fixture never truncates; every row is namespaced by a per-JVM suffix.
@SuppressWarnings("deprecation")
class PortalAppRepositoryTest {

    private static final DataSource DS = TestPg.dataSource();
    private static final DSLContext DB = DSL.using(DS, SQLDialect.POSTGRES);
    private static final PortalAppRepository portalAppRepo = new PortalAppRepository(DS);
    private static final PortalIdentityRepository identityRepo = new PortalIdentityRepository(DS);
    private static final ClientRepository clientRepo = new ClientRepository(DS);
    private static final OAuthClientRepository oauthClientRepo = new OAuthClientRepository(DS, new ApplicationRepository(DS));
    private static final UnitOfWork uow = new UnitOfWork(DS, new PlatformSink(Json.MAPPER));

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toLowerCase(Locale.ROOT);

    private static String testClient(String tag) {
        Client c = Client.create("Portal App Test " + tag, ClientIdentifier.parse("pat-" + RUN + "-" + tag));
        uow.inTransaction(tx -> {
            clientRepo.persist(c, tx.dbTx());
            return null;
        });
        return c.id();
    }

    private static PortalApp create(String clientId, String tag) {
        PortalApp a = PortalApp.create(clientId, PortalAppCode.parse(tag + "-" + RUN), "App " + tag, null);
        uow.inTransaction(tx -> {
            portalAppRepo.persist(a, tx.dbTx());
            return null;
        });
        return a;
    }

    private static void persistIdentity(PortalIdentity p) {
        uow.inTransaction(tx -> {
            identityRepo.persist(p, tx.dbTx());
            return null;
        });
    }

    private static void persistOAuthClient(OAuthClient c) {
        uow.inTransaction(tx -> {
            oauthClientRepo.persist(c, tx.dbTx());
            return null;
        });
    }

    /// `portalAppId` has no wire setter yet (unit B's job, spec Part A J4) —
    /// built directly, exactly as a linked client would look once it lands.
    private static OAuthClient linkedTo(OAuthClient c, String portalAppId) {
        return new OAuthClient(c.id(), c.clientId(), c.clientName(), c.clientType(), c.secretRef(), c.previousSecretRef(),
                c.previousSecretExpiresAt(), c.previousSecretLastUsedAt(), c.redirectUris(), c.postLogoutRedirectUris(),
                c.grantTypes(), c.defaultScopes(), c.allowedOrigins(), c.applicationIds(), c.pkceRequired(), c.active(),
                c.principalId(), c.portalClientId(), portalAppId, c.apiAccess(), c.createdAt(), c.updatedAt());
    }

    private static int grantRowCount(String identityId) {
        return DB.fetch("SELECT 1 FROM portal_identity_apps WHERE identity_id = ?", identityId).size();
    }

    // ── Basic CRUD ─────────────────────────────────────────────────────────

    @Test
    void createNormalisesCodeAndPersistLeavesCodeAndClientIdImmutable() {
        String clientId = testClient("crud");
        PortalApp app = create(clientId, "Crud");
        assertThat(app.code()).isEqualTo(("crud-" + RUN).toLowerCase(Locale.ROOT));

        PortalApp reloaded = portalAppRepo.findById(app.id()).orElseThrow();
        assertThat(reloaded.name()).isEqualTo(app.name());

        PortalApp renamed = reloaded.update("Renamed", "desc", false);
        uow.inTransaction(tx -> {
            portalAppRepo.persist(renamed, tx.dbTx());
            return null;
        });
        PortalApp afterRename = portalAppRepo.findById(app.id()).orElseThrow();
        assertThat(afterRename.name()).isEqualTo("Renamed");
        assertThat(afterRename.active()).isFalse();
        assertThat(afterRename.code()).as("code never changes").isEqualTo(app.code());
        assertThat(afterRename.clientId()).as("client_id never changes").isEqualTo(clientId);
    }

    @Test
    void findByClientAndCodeNormalises() {
        String clientId = testClient("bycode");
        PortalApp app = create(clientId, "ByCode");
        assertThat(portalAppRepo.findByClientAndCode(clientId, "  " + app.code().toUpperCase(Locale.ROOT) + "  "))
                .map(PortalApp::id).contains(app.id());
        assertThat(portalAppRepo.findByClientAndCode(clientId, "no-such-code")).isEmpty();
    }

    // ── §2.4 OAuth-client join ────────────────────────────────────────────────

    @Test
    void findByOAuthClientIdReturnsTheLinkedAppAndEmptyForAnUnlinkedPortalClient() {
        String clientId = testClient("oac-link");
        PortalApp app = create(clientId, "oac-link");
        OAuthClient linked = linkedTo(OAuthClient.create("cli_" + RUN + "_link", "Linked", ClientType.PUBLIC)
                .withPortalAndApiAccess(clientId, false), app.id());
        persistOAuthClient(linked);

        OAuthClient legacy = OAuthClient.create("cli_" + RUN + "_legacy", "Legacy", ClientType.PUBLIC)
                .withPortalAndApiAccess(clientId, false); // portal client, no app link
        persistOAuthClient(legacy);

        assertThat(portalAppRepo.findByOAuthClientId(linked.clientId())).map(PortalApp::id).contains(app.id());
        assertThat(portalAppRepo.findByOAuthClientId(legacy.clientId())).as("legacy client-wide portal ⇒ empty").isEmpty();
        assertThat(portalAppRepo.findByOAuthClientId("cli_" + RUN + "_doesnotexist")).isEmpty();
    }

    // ── userCounts ─────────────────────────────────────────────────────────

    @Test
    void userCountsCountsGrantRowsPerAppAndOmitsAppsWithNone() {
        String clientId = testClient("counts");
        PortalApp appA = create(clientId, "counts-a");
        PortalApp appB = create(clientId, "counts-b"); // never granted

        PortalIdentity u1 = PortalIdentity.create(clientId, "counts-1-" + RUN + "@example.com", null, PortalIdentitySource.INVITE)
                .grant(appA.id(), PortalAppGrantSource.INVITE);
        PortalIdentity u2 = PortalIdentity.create(clientId, "counts-2-" + RUN + "@example.com", null, PortalIdentitySource.INVITE)
                .grant(appA.id(), PortalAppGrantSource.INVITE);
        persistIdentity(u1);
        persistIdentity(u2);

        Map<String, Integer> counts = portalAppRepo.userCounts(List.of(appA.id(), appB.id()));
        assertThat(counts.getOrDefault(appA.id(), 0)).isEqualTo(2);
        assertThat(counts.getOrDefault(appB.id(), 0)).as("no grants ⇒ absent, caller defaults to 0").isEqualTo(0);
    }

    // ── FK cascades (spec §10) ─────────────────────────────────────────────

    @Test
    void deletingAnAppCascadesItsGrants() {
        String clientId = testClient("cascade-app");
        PortalApp app = create(clientId, "cascade-app");
        PortalIdentity identity = PortalIdentity.create(clientId, "cascade-app-" + RUN + "@example.com", null, PortalIdentitySource.INVITE)
                .grant(app.id(), PortalAppGrantSource.INVITE);
        persistIdentity(identity);
        assertThat(grantRowCount(identity.id())).isEqualTo(1);

        uow.inTransaction(tx -> {
            portalAppRepo.delete(app, tx.dbTx());
            return null;
        });

        assertThat(grantRowCount(identity.id())).as("the grant row is gone once its app is deleted").isEqualTo(0);
        assertThat(portalAppRepo.findById(app.id())).isEmpty();
    }

    @Test
    void deletingAnIdentityCascadesItsGrants() {
        String clientId = testClient("cascade-identity");
        PortalApp app = create(clientId, "cascade-identity");
        PortalIdentity identity = PortalIdentity.create(clientId, "cascade-identity-" + RUN + "@example.com", null, PortalIdentitySource.INVITE)
                .grant(app.id(), PortalAppGrantSource.INVITE);
        persistIdentity(identity);
        assertThat(grantRowCount(identity.id())).isEqualTo(1);

        uow.inTransaction(tx -> {
            identityRepo.delete(identity, tx.dbTx());
            return null;
        });

        assertThat(grantRowCount(identity.id())).as("the grant row is gone once its identity is deleted").isEqualTo(0);
    }
}
