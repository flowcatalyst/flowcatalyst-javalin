package io.flowcatalyst.platform.ingest.api;

import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.platform.audit.AuditLogRepository;
import io.flowcatalyst.platform.client.Client;
import io.flowcatalyst.platform.client.ClientRepository;
import io.flowcatalyst.platform.connection.ConnectionRepository;
import io.flowcatalyst.platform.dispatchjob.DispatchJobLifecycle;
import io.flowcatalyst.platform.dispatchjob.processing.DeliverySigningGuard;
import io.flowcatalyst.platform.event.EventRepository;
import io.flowcatalyst.platform.serviceaccount.SigningAccounts;
import io.flowcatalyst.platform.shared.TestHttp;
import io.flowcatalyst.platform.shared.auth.Authenticator;
import io.flowcatalyst.platform.shared.auth.ClaimsResolver;
import io.flowcatalyst.platform.shared.auth.JwtVerifier;
import io.flowcatalyst.platform.shared.auth.SigningKeys;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.platform.subscription.SubscriptionRepository;
import io.flowcatalyst.testpg.TestPg;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.net.http.HttpResponse;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static io.flowcatalyst.db.generated.Tables.MSG_DISPATCH_JOBS;
import static io.flowcatalyst.db.generated.Tables.MSG_EVENTS;
import static io.flowcatalyst.db.generated.Tables.TNT_CLIENTS;
import static org.assertj.core.api.Assertions.assertThat;

/// The ingest client rules of owner rulings 2026-10-12 (Go F14,
/// `internal/platform/shared/sdk/client_code.go`; `docs/spec/sdk-ingest.md`
/// §3.4): a `clientId` or `clientCode` that is absent, `null`, empty or blank
/// counts as not given; an events-batch `clientCode` the caller cannot use is
/// refused naming the code, never the client id it resolved to; and the
/// dispatch-job routes resolve a `clientCode`. The two clients here have an
/// identifier that differs from their id, so a message naming the resolved id
/// is told apart from one naming the code.
class IngestClientRulesTest {

    private static final javax.sql.DataSource DS = TestPg.dataSource();
    private static final DSLContext DB = DSL.using(DS, SQLDialect.POSTGRES);
    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toLowerCase(Locale.ROOT);

    private static final String CLIENT_A = "cli_" + RUN + "0000a";
    private static final String CODE_A = "alpha-" + RUN;
    private static final String CLIENT_B = "cli_" + RUN + "0000b";
    private static final String CODE_B = "beta-" + RUN;
    private static final String UNKNOWN_CODE = "nosuch-" + RUN;

    private static final String[] ANCHOR = {
            Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
            Authenticator.TEST_SCOPE, "ANCHOR",
            Authenticator.TEST_PERMISSIONS, "platform:*:*:*"};
    /// Confined to client A alone.
    private static final String[] CLIENT_CALLER = {
            Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
            Authenticator.TEST_SCOPE, "CLIENT",
            Authenticator.TEST_CLIENTS, CLIENT_A,
            Authenticator.TEST_PERMISSIONS, "platform:messaging:batch:events-write,platform:messaging:batch:dispatch-jobs-write"};

    /// Reaches both clients, so it has no single-client default.
    private static final String[] PARTNER = {
            Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
            Authenticator.TEST_SCOPE, "PARTNER",
            Authenticator.TEST_CLIENTS, CLIENT_A + "," + CLIENT_B,
            Authenticator.TEST_PERMISSIONS, "platform:messaging:batch:events-write,platform:messaging:batch:dispatch-jobs-write"};

    private static final AtomicInteger clientLookups = new AtomicInteger();
    private static TestHttp http;

