package io.flowcatalyst.platform.oauthclient;

import io.flowcatalyst.platform.application.Application;
import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.platform.application.ApplicationType;
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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static io.flowcatalyst.db.generated.Tables.OAUTH_CLIENTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// `OAuthClientRepository` against embedded Postgres (spec `auth-core.md`
/// §3.6): the full round trip of every column and all five junctions, the
/// null-keeps / empty-clears update semantics, the `applications[{id,name}]`
/// resolution, and the X-06 corrupt-row read. Writes go straight through
/// `repo.persist`/`repo.delete` inside `uow.inTransaction` (no event/audit
/// needed to test the repository's own mechanics) — `OAuthClientOperationsTest`
/// covers the envelope.
class OAuthClientRepositoryTest {

    private static final DataSource DS = TestPg.dataSource();
    private static final DSLContext DB = DSL.using(DS, SQLDialect.POSTGRES);
    private static final UnitOfWork uow = new UnitOfWork(DS, new PlatformSink(Json.MAPPER));
    private static final ApplicationRepository applicationRepo = new ApplicationRepository(DS);
    private static final OAuthClientRepository repo = new OAuthClientRepository(DS, applicationRepo);

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);

    private static void persist(OAuthClient c) {
        uow.inTransaction(tx -> {
            repo.persist(c, tx.dbTx());
            return null;
        });
    }

    private static void delete(OAuthClient c) {
        uow.inTransaction(tx -> {
            repo.delete(c, tx.dbTx());
            return null;
        });
    }

    // ── Round trip ─────────────────────────────────────────────────────────

    @Test
    void persistRoundTripsEveryColumnAndAllFiveJunctions() {
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        OAuthClient c = OAuthClient.create("cli_" + RUN + "_full", "Full Client", ClientType.CONFIDENTIAL)
                .withSecretRef("encrypted:current")
                .withRedirectUris(List.of("https://a.example/cb", "https://b.example/cb"))
                .withPostLogoutRedirectUris(List.of("https://a.example/logout"))
                .withGrantTypes(List.of("authorization_code", "refresh_token"))
                .withDefaultScopes(List.of("read", "write"))
                .withAllowedOrigins(List.of("https://a.example"))
                .withApplicationIds(List.of("app_" + RUN + "_1", "app_" + RUN + "_2"))
                .withPkceRequired(false)
                .withPortalAndApiAccess(null, true);
        OAuthClient rotated = c.rotateSecret("encrypted:previous-overlap", Duration.ofHours(6), t0).client();

        persist(rotated);

        OAuthClient stored = repo.findById(rotated.id()).orElseThrow();
        assertThat(stored.clientId()).isEqualTo(rotated.clientId());
        assertThat(stored.clientName()).isEqualTo("Full Client");
        assertThat(stored.clientType()).isEqualTo(ClientType.CONFIDENTIAL);
        assertThat(stored.secretRef()).isEqualTo("encrypted:previous-overlap");
        assertThat(stored.previousSecretRef()).isEqualTo("encrypted:current");
        assertThat(stored.previousSecretExpiresAt()).isEqualTo(t0.plus(Duration.ofHours(6)));
        assertThat(stored.previousSecretLastUsedAt()).isNull();
        assertThat(stored.redirectUris()).containsExactlyInAnyOrder("https://a.example/cb", "https://b.example/cb");
        assertThat(stored.postLogoutRedirectUris()).containsExactly("https://a.example/logout");
        assertThat(stored.grantTypes()).containsExactlyInAnyOrder("authorization_code", "refresh_token");
        assertThat(stored.defaultScopes()).containsExactlyInAnyOrder("read", "write");
        assertThat(stored.allowedOrigins()).containsExactly("https://a.example");
        assertThat(stored.applicationIds()).containsExactlyInAnyOrder("app_" + RUN + "_1", "app_" + RUN + "_2");
        assertThat(stored.pkceRequired()).isFalse();
        assertThat(stored.active()).isTrue();
        assertThat(stored.principalId()).isNull();
        assertThat(stored.portalClientId()).isNull();
        assertThat(stored.apiAccess()).isTrue();
        assertThat(stored.createdAt()).isEqualTo(rotated.createdAt());

        // findByClientId resolves the same row.
        assertThat(repo.findByClientId(rotated.clientId())).contains(stored);

        // Re-persist with fewer redirect URIs: clear-and-reinsert, not append.
        OAuthClient shrunk = stored.withRedirectUris(List.of("https://only.example"));
        persist(shrunk);
        assertThat(repo.findById(rotated.id()).orElseThrow().redirectUris()).containsExactly("https://only.example");

        // Delete removes the row and every junction row.
        delete(shrunk);
        assertThat(repo.findById(rotated.id())).isEmpty();
        assertThat(DB.fetchCount(io.flowcatalyst.db.generated.Tables.OAUTH_CLIENT_REDIRECT_URIS,
                io.flowcatalyst.db.generated.Tables.OAUTH_CLIENT_REDIRECT_URIS.OAUTH_CLIENT_ID.eq(rotated.id()))).isZero();
    }

    @Test
    void findAllOrdersByClientNameAndHydratesJunctions() {
        String suffix = "_findall_" + RUN;
        OAuthClient a = OAuthClient.create("cli" + suffix + "a", "Zebra" + suffix, ClientType.PUBLIC)
                .withRedirectUris(List.of("https://z.example"));
        OAuthClient b = OAuthClient.create("cli" + suffix + "b", "Alpha" + suffix, ClientType.PUBLIC)
                .withGrantTypes(List.of("client_credentials"));
        persist(a);
        persist(b);

        List<OAuthClient> all = repo.findAll();
        assertThat(all).extracting(OAuthClient::id).contains(a.id(), b.id());
        int ia = indexOf(all, a.id());
        int ib = indexOf(all, b.id());
        assertThat(ib).as("ordered by clientName").isLessThan(ia);
        assertThat(all.get(ia).redirectUris()).containsExactly("https://z.example");
        assertThat(all.get(ib).grantTypes()).containsExactly("client_credentials");
    }

    private static int indexOf(List<OAuthClient> list, String id) {
        for (int i = 0; i < list.size(); i++) if (list.get(i).id().equals(id)) return i;
        throw new AssertionError("not found: " + id);
    }

    // ── null keeps / empty clears (entity-level, persisted and re-read) ─────

    @Test
    void nullChangeKeepsTheCurrentJunctionAndEmptyListClearsIt() {
        OAuthClient created = OAuthClient.create("cli_" + RUN + "_upd", "Update Me", ClientType.PUBLIC)
                .withRedirectUris(List.of("https://keep.example"))
                .withGrantTypes(List.of("authorization_code"));
        persist(created);

        // null redirectUris in Changes ⇒ untouched.
        OAuthClient afterNullChange = created.update(new OAuthClient.Changes(
                null, null, null, null, null, null, null, null, null, null));
        persist(afterNullChange);
        assertThat(repo.findById(created.id()).orElseThrow().redirectUris())
                .as("null in Changes leaves the stored junction alone").containsExactly("https://keep.example");

        // empty grantTypes in Changes ⇒ explicit clear.
        OAuthClient afterEmptyChange = afterNullChange.update(new OAuthClient.Changes(
                null, null, null, List.of(), null, null, null, null, null, null));
        persist(afterEmptyChange);
        OAuthClient reloaded = repo.findById(created.id()).orElseThrow();
        assertThat(reloaded.grantTypes()).as("empty list in Changes clears the junction").isEmpty();
        assertThat(reloaded.redirectUris()).as("still untouched by the second update")
                .containsExactly("https://keep.example");
    }

    // ── applications[{id,name}] resolution ───────────────────────────────────

    @Test
    void applicationRefsResolvesLiveNamesAndFallsBackToIdForADeletedApplication() {
        Application app = Application.create(ApplicationType.APPLICATION, "app-refs-" + RUN, "Live App " + RUN);
        uow.inTransaction(tx -> {
            applicationRepo.persist(app, tx.dbTx());
            return null;
        });
        String missingId = "app_" + RUN + "_gone";

        List<OAuthClientRepository.ApplicationRef> refs = repo.applicationRefs(List.of(app.id(), missingId));
        assertThat(refs).containsExactly(
                new OAuthClientRepository.ApplicationRef(app.id(), "Live App " + RUN),
                new OAuthClientRepository.ApplicationRef(missingId, missingId));
    }

    // ── X-06: corrupt client_type fails loudly ──────────────────────────────

    /// `chk_oauth_clients_client_type` blocks a fresh write of an unrecognised
    /// type, so the constraint is dropped for the seed insert AND the
    /// assertion, and the row is deleted again before restoring — otherwise
    /// restoring it would itself fail by re-validating against the row just
    /// inserted (`io.flowcatalyst.testpg.TestPg`, ported from Go's
    /// `testpg.WithConstraintDropped`).
    @Test
    void findByIdRejectsAnUnrecognisedClientTypeInsteadOfDefaultingToPublic() {
        TestPg.withConstraintDropped(DS, "oauth_clients", "chk_oauth_clients_client_type", () -> {
            String id = EntityType.OAUTH_CLIENT.generate();
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            DB.insertInto(OAUTH_CLIENTS)
                    .set(OAUTH_CLIENTS.ID, id)
                    .set(OAUTH_CLIENTS.CLIENT_ID, "cli_" + RUN + "_corrupt")
                    .set(OAUTH_CLIENTS.CLIENT_NAME, "Corrupt")
                    .set(OAUTH_CLIENTS.CLIENT_TYPE, "BOGUS")
                    .set(OAUTH_CLIENTS.PKCE_REQUIRED, true)
                    .set(OAUTH_CLIENTS.ACTIVE, true)
                    .set(OAUTH_CLIENTS.API_ACCESS, false)
                    .set(OAUTH_CLIENTS.CREATED_AT, now)
                    .set(OAUTH_CLIENTS.UPDATED_AT, now)
                    .execute();
            try {
                assertThatThrownBy(() -> repo.findById(id))
                        .isInstanceOf(CorruptOAuthClientException.class)
                        .satisfies(e -> assertThat(((CorruptOAuthClientException) e).rowId()).isEqualTo(id));
                // findAll must fail on the corrupt row too (X-06: a list read fails whole, not partial).
                assertThatThrownBy(repo::findAll).isInstanceOf(CorruptOAuthClientException.class);
            } finally {
                DB.deleteFrom(OAUTH_CLIENTS).where(OAUTH_CLIENTS.ID.eq(id)).execute();
            }
        });
    }
}
