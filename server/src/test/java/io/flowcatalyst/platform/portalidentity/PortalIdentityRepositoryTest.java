package io.flowcatalyst.platform.portalidentity;

import io.flowcatalyst.platform.client.Client;
import io.flowcatalyst.platform.client.ClientIdentifier;
import io.flowcatalyst.platform.client.ClientRepository;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.shared.platformsink.PlatformSink;
import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import io.flowcatalyst.testpg.TestPg;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

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
    private static final PortalIdentityRepository repo = new PortalIdentityRepository(DS);
    private static final ClientRepository clientRepo = new ClientRepository(DS);
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

    private static void persist(PortalIdentity p) {
        uow.inTransaction(tx -> {
            repo.persist(p, tx.dbTx());
            return null;
        });
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
    void findByClientReturnsNewestFirst() {
        String clientId = testClient("list");
        PortalIdentity first = PortalIdentity.create(clientId, "a-" + RUN + "@example.com", null, PortalIdentitySource.INVITE);
        persist(first);
        PortalIdentity second = PortalIdentity.create(clientId, "b-" + RUN + "@example.com", null, PortalIdentitySource.INVITE);
        persist(second);

        List<PortalIdentity> listed = repo.findByClient(clientId);
        assertThat(listed).extracting(PortalIdentity::id).containsExactly(second.id(), first.id());
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
                "hash-impostor", PortalIdentityStatus.DISABLED, PortalIdentitySource.JIT, null,
                Instant.now().plusSeconds(999), Instant.now());
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
}