    @BeforeAll
    static void start() {
        DB.insertInto(TNT_CLIENTS).set(TNT_CLIENTS.ID, CLIENT_A).set(TNT_CLIENTS.NAME, "Alpha").set(TNT_CLIENTS.IDENTIFIER, CODE_A).execute();
        DB.insertInto(TNT_CLIENTS).set(TNT_CLIENTS.ID, CLIENT_B).set(TNT_CLIENTS.NAME, "Beta").set(TNT_CLIENTS.IDENTIFIER, CODE_B).execute();

        var clients = new ClientRepository(DS);
        var subscriptions = new SubscriptionRepository(DS);
        var state = new IngestApi.State(new EventRepository(DS), new DispatchJobLifecycle(DS), new AuditLogRepository(DS),
                code -> {
                    clientLookups.incrementAndGet();
                    Optional<Client> found = clients.findByIdentifier(code);
                    return found;
                },
                new ApplicationRepository(DS)::findByCode, subscriptions::findById,
                new DeliverySigningGuard(subscriptions::findById, new ConnectionRepository(DS)::findById, SigningAccounts.reach(DS)));
        var keys = SigningKeys.generateEphemeral();
        var verifier = new JwtVerifier(new JwtVerifier.Config("http://localhost:8080", new JwtVerifier.RsaKeys(keys.publicKey())));
        var auth = new Authenticator(verifier, ClaimsResolver.none(), Authenticator.Config.of(true));
        http = TestHttp.routes(routes -> {
            HttpError.install(routes);
            routes.before("/api/*", auth);
            IngestApi.register(routes, state);
        });
    }

    @AfterAll
    static void stop() {
        http.close();
    }

    private static JsonNode json(HttpResponse<String> r) {
        return Json.MAPPER.readTree(r.body());
    }

    /// A type naming no application, so no signing identity is involved.
    private static String uniqueType(String tag) {
        return "it." + RUN + "." + tag + "." + UUID.randomUUID().toString().substring(0, 8);
    }

    private static int countEvents(String type) {
        return DB.fetchCount(MSG_EVENTS, MSG_EVENTS.TYPE.eq(type));
    }

    private static String eventClient(String type) {
        return DB.selectFrom(MSG_EVENTS).where(MSG_EVENTS.TYPE.eq(type)).fetchOne().getClientId();
    }

    private static int countJobs(String code) {
        return DB.fetchCount(MSG_DISPATCH_JOBS, MSG_DISPATCH_JOBS.CODE.eq(code));
    }

    private static String jobClient(String code) {
        return DB.selectFrom(MSG_DISPATCH_JOBS).where(MSG_DISPATCH_JOBS.CODE.eq(code)).fetchOne().getClientId();
    }

    private static void assertRefused(HttpResponse<String> r, int status, String error, String message) {
        assertThat(r.statusCode()).as(r.body()).isEqualTo(status);
        assertThat(json(r).get("error").asString()).as(r.body()).isEqualTo(error);
        assertThat(json(r).get("message").asString()).as(r.body()).isEqualTo(message);
        assertThat(r.body()).as("never the other tenant's client id").doesNotContain(CLIENT_B);
    }

    // ── Events batch ─────────────────────────────────────────────────────

    /// Before: the code resolved to client B and the refusal named B's id,
    /// confirming the code exists and leaking the other tenant's id.
    @Test
    void anotherClientsCodeIsRefusedNamingTheCodeLikeAnUnknownOne() {
        String valid = uniqueType("evvalid");
        String other = uniqueType("evother");
        var r = http.post("/api/events/batch", """
                {"items":[{"type":"%s","source":"s","data":{}},{"type":"%s","source":"s","data":{},"clientCode":"%s"}]}
                """.formatted(valid, other, CODE_B), CLIENT_CALLER);
        assertRefused(r, 403, "FORBIDDEN", "No access to client: " + CODE_B);
        assertThat(countEvents(valid)).as("the whole batch is refused").isZero();
        assertThat(countEvents(other)).isZero();

        String unknown = uniqueType("evunknown");
        var u = http.post("/api/events/batch", """
                {"items":[{"type":"%s","source":"s","data":{},"clientCode":"%s"}]}
                """.formatted(unknown, UNKNOWN_CODE), CLIENT_CALLER);
        assertRefused(u, 403, "FORBIDDEN", "No access to client: " + UNKNOWN_CODE);
        assertThat(countEvents(unknown)).isZero();
    }

