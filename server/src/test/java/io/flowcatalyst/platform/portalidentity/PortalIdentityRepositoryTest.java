package io.flowcatalyst.platform.portalidentity;

import io.flowcatalyst.platform.client.Client;
import io.flowcatalyst.platform.client.ClientIdentifier;
import io.flowcatalyst.platform.client.ClientRepository;
import io.flowcatalyst.platform.portalapp.PortalApp;
import io.flowcatalyst.platform.portalapp.PortalAppCode;
import io.flowcatalyst.platform.portalapp.PortalAppRepository;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.shared.platformsink.PlatformSink;
import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import io.flowcatalyst.testpg.TestPg;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/// `PortalIdentityRepository` against the embedded Postgres (spec
/// `auth-identity.md` §3.3): the upsert's column-level guarantee (a
/// re-`Ensure` keeps `id` / `source` / `created_at` / `password_hash` no
/// matter what the in-memory aggregate carries — this is the SQL-level
/// protection, independent of `PortalIdentity.ensureActive`'s own care) and
/// cross-client isolation.
///
/// The fixture never truncates; every row is namespaced by a per-JVM suffix.
@SuppressWarnings("deprecation")
class PortalIdentityRepositoryTest {

    private static final DataSource DS = TestPg.dataSource();
    private static final DSLContext DB = DSL.using(DS, SQLDialect.POSTGRES);
    private static final PortalIdentityRepository repo = new PortalIdentityRepository(DS);
    private static final ClientRepository clientRepo = new ClientRepository(DS);
    private static final PortalAppRepository portalAppRepo = new PortalAppRepository(DS);
    private static final UnitOfWork uow = new UnitOfWork(DS, new PlatformSink(Json.MAPPER));

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toLowerCase(Locale.ROOT);

    private static String testClient(String tag) {
        Client c = Client.create("Portal Identity Test " + tag, ClientIdentifier.parse("piu-" + RUN + "-" + tag));
        uow.inTransaction(tx -> {
            clientRepo.persist(c, tx.dbTx());
            return null;
        });
        return c.id();
    }

    private static PortalApp testApp(String clientId, String tag) {
        PortalApp a = PortalApp.create(clientId, PortalAppCode.parse(tag + "-" + RUN), "App " + tag, null);
        uow.inTransaction(tx -> {
            portalAppRepo.persist(a, tx.dbTx());
            return null;
        });
        return a;
    }

    private static void persist(PortalIdentity p) {
        uow.inTransaction(tx -> {
            repo.persist(p, tx.dbTx());
            return null;
        });
    }

    private static int grantRowCount(String identityId) {
        return DB.fetch("SELECT 1 FROM portal_identity_apps WHERE identity_id = ?", identityId).size();
    }

    @Test
    void persistInsertsAndFindsByIdAndByClientAndEmailCaseInsensitively() {
        String clientId = testClient("find");
        PortalIdentity p = PortalIdentity.create(clientId, "Find.Me@Example.com", "Findable", PortalIdentitySource.INVITE);
        persist(p);

        assertThat(repo.findById(p.id())).contains(p);
        assertThat(repo.findByClientAndEmail(clientId, "FIND.ME@EXAMPLE.COM")).contains(p);
        assertThat(repo.findByClientAndEmail(clientId, "  find.me@example.com  ")).contains(p);
        assertThat(repo.findById("ptu_doesnotexist1")).isEmpty();
    }

    @Test
    void searchWithNoFiltersReturnsTheClientsIdentitiesNewestFirst() {
        String clientId = testClient("list");
        PortalIdentity first = PortalIdentity.create(clientId, "a-" + RUN + "@example.com", null, PortalIdentitySource.INVITE);
        persist(first);
        PortalIdentity second = PortalIdentity.create(clientId, "b-" + RUN + "@example.com", null, PortalIdentitySource.INVITE);
        persist(second);

        var page = repo.search(new PortalIdentityRepository.SearchFilter(clientId, null, null, 0, 100));
        assertThat(page.items()).extracting(PortalIdentity::id).containsExactly(second.id(), first.id());
        assertThat(page.total()).isEqualTo(2);
    }

