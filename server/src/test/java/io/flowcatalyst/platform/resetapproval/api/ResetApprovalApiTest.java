package io.flowcatalyst.platform.resetapproval.api;

import io.flowcatalyst.platform.mail.Mail;
import io.flowcatalyst.platform.mail.MailService;
import io.flowcatalyst.platform.passwordreset.ResetLinks;
import io.flowcatalyst.platform.passwordreset.ResetTokenRepository;
import io.flowcatalyst.platform.principal.EmailAddress;
import io.flowcatalyst.platform.principal.Principal;
import io.flowcatalyst.platform.principal.PrincipalRepository;
import io.flowcatalyst.platform.principal.UserScope;
import io.flowcatalyst.platform.publicapi.EmailTheme;
import io.flowcatalyst.platform.resetapproval.ResetApprovalRepository;
import io.flowcatalyst.platform.resetapproval.ResetApprovalRequest;
import io.flowcatalyst.platform.resetapproval.ResetApprovalStatus;
import io.flowcatalyst.platform.shared.TestHttp;
import io.flowcatalyst.platform.shared.auth.Authenticator;
import io.flowcatalyst.platform.shared.auth.ClaimsResolver;
import io.flowcatalyst.platform.shared.auth.JwtVerifier;
import io.flowcatalyst.platform.shared.auth.SigningKeys;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.shared.platformsink.PlatformSink;
import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.sdk.usecase.jdbc.DbTx;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import io.flowcatalyst.testpg.TestPg;
import tools.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/// The three `/api/reset-approvals*` routes end to end through Javalin
/// (spec `auth-identity.md` §8.6): anchor-vs-client-admin visibility,
/// approve mailing a reset link and rejecting a repeat decision, deny
/// persisting the reviewer's note, and the permission gate.
class ResetApprovalApiTest {

    private static final ResetApprovalRepository repo = new ResetApprovalRepository(TestPg.dataSource());
    private static final PrincipalRepository principals = new PrincipalRepository(TestPg.dataSource());
    private static final UnitOfWork uow = new UnitOfWork(TestPg.dataSource(), new PlatformSink(Json.MAPPER));
    private static final List<Mail> SENT = new CopyOnWriteArrayList<>();
    private static final MailService MAIL = SENT::add;
    private static final ResetLinks resetLinks = new ResetLinks(new ResetTokenRepository(TestPg.dataSource()), MAIL,
            () -> EmailTheme.defaults("Test"), "https://app.example.test", Clock.systemUTC());

    private static final String[] ANCHOR = {
            Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
            Authenticator.TEST_SCOPE, "ANCHOR"};

    private static TestHttp http;

    @BeforeAll
    static void start() {
        var keys = SigningKeys.generateEphemeral();
        var verifier = new JwtVerifier(new JwtVerifier.Config("http://localhost:8080", new JwtVerifier.RsaKeys(keys.publicKey())));
        var auth = new Authenticator(verifier, ClaimsResolver.none(), Authenticator.Config.of(true));
        var state = new ResetApprovalApi.State(repo, principals, resetLinks, uow);
        http = TestHttp.routes(routes -> {
            HttpError.install(routes);
            routes.before("/api/*", auth);
            ResetApprovalApi.register(routes, state);
        });
    }