    @Test
    void theCallersOwnCodeResolvesAndAnExplicitIdWinsOverACode() {
        String own = uniqueType("evowncode");
        String explicit = uniqueType("evexplicit");
        var r = http.post("/api/events/batch", """
                {"items":[{"type":"%s","source":"s","data":{},"clientCode":"%s"},
                          {"type":"%s","source":"s","data":{},"clientId":"%s","clientCode":"%s"}]}
                """.formatted(own, CODE_A, explicit, CLIENT_A, CODE_B), CLIENT_CALLER);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        assertThat(eventClient(own)).isEqualTo(CLIENT_A);
        assertThat(eventClient(explicit)).isEqualTo(CLIENT_A);
    }

    /// Before: a blank `clientId` was not absent, so a non-anchor was refused
    /// "No access to client: " and the code beside it was never consulted.
    @Test
    void aBlankClientIdIsAbsentForANonAnchor() {
        String empty = uniqueType("evemptyid");
        String spaces = uniqueType("evblankid");
        String withCode = uniqueType("evblankidcode");
        var r = http.post("/api/events/batch", """
                {"items":[{"type":"%s","source":"s","data":{},"clientId":""},
                          {"type":"%s","source":"s","data":{},"clientId":"  "},
                          {"type":"%s","source":"s","data":{},"clientId":" ","clientCode":"%s"}]}
                """.formatted(empty, spaces, withCode, CODE_A), CLIENT_CALLER);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        assertThat(eventClient(empty)).as("the single client's default").isEqualTo(CLIENT_A);
        assertThat(eventClient(spaces)).isEqualTo(CLIENT_A);
        assertThat(eventClient(withCode)).as("the code is consulted").isEqualTo(CLIENT_A);

        var otherCode = http.post("/api/events/batch", """
                {"items":[{"type":"%s","source":"s","data":{},"clientId":"","clientCode":"%s"}]}
                """.formatted(uniqueType("evblankidother"), CODE_B), CLIENT_CALLER);
        assertRefused(otherCode, 403, "FORBIDDEN", "No access to client: " + CODE_B);
    }

    /// Before: an anchor's blank `clientId` was stored as `""`.
    @Test
    void aBlankClientIdIsAbsentForAnAnchor() {
        String blank = uniqueType("evanchorblank");
        String withCode = uniqueType("evanchorblankcode");
        var r = http.post("/api/events/batch", """
                {"items":[{"type":"%s","source":"s","data":{},"clientId":" "},
                          {"type":"%s","source":"s","data":{},"clientId":"","clientCode":"%s"}]}
                """.formatted(blank, withCode, CODE_B), ANCHOR);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        assertThat(eventClient(blank)).as("platform-scoped, not \"\"").isNull();
        assertThat(eventClient(withCode)).isEqualTo(CLIENT_B);

        String single = uniqueType("evanchorsingblank");
        var s = http.post("/api/events", """
                {"eventType":"%s","source":"s","data":{},"clientId":""}
                """.formatted(single), ANCHOR);
        assertThat(s.statusCode()).as(s.body()).isEqualTo(201);
        assertThat(eventClient(single)).isNull();
    }