    /// Mutant: the upsert's `ON CONFLICT … DO UPDATE SET` gains `source` /
    /// `password_hash` (or `id` / `created_at`) to its column list. This
    /// asserts the *stored row*, not the in-memory object the operation
    /// happened to build — a second `PortalIdentity` for the same
    /// (client, email) is persisted carrying a different id, source,
    /// password and created-at, so only the SQL-level column exclusion (not
    /// `PortalIdentity.ensureActive`'s own discipline) can save this test.
    @Test
    void reEnsureUpsertKeepsIdSourceCreatedAtAndPasswordHashRegardlessOfWhatTheNewObjectCarries() {
        String clientId = testClient("upsert");
        String email = "upsert-" + RUN + "@example.com";
        PortalIdentity original = PortalIdentity.create(clientId, email, "Original Name", PortalIdentitySource.INVITE);
        persist(original);
        uow.inTransaction(tx -> {
            repo.setPasswordHash(original.id(), "hash-original", tx.dbTx());
            return null;
        });
        assertThat(repo.findById(original.id()).orElseThrow().passwordHash()).isEqualTo("hash-original");

        // An "impostor" object for the SAME (clientId, email) but a different
        // id/source/passwordHash/createdAt — exactly what a buggy upsert
        // would let through.
        PortalIdentity impostor = new PortalIdentity(EntityType.PORTAL_USER.generate(), clientId, email, "New Name",
                "hash-impostor", PortalIdentityStatus.DISABLED, PortalIdentitySource.JIT, List.of(), null,
                null, null, Instant.now().plusSeconds(999), Instant.now());
        persist(impostor);

        PortalIdentity reloaded = repo.findByClientAndEmail(clientId, email).orElseThrow();
        assertThat(reloaded.id()).as("id kept from the original row").isEqualTo(original.id());
        assertThat(reloaded.source()).as("source kept from the original row").isEqualTo(PortalIdentitySource.INVITE);
        assertThat(reloaded.passwordHash()).as("password kept from the original row").isEqualTo("hash-original");
        assertThat(reloaded.createdAt()).as("created_at kept from the original row").isEqualTo(original.createdAt());
        // The columns the upsert DOES own take the new values.
        assertThat(reloaded.name()).isEqualTo("New Name");
        assertThat(reloaded.status()).isEqualTo(PortalIdentityStatus.DISABLED);
    }

    @Test
    void sameEmailUnderDifferentClientsAreIndependentRows() {
        String clientA = testClient("xa");
        String clientB = testClient("xb");
        String email = "shared-" + RUN + "@example.com";
        PortalIdentity a = PortalIdentity.create(clientA, email, "A's copy", PortalIdentitySource.INVITE);
        persist(a);
        PortalIdentity b = PortalIdentity.create(clientB, email, "B's copy", PortalIdentitySource.INVITE);
        persist(b);

        assertThat(repo.findByClientAndEmail(clientA, email)).map(PortalIdentity::name).contains("A's copy");
        assertThat(repo.findByClientAndEmail(clientB, email)).map(PortalIdentity::name).contains("B's copy");
        assertThat(a.id()).isNotEqualTo(b.id());
    }

    @Test
    void deleteRemovesTheRow() {
        String clientId = testClient("del");
        PortalIdentity p = PortalIdentity.create(clientId, "del-" + RUN + "@example.com", null, PortalIdentitySource.INVITE);
        persist(p);
        uow.inTransaction(tx -> {
            repo.delete(p, tx.dbTx());
            return null;
        });
        assertThat(repo.findById(p.id())).isEmpty();
    }

    @Test
    void touchLastLoginStampsTheColumn() {
        String clientId = testClient("touch");
        PortalIdentity p = PortalIdentity.create(clientId, "touch-" + RUN + "@example.com", null, PortalIdentitySource.INVITE);
        persist(p);
        assertThat(repo.findById(p.id()).orElseThrow().lastLoginAt()).isNull();

        repo.touchLastLogin(p.id());

        assertThat(repo.findById(p.id()).orElseThrow().lastLoginAt()).isNotNull();
    }

    // ── Grants (spec `portal-apps.md` §2.2) ───────────────────────────────────

    @Test
    void grantsRoundTripInGrantedAtOrder() throws InterruptedException {
        String clientId = testClient("grants");
        PortalApp appA = testApp(clientId, "grants-a");
        PortalApp appB = testApp(clientId, "grants-b");
        PortalIdentity withA = PortalIdentity.create(clientId, "grants-" + RUN + "@example.com", null, PortalIdentitySource.INVITE)
                .grant(appA.id(), PortalAppGrantSource.INVITE);
        Thread.sleep(5);
        PortalIdentity withBoth = withA.grant(appB.id(), PortalAppGrantSource.ADMIN);
        persist(withBoth);

        PortalIdentity reloaded = repo.findById(withBoth.id()).orElseThrow();
        assertThat(reloaded.apps()).extracting(PortalAppGrant::appId).containsExactly(appA.id(), appB.id());
        assertThat(reloaded.apps()).extracting(PortalAppGrant::source)
                .containsExactly(PortalAppGrantSource.INVITE, PortalAppGrantSource.ADMIN);
    }