    @AfterAll
    static void stop() {
        http.close();
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private static JsonNode json(HttpResponse<String> r) {
        try {
            return Json.MAPPER.readTree(r.body());
        } catch (Exception e) {
            throw new IllegalStateException("not JSON: " + r.body(), e);
        }
    }

    private static String[] clientAdmin(String clientId) {
        return new String[]{
                Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
                Authenticator.TEST_SCOPE, "CLIENT",
                Authenticator.TEST_CLIENTS, clientId,
                Authenticator.TEST_PERMISSIONS,
                "platform:iam:user:create,platform:iam:user:update,platform:iam:user:delete"};
    }

    private static String[] nonAdmin(String clientId) {
        return new String[]{
                Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
                Authenticator.TEST_SCOPE, "CLIENT",
                Authenticator.TEST_CLIENTS, clientId};
    }

    /// A real `iam_principals` row (FK target) — the reset-approval request
    /// under test is queued for it. Returns the persisted `Principal` so
    /// tests can read its e-mail back.
    private static Principal seedPrincipal(String clientId) {
        String email = ("rar-apitest-" + UUID.randomUUID() + "@example.test").toLowerCase(Locale.ROOT);
        var p = Principal.newUser(EmailAddress.parse(email), UserScope.CLIENT).withClientId(clientId);
        try (Connection c = TestPg.dataSource().getConnection()) {
            c.setAutoCommit(true);
            principals.persist(p, DbTx.wrapForBootstrap(c));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return p;
    }

    private static ResetApprovalRequest seedPending(String principalId, String clientId) {
        var r = ResetApprovalRequest.create(principalId, clientId);
        try (Connection c = TestPg.dataSource().getConnection()) {
            c.setAutoCommit(true);
            repo.persist(r, DbTx.wrapForBootstrap(c));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return r;
    }

    // ── GET /api/reset-approvals ─────────────────────────────────────────

    @Test
    void anchorSeesEveryPendingRequestAcrossClients() {
        String clientA = EntityType.CLIENT.generate();
        String clientB = EntityType.CLIENT.generate();
        var reqA = seedPending(seedPrincipal(clientA).id(), clientA);
        var reqB = seedPending(seedPrincipal(clientB).id(), clientB);

        var r = http.get("/api/reset-approvals", ANCHOR);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        List<String> ids = json(r).get("requests").findValues("id").stream().map(JsonNode::asString).toList();
        assertThat(ids).contains(reqA.id(), reqB.id());
    }

    @Test
    void aClientAdminSeesOnlyItsOwnClientsPendingRequests() {
        String own = EntityType.CLIENT.generate();
        String other = EntityType.CLIENT.generate();
        var mine = seedPending(seedPrincipal(own).id(), own);
        var notMine = seedPending(seedPrincipal(other).id(), other);

        var r = http.get("/api/reset-approvals", clientAdmin(own));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        List<String> ids = json(r).get("requests").findValues("id").stream().map(JsonNode::asString).toList();
        assertThat(ids).contains(mine.id()).doesNotContain(notMine.id());
    }

    @Test
    void listResolvesThePrincipalsEmailAndName() {
        String clientId = EntityType.CLIENT.generate();
        var principal = seedPrincipal(clientId);
        var req = seedPending(principal.id(), clientId);

        var r = http.get("/api/reset-approvals", clientAdmin(clientId));
        var row = findRow(json(r).get("requests"), req.id());
        assertThat(row.get("principalId").asString()).isEqualTo(principal.id());
        assertThat(row.get("email").asString()).isEqualTo(principal.email());
        assertThat(row.get("clientId").asString()).isEqualTo(clientId);
    }

    @Test
    void listWithoutTheUserWritePermissionIs403() {
        String clientId = EntityType.CLIENT.generate();
        var r = http.get("/api/reset-approvals", nonAdmin(clientId));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(403);
        assertThat(json(r).get("error").asString()).isEqualTo("PERMISSION_REQUIRED");
    }

    // ── approve ────────────────────────────────────────────────────────────

    @Test
    void approveMailsAResetLinkThenASecondApproveIs400AlreadyDecided() {
        String clientId = EntityType.CLIENT.generate();
        var principal = seedPrincipal(clientId);
        var req = seedPending(principal.id(), clientId);
        int before = SENT.size();

        var approve = http.post("/api/reset-approvals/" + req.id() + "/approve", null, clientAdmin(clientId));
        assertThat(approve.statusCode()).as(approve.body()).isEqualTo(200);
        assertThat(json(approve).get("message").asString()).isEqualTo("Reset approved — the user has been emailed a link");

        assertThat(SENT).hasSizeGreaterThan(before);
        Mail mail = SENT.getLast();
        assertThat(mail.to()).isEqualTo(principal.email());
        assertThat(mail.subject()).isEqualTo("Reset your password");

        var second = http.post("/api/reset-approvals/" + req.id() + "/approve", null, clientAdmin(clientId));
        assertThat(second.statusCode()).as(second.body()).isEqualTo(400);
        assertThat(json(second).get("error").asString()).isEqualTo("ALREADY_DECIDED");
    }

    @Test
    void approveOnAnUnknownIdIs404() {
        var r = http.post("/api/reset-approvals/rar_doesnotexist1/approve", null, ANCHOR);
        assertThat(r.statusCode()).isEqualTo(404);
        assertThat(json(r).get("error").asString()).isEqualTo("ResetApprovalRequest_NOT_FOUND");
    }

    // ── deny ───────────────────────────────────────────────────────────────

    @Test
    void denyPersistsTheReviewersNote() {
        String clientId = EntityType.CLIENT.generate();
        var principal = seedPrincipal(clientId);
        var req = seedPending(principal.id(), clientId);

        var deny = http.post("/api/reset-approvals/" + req.id() + "/deny",
                "{\"note\":\"looks like the user just forgot their factor\"}", clientAdmin(clientId));
        assertThat(deny.statusCode()).as(deny.body()).isEqualTo(200);
        assertThat(json(deny).get("message").asString()).isEqualTo("Reset request denied");

        var reloaded = repo.findById(req.id()).orElseThrow();
        assertThat(reloaded.status()).isEqualTo(ResetApprovalStatus.DENIED);
        assertThat(reloaded.note()).isEqualTo("looks like the user just forgot their factor");
    }

    // ── Gate: approve/deny are user-admin-only ──────────────────────────────

    @Test
    void approveAndDenyRejectANonAdminWith403() {
        String clientId = EntityType.CLIENT.generate();
        var principal = seedPrincipal(clientId);
        var req = seedPending(principal.id(), clientId);

        var approve = http.post("/api/reset-approvals/" + req.id() + "/approve", null, nonAdmin(clientId));
        assertThat(approve.statusCode()).as(approve.body()).isEqualTo(403);
        assertThat(json(approve).get("error").asString()).isEqualTo("PERMISSION_REQUIRED");

        var deny = http.post("/api/reset-approvals/" + req.id() + "/deny", null, nonAdmin(clientId));
        assertThat(deny.statusCode()).as(deny.body()).isEqualTo(403);

        // Nothing took effect.
        assertThat(repo.findById(req.id()).orElseThrow().status()).isEqualTo(ResetApprovalStatus.PENDING);
    }

    @Test
    void approveRejectsAClientAdminOfADifferentClientWith403() {
        String clientId = EntityType.CLIENT.generate();
        String otherClient = EntityType.CLIENT.generate();
        var principal = seedPrincipal(clientId);
        var req = seedPending(principal.id(), clientId);

        var approve = http.post("/api/reset-approvals/" + req.id() + "/approve", null, clientAdmin(otherClient));
        assertThat(approve.statusCode()).as(approve.body()).isEqualTo(403);
        assertThat(json(approve).get("error").asString()).isEqualTo("SCOPE_FORBIDDEN");
    }

    private static JsonNode findRow(JsonNode requests, String id) {
        for (JsonNode row : requests) {
            if (row.get("id").asString().equals(id)) {
                return row;
            }
        }
        throw new AssertionError("no row with id " + id + " in " + requests);
    }
}