    /// Before: the singular route's default fired only on a `null` clientId,
    /// so a blank one was refused for a non-anchor.
    @Test
    void theSingularRoutesBlankClientIdTakesTheCallersClient() {
        String type = uniqueType("evsingblank");
        var r = http.post("/api/events", """
                {"eventType":"%s","source":"s","data":{},"clientId":"  "}
                """.formatted(type), CLIENT_CALLER);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        assertThat(eventClient(type)).isEqualTo(CLIENT_A);

        var other = http.post("/api/events", """
                {"eventType":"%s","source":"s","data":{},"clientId":"%s"}
                """.formatted(uniqueType("evsingother"), CLIENT_B), CLIENT_CALLER);
        assertThat(other.statusCode()).as(other.body()).isEqualTo(403);
        assertThat(json(other).get("message").asString()).isEqualTo("No access to client: " + CLIENT_B);

        // A caller reaching two clients has no single-client default: only the singular
        // route's first-client default (spec §5 D3) can place the event, so it must see the
        // blank clientId as absent too.
        String partnerType = uniqueType("evsingpartner");
        var partner = http.post("/api/events", """
                {"eventType":"%s","source":"s","data":{},"clientId":""}
                """.formatted(partnerType), PARTNER);
        assertThat(partner.statusCode()).as(partner.body()).isEqualTo(201);
        assertThat(eventClient(partnerType)).isEqualTo(CLIENT_A);
    }

    // ── Dispatch jobs: clientCode ────────────────────────────────────────

    private static String job(String code, String extra) {
        return "{\"code\":\"" + code + "\",\"targetUrl\":\"https://target.test/hook\"" + extra + "}";
    }

    private static String single(String code, String extra) {
        return "{\"code\":\"" + code + "\",\"targetUrl\":\"https://target.test/hook\",\"payload\":\"{}\",\"serviceAccountId\":\"sa\""
                + extra + "}";
    }

    /// Before: the routes had no clientCode; it was ignored and an anchor's
    /// job was written platform-scoped.
    @Test
    void anAnchorsDispatchJobCodeResolvesAnExplicitIdWinsAndABlankCodeIsIgnored() {
        String resolved = uniqueType("djanchorcode");
        String resolvedToo = uniqueType("djanchorcode2");
        String explicit = uniqueType("djexplicit");
        String blank = uniqueType("djblankcode");
        int before = clientLookups.get();
        var r = http.post("/api/dispatch-jobs/batch", "{\"items\":[" + String.join(",",
                job(resolved, ",\"clientCode\":\"" + CODE_B + "\""),
                job(resolvedToo, ",\"clientCode\":\"" + CODE_B + "\""),
                job(explicit, ",\"clientId\":\"" + CLIENT_A + "\",\"clientCode\":\"" + CODE_B + "\""),
                job(blank, ",\"clientCode\":\"  \"")) + "]}", ANCHOR);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        assertThat(jobClient(resolved)).isEqualTo(CLIENT_B);
        assertThat(jobClient(resolvedToo)).isEqualTo(CLIENT_B);
        assertThat(jobClient(explicit)).as("the clientId wins").isEqualTo(CLIENT_A);
        assertThat(jobClient(blank)).as("a blank code is not given").isNull();
        assertThat(clientLookups.get() - before).as("one lookup per distinct code").isEqualTo(1);

        String one = uniqueType("djanchorsingle");
        var s = http.post("/api/dispatch-jobs", single(one, ",\"clientCode\":\"" + CODE_B + "\""), ANCHOR);
        assertThat(s.statusCode()).as(s.body()).isEqualTo(201);
        assertThat(jobClient(one)).isEqualTo(CLIENT_B);
    }

    /// A job is never written unscoped because of an unknown code.
    @Test
    void anAnchorsUnknownDispatchJobCodeIsClientNotFoundAndNothingIsWritten() {
        String valid = uniqueType("djanchorvalid");
        String unknown = uniqueType("djanchorunknown");
        var r = http.post("/api/dispatch-jobs/batch", "{\"items\":[" + job(valid, "") + ","
                + job(unknown, ",\"clientCode\":\"" + UNKNOWN_CODE + "\"") + "]}", ANCHOR);
        assertRefused(r, 400, "CLIENT_NOT_FOUND", "Client not found: " + UNKNOWN_CODE);
        assertThat(countJobs(valid)).as("the whole batch is refused").isZero();
        assertThat(countJobs(unknown)).isZero();

        String one = uniqueType("djanchorsingleunknown");
        var s = http.post("/api/dispatch-jobs", single(one, ",\"clientCode\":\"" + UNKNOWN_CODE + "\""), ANCHOR);
        assertRefused(s, 400, "CLIENT_NOT_FOUND", "Client not found: " + UNKNOWN_CODE);
        assertThat(countJobs(one)).isZero();
    }