    /// Mutant: `syncGrants` drops the delete of rows no longer in `apps()`.
    /// Asserts the raw table, not `PortalIdentity#apps()` — a mutant that
    /// only forgot to re-hydrate would still pass a check on the in-memory
    /// entity alone (CLAUDE.md: "assert the row is gone, not that delete
    /// was invoked").
    @Test
    void revokeThenPersistRemovesTheRowFromPortalIdentityApps() {
        String clientId = testClient("revoke");
        PortalApp app = testApp(clientId, "revoke");
        PortalIdentity granted = PortalIdentity.create(clientId, "revoke-" + RUN + "@example.com", null, PortalIdentitySource.INVITE)
                .grant(app.id(), PortalAppGrantSource.INVITE);
        persist(granted);
        assertThat(grantRowCount(granted.id())).isEqualTo(1);

        PortalIdentity revoked = repo.findById(granted.id()).orElseThrow().revoke(app.id());
        assertThat(revoked.apps()).isEmpty();
        persist(revoked);

        assertThat(grantRowCount(granted.id())).as("the row is gone from portal_identity_apps").isEqualTo(0);
    }

    /// Mutant: the grant sync keys on the in-memory aggregate's own `id()`
    /// instead of the upsert's `RETURNING id`. A second in-memory identity
    /// for the SAME (client, email) carries a freshly generated id that
    /// never becomes a `portal_identities` row (the upsert resolves to the
    /// original row) — syncing grants under that id would violate
    /// `portal_identity_apps`'s FK and throw, or (if the FK were somehow
    /// satisfied) leave the grant unreachable from the real row. Either way
    /// this test fails; on the correct implementation the grant lands under
    /// the original id and is visible there.
    @Test
    void aSecondIdentityObjectWithADifferentIdPersistsItsGrantsUnderTheStoredId() {
        String clientId = testClient("race");
        PortalApp app = testApp(clientId, "race");
        String email = "race-" + RUN + "@example.com";
        PortalIdentity original = PortalIdentity.create(clientId, email, null, PortalIdentitySource.INVITE);
        persist(original);

        PortalIdentity impostor = PortalIdentity.create(clientId, email, null, PortalIdentitySource.INVITE)
                .grant(app.id(), PortalAppGrantSource.INVITE);
        assertThat(impostor.id()).as("a racing Ensure builds its own fresh id before the upsert resolves")
                .isNotEqualTo(original.id());
        persist(impostor);

        PortalIdentity reloaded = repo.findByClientAndEmail(clientId, email).orElseThrow();
        assertThat(reloaded.id()).as("the winner's row, per the existing upsert guarantee").isEqualTo(original.id());
        assertThat(reloaded.apps()).extracting(PortalAppGrant::appId).containsExactly(app.id());
        assertThat(grantRowCount(impostor.id())).as("no grant row under the impostor's own, never-persisted id").isEqualTo(0);
    }

    // ── markInvited (spec `portal-apps.md` §2.2) ──────────────────────────────

    @Test
    void markInvitedRoundTripsAndSurvivesALaterPersist() {
        String clientId = testClient("invited");
        PortalIdentity p = PortalIdentity.create(clientId, "invited-" + RUN + "@example.com", null, PortalIdentitySource.INVITE);
        persist(p);
        Instant invitedAt = Instant.now().minusSeconds(10);
        Instant expiresAt = invitedAt.plus(Duration.ofHours(72));
        repo.markInvited(p.id(), invitedAt, expiresAt);

        PortalIdentity afterInvite = repo.findById(p.id()).orElseThrow();
        assertThat(afterInvite.invitedAt()).isCloseTo(invitedAt, within(1, ChronoUnit.SECONDS));
        assertThat(afterInvite.inviteExpiresAt()).isCloseTo(expiresAt, within(1, ChronoUnit.SECONDS));

        // Deliberately absent from the upsert's SET list (spec §2.2) — a
        // later Ensure-style persist must not clobber it.
        persist(afterInvite.ensureActive("New Name"));
        PortalIdentity afterPersist = repo.findById(p.id()).orElseThrow();
        assertThat(afterPersist.invitedAt()).as("invited_at survives a later persist").isCloseTo(invitedAt, within(1, ChronoUnit.SECONDS));
        assertThat(afterPersist.inviteExpiresAt()).as("invite_expires_at survives a later persist").isCloseTo(expiresAt, within(1, ChronoUnit.SECONDS));
    }