    @Test
    void aNonAnchorsDispatchJobCodeMustNameAClientItReaches() {
        String own = uniqueType("djowncode");
        String explicit = uniqueType("djownexplicit");
        var r = http.post("/api/dispatch-jobs/batch", "{\"items\":[" + String.join(",",
                job(own, ",\"clientCode\":\"" + CODE_A + "\""),
                job(explicit, ",\"clientId\":\"" + CLIENT_A + "\",\"clientCode\":\"" + CODE_B + "\"")) + "]}", CLIENT_CALLER);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        assertThat(jobClient(own)).isEqualTo(CLIENT_A);
        assertThat(jobClient(explicit)).isEqualTo(CLIENT_A);

        // Another client's code and an unknown one: the same refusal, naming the code. Before,
        // both were ignored and the job was written under the caller's own client.
        for (String code : new String[] {CODE_B, UNKNOWN_CODE}) {
            String valid = uniqueType("djclientvalid");
            String refused = uniqueType("djclientrefused");
            var b = http.post("/api/dispatch-jobs/batch", "{\"items\":[" + job(valid, "") + ","
                    + job(refused, ",\"clientCode\":\"" + code + "\"") + "]}", CLIENT_CALLER);
            assertRefused(b, 403, "FORBIDDEN", "No access to client: " + code);
            assertThat(countJobs(valid)).as("the whole batch is refused").isZero();
            assertThat(countJobs(refused)).isZero();

            String one = uniqueType("djclientsingle");
            var s = http.post("/api/dispatch-jobs", single(one, ",\"clientCode\":\"" + code + "\""), CLIENT_CALLER);
            assertRefused(s, 403, "FORBIDDEN", "No access to client: " + code);
            assertThat(countJobs(one)).isZero();
        }

        // A caller reaching two clients names one by its code (before: no client, refused).
        String partner = uniqueType("djpartnercode");
        var p = http.post("/api/dispatch-jobs/batch", "{\"items\":[" + job(partner, ",\"clientCode\":\"" + CODE_B + "\"") + "]}",
                PARTNER);
        assertThat(p.statusCode()).as(p.body()).isEqualTo(201);
        assertThat(jobClient(partner)).isEqualTo(CLIENT_B);
    }

    // ── Dispatch jobs: blank clientId ────────────────────────────────────

    /// Before: a blank `clientId` was refused "No access to client: " for a
    /// non-anchor and stored as sent for an anchor.
    @Test
    void aDispatchJobsBlankClientIdIsAbsent() {
        String batchCode = uniqueType("djblankbatch");
        var r = http.post("/api/dispatch-jobs/batch", """
                {"items":[{"code":"%s","targetUrl":"https://target.test/hook","clientId":" "}]}
                """.formatted(batchCode), CLIENT_CALLER);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        assertThat(jobClient(batchCode)).as("the single client's default").isEqualTo(CLIENT_A);

        String singleCode = uniqueType("djblanksingle");
        var s = http.post("/api/dispatch-jobs", """
                {"code":"%s","targetUrl":"https://target.test/hook","payload":"{}","serviceAccountId":"sa","clientId":""}
                """.formatted(singleCode), CLIENT_CALLER);
        assertThat(s.statusCode()).as(s.body()).isEqualTo(201);
        assertThat(jobClient(singleCode)).isEqualTo(CLIENT_A);

        String anchorCode = uniqueType("djblankanchor");
        var a = http.post("/api/dispatch-jobs/batch", """
                {"items":[{"code":"%s","targetUrl":"https://target.test/hook","clientId":""}]}
                """.formatted(anchorCode), ANCHOR);
        assertThat(a.statusCode()).as(a.body()).isEqualTo(201);
        assertThat(jobClient(anchorCode)).as("platform-scoped, not \"\"").isNull();
    }
}