    // ── search (spec §4.2, §9.3, Part A J10) ──────────────────────────────────

    @Test
    void searchMatchesEmailOrNamePrefixCaseInsensitivelyAndPagesWithTotal() {
        String clientId = testClient("search");
        PortalIdentity u1 = PortalIdentity.create(clientId, "pat.jones-" + RUN + "@example.com", "Pat Jones", PortalIdentitySource.INVITE);
        PortalIdentity u2 = PortalIdentity.create(clientId, "jsmith-" + RUN + "@example.com", "Jonas Smith", PortalIdentitySource.INVITE);
        PortalIdentity u3 = PortalIdentity.create(clientId, "zzz-" + RUN + "@example.com", null, PortalIdentitySource.INVITE);
        persist(u1);
        persist(u2);
        persist(u3);

        assertThat(search(clientId, "PAT", null, 0, 10).items()).as("email prefix, case-insensitive")
                .extracting(PortalIdentity::id).containsExactly(u1.id());
        assertThat(search(clientId, "jonas", null, 0, 10).items()).as("name prefix")
                .extracting(PortalIdentity::id).containsExactly(u2.id());
        // Mutant: the pattern is built as `%q%` instead of `q%`. "jones" is a
        // SUBSTRING of "pat.jones@…" and of "Pat Jones" but not a PREFIX of
        // either — a substring search would wrongly find u1 here.
        var jones = search(clientId, "jones", null, 0, 10);
        assertThat(jones.items()).as("prefix-only: a substring match must not count").isEmpty();
        assertThat(jones.total()).isZero();

        var page0 = search(clientId, null, null, 0, 2);
        assertThat(page0.items()).hasSize(2);
        assertThat(page0.total()).isEqualTo(3);
        var page1 = search(clientId, null, null, 1, 2);
        assertThat(page1.items()).hasSize(1);
        assertThat(page1.total()).isEqualTo(3);
    }

    /// Mutant: `ESCAPE` handling is dropped, so `_` and `%` in `q` act as SQL
    /// LIKE wildcards instead of literal characters. `under_score…` and
    /// `underXscore…` differ only in that one character — searching `under_`
    /// must find only the literal-underscore row; searching `u%` (a literal
    /// percent, escaped) must find neither, since no email starts with the
    /// two literal characters `u%`.
    @Test
    void searchEscapesLikeWildcardsInQ() {
        String clientId = testClient("escape");
        PortalIdentity underscore = PortalIdentity.create(clientId, "under_score-" + RUN + "@example.com", null, PortalIdentitySource.INVITE);
        PortalIdentity anyChar = PortalIdentity.create(clientId, "underXscore-" + RUN + "@example.com", null, PortalIdentitySource.INVITE);
        persist(underscore);
        persist(anyChar);

        var underscoreResult = search(clientId, "under_", null, 0, 10);
        assertThat(underscoreResult.items()).as("literal '_' must not match an arbitrary character")
                .extracting(PortalIdentity::id).containsExactly(underscore.id());

        var percentResult = search(clientId, "u%", null, 0, 10);
        assertThat(percentResult.items()).as("literal '%' must not act as a wildcard").isEmpty();
    }

    @Test
    void searchFiltersByGrantedApp() {
        String clientId = testClient("search-app");
        PortalApp app = testApp(clientId, "search-app");
        PortalIdentity granted = PortalIdentity.create(clientId, "granted-" + RUN + "@example.com", null, PortalIdentitySource.INVITE)
                .grant(app.id(), PortalAppGrantSource.INVITE);
        PortalIdentity ungranted = PortalIdentity.create(clientId, "ungranted-" + RUN + "@example.com", null, PortalIdentitySource.INVITE);
        persist(granted);
        persist(ungranted);

        var page = search(clientId, null, app.id(), 0, 10);
        assertThat(page.items()).extracting(PortalIdentity::id).containsExactly(granted.id());
        assertThat(page.total()).isEqualTo(1);
    }

    @Test
    void searchWithAnUnknownAppFilterFindsNothing() {
        String clientId = testClient("search-app-unknown");
        persist(PortalIdentity.create(clientId, "unknown-app-" + RUN + "@example.com", null, PortalIdentitySource.INVITE));
        assertThat(search(clientId, null, "pta_doesnotexist1", 0, 10).items()).isEmpty();
    }

    private static PortalIdentityRepository.SearchPage search(String clientId, String q, String portalAppId, int page, int size) {
        return repo.search(new PortalIdentityRepository.SearchFilter(clientId, q, portalAppId, page, size));
    }
}
