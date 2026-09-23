package io.flowcatalyst.platform.function.api;

import io.flowcatalyst.platform.application.Application;
import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.platform.application.ApplicationType;
import io.flowcatalyst.platform.function.DnsLabel;
import io.flowcatalyst.platform.function.Function;
import io.flowcatalyst.platform.function.FunctionAddress;
import io.flowcatalyst.platform.function.FunctionDomainRepository;
import io.flowcatalyst.platform.function.FunctionOwner;
import io.flowcatalyst.platform.function.FunctionRepository;
import io.flowcatalyst.platform.function.FunctionRoute;
import io.flowcatalyst.platform.function.FunctionRouteRepository;
import io.flowcatalyst.platform.function.Hostname;
import io.flowcatalyst.platform.function.RoutePattern;
import io.flowcatalyst.platform.function.Runtime;
import io.flowcatalyst.platform.shared.TestHttp;
import io.flowcatalyst.platform.shared.auth.Authenticator;
import io.flowcatalyst.platform.shared.auth.ClaimsResolver;
import io.flowcatalyst.platform.shared.auth.JwtVerifier;
import io.flowcatalyst.platform.shared.auth.SigningKeys;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.shared.platformsink.PlatformSink;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import io.flowcatalyst.testpg.TestPg;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/// `/api/function-domains` + `/api/function-routes` end to end (spec
/// `function-public-routes.md` §1, §2, amended `function-domains-no-dns.md`):
/// routing, the coarse permission gate, and the claim → list → release round
/// trip. A claim is verified by being made — there is no verify route, no
/// resolver seam, no `verification` key on the wire. Exhaustive nesting /
/// zone-covering mutation coverage lives in `FunctionDomainOperationsTest`,
/// which this file does not repeat.
@SuppressWarnings("deprecation")
class FunctionDomainApiTest {

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);

    private static final ApplicationRepository applications = new ApplicationRepository(TestPg.dataSource());
    private static final FunctionRepository functions = new FunctionRepository(TestPg.dataSource());
    private static final FunctionDomainRepository domains = new FunctionDomainRepository(TestPg.dataSource());
    private static final FunctionRouteRepository routes = new FunctionRouteRepository(TestPg.dataSource());
    private static final UnitOfWork uow = new UnitOfWork(TestPg.dataSource(), new PlatformSink(Json.MAPPER));

    private static final String[] ANCHOR = {
            Authenticator.TEST_PRINCIPAL, "usr_anchor_" + RUN, Authenticator.TEST_SCOPE, "ANCHOR",
            Authenticator.TEST_PERMISSIONS, "platform:*:*:*"};

    private static final String[] MANAGE = {
            Authenticator.TEST_PRINCIPAL, "usr_manage_" + RUN, Authenticator.TEST_SCOPE, "ANCHOR",
            Authenticator.TEST_PERMISSIONS, "platform:function:domain:manage,platform:function:function:view"};

    private static final String[] VIEW_ONLY = {
            Authenticator.TEST_PRINCIPAL, "usr_view_" + RUN, Authenticator.TEST_SCOPE, "ANCHOR",
            Authenticator.TEST_PERMISSIONS, "platform:function:function:view"};

    private static TestHttp http;

    @BeforeAll
    static void start() {
        var keys = SigningKeys.generateEphemeral();
        var verifier = new JwtVerifier(new JwtVerifier.Config("http://localhost:8080", new JwtVerifier.RsaKeys(keys.publicKey())));
        var auth = new Authenticator(verifier, ClaimsResolver.none(), Authenticator.Config.of(true));
        http = TestHttp.routes(r -> {
            HttpError.install(r);
            r.before("/api/*", auth);
            FunctionDomainApi.register(r, new FunctionDomainApi.State(domains, routes, functions, uow));
        });
    }

    @AfterAll
    static void stop() {
        http.close();
    }

    private static JsonNode json(HttpResponse<String> r) {
        return Json.MAPPER.readTree(r.body());
    }

    private static String host(String tag) {
        return "fda-" + tag + "-" + RUN + "-" + UUID.randomUUID().toString().substring(0, 8).toLowerCase(Locale.ROOT)
                + ".example.com";
    }

    // ── Coarse permission gate ───────────────────────────────────────────────

    /// Ordinary `/api/*` routes give 403 on a missing credential (unlike
    /// `/control/functions/*`'s special 401 rule, spec `function-api.md`
    /// §2 — that rule is `FunctionControlApi`'s alone).
    @Test
    void claimWithNoCredentialIs403() {
        var r = http.post("/api/function-domains", "{\"hostname\":\"" + host("noauth") + "\"}");
        assertThat(r.statusCode()).isEqualTo(403);
    }

    @Test
    void claimWithoutManagePermissionIs403() {
        var r = http.post("/api/function-domains", "{\"hostname\":\"" + host("noperm") + "\"}", VIEW_ONLY);
        assertThat(r.statusCode()).isEqualTo(403);
    }

    // ── Claim → list → release round trip ─────────────────────────────────

    @Test
    void claimListAndReleaseRoundTrip() {
        String h = host("roundtrip");
        var claimed = http.post("/api/function-domains", "{\"hostname\":\"" + h + "\"}", MANAGE);
        assertThat(claimed.statusCode()).as(claimed.body()).isEqualTo(201);
        JsonNode claimedBody = json(claimed);
        assertThat(claimedBody.get("hostname").asString()).isEqualTo(h);
        assertThat(claimedBody.get("owner").asString()).isEqualTo("platform");
        // N3: no verification key at all — a claim is verified by being made.
        assertThat(claimedBody.has("verification")).as("mutant: still carry a verification key").isFalse();

        // A second claim of the SAME hostname conflicts and never names the holder.
        var retaken = http.post("/api/function-domains", "{\"hostname\":\"" + h + "\"}", MANAGE);
        assertThat(retaken.statusCode()).isEqualTo(409);
        assertThat(json(retaken).get("error").asString()).isEqualTo("DOMAIN_TAKEN");

        // GET list, filtered to the platform owner.
        var listed = http.get("/api/function-domains?clientId=platform", MANAGE);
        assertThat(listed.statusCode()).as(listed.body()).isEqualTo(200);
        assertThat(json(listed)).anySatisfy(n -> assertThat(n.get("hostname").asString()).isEqualTo(h));

        // Release.
        var released = http.delete("/api/function-domains/" + h, MANAGE);
        assertThat(released.statusCode()).isEqualTo(204);
        var afterRelease = http.get("/api/function-domains?clientId=platform", MANAGE);
        assertThat(json(afterRelease)).noneSatisfy(n -> assertThat(n.get("hostname").asString()).isEqualTo(h));
    }

    /// N3: the verify route is gone — a request to it 404s with the router's
    /// own not-found shape (no matching route at all), never a handler that
    /// happens to answer something else.
    @Test
    void verifyRouteIsGoneAndAnswersTheRoutersOwnNotFoundShape() {
        String h = host("noverify");
        http.post("/api/function-domains", "{\"hostname\":\"" + h + "\"}", MANAGE);

        var r = http.post("/api/function-domains/" + h + "/verify", "", MANAGE);
        assertThat(r.statusCode()).as("mutant: a handler still answers this route").isEqualTo(404);
    }

    @Test
    void releaseOfADomainInUseIs409NamingTheFunction() {
        String h = host("inuse");
        http.post("/api/function-domains", "{\"hostname\":\"" + h + "\"}", MANAGE);

        String appId = testApplication("finuse");
        FunctionAddress address = FunctionAddress.of(new DnsLabel("fda" + RUN), new DnsLabel("svc"),
                new DnsLabel(UUID.randomUUID().toString().substring(0, 8).toLowerCase(Locale.ROOT)));
        Function f = Function.create(appId, address, new FunctionOwner.Platform(), Runtime.JVM, null);
        uow.inTransaction(tx -> {
            functions.persist(f, tx.dbTx());
            return null;
        });
        FunctionRoute route = FunctionRoute.of(f.id(), Hostname.parse(h), RoutePattern.parse("/"), List.of(), Instant.now());
        uow.inTransaction(tx -> {
            routes.replaceForFunction(f.id(), List.of(route), tx.dbTx());
            return null;
        });

        var r = http.delete("/api/function-domains/" + h, MANAGE);
        assertThat(r.statusCode()).isEqualTo(409);
        assertThat(json(r).get("error").asString()).isEqualTo("DOMAIN_IN_USE");
        assertThat(r.body()).contains(f.address().render());
    }

    // ── Z4 (spec `function-zones-and-aliases.md` §8): release refuses a ZONE
    // claim while a DEEPER hostname under it is routed — the covering check,
    // not the old equality check, which would have missed this. ────────────

    @Test
    void releaseOfAZoneIsDomainInUseWhileADeeperHostnameIsRouted() {
        String apex = host("z4apex");
        http.post("/api/function-domains", "{\"hostname\":\"" + apex + "\"}", MANAGE);
        String deep = "myapp." + apex;

        String appId = testApplication("z4inuse");
        FunctionAddress address = FunctionAddress.of(new DnsLabel("fda" + RUN), new DnsLabel("svc"),
                new DnsLabel(UUID.randomUUID().toString().substring(0, 8).toLowerCase(Locale.ROOT)));
        Function f = Function.create(appId, address, new FunctionOwner.Platform(), Runtime.JVM, null);
        uow.inTransaction(tx -> {
            functions.persist(f, tx.dbTx());
            return null;
        });
        // The routed hostname is DEEPER than the claimed apex — equality would miss it.
        FunctionRoute route = FunctionRoute.of(f.id(), Hostname.parse(deep), RoutePattern.parse("/"), List.of(), Instant.now());
        uow.inTransaction(tx -> {
            routes.replaceForFunction(f.id(), List.of(route), tx.dbTx());
            return null;
        });

        var r = http.delete("/api/function-domains/" + apex, MANAGE);
        assertThat(r.statusCode()).as("mutant: equality instead of covering").isEqualTo(409);
        assertThat(json(r).get("error").asString()).isEqualTo("DOMAIN_IN_USE");
        assertThat(r.body()).contains(f.address().render());
    }

    // ── Z2 (spec `function-zones-and-aliases.md` §8): no two claims may
    // nest, by ANY owner, in EITHER order — and a same-owner attempt is
    // refused just the same (no self-exception). Each direction is its own
    // test so a mutant dropping either the "covers" or the "covered by"
    // check dies on its own dedicated test. ──────────────────────────────

    /// Claiming a SUB-hostname after the apex is already claimed (by another
    /// owner) ⇒ `DOMAIN_TAKEN`. Mutant: drop the "covers d" check.
    @Test
    void claimOfASubHostnameAfterTheApexIsAlreadyClaimedIsDomainTaken() {
        String apex = host("z2apex1");
        var claimApex = http.post("/api/function-domains", "{\"hostname\":\"" + apex + "\"}", MANAGE);
        assertThat(claimApex.statusCode()).as(claimApex.body()).isEqualTo(201);

        var claimDeep = http.post("/api/function-domains", "{\"hostname\":\"myapp." + apex + "\"}", MANAGE);
        assertThat(claimDeep.statusCode()).as("mutant: drop the covers-d check").isEqualTo(409);
        assertThat(json(claimDeep).get("error").asString()).isEqualTo("DOMAIN_TAKEN");
    }

    /// The REVERSE order: the sub-hostname is claimed FIRST, then the apex ⇒
    /// still `DOMAIN_TAKEN`. Mutant: drop the "covered by d" check.
    @Test
    void claimOfTheApexAfterASubHostnameIsAlreadyClaimedIsDomainTaken() {
        String apex = host("z2apex2");
        var claimDeep = http.post("/api/function-domains", "{\"hostname\":\"myapp." + apex + "\"}", MANAGE);
        assertThat(claimDeep.statusCode()).as(claimDeep.body()).isEqualTo(201);

        var claimApex = http.post("/api/function-domains", "{\"hostname\":\"" + apex + "\"}", MANAGE);
        assertThat(claimApex.statusCode()).as("mutant: drop the covered-by-d check").isEqualTo(409);
        assertThat(json(claimApex).get("error").asString()).isEqualTo("DOMAIN_TAKEN");
    }

    /// The SAME owner attempting to claim a nested zone is refused too — no
    /// self-exception (spec §6 M1's "never names the holder" rule already
    /// covers the equals case; this pins that nesting gets no carve-out
    /// either, for either direction).
    @Test
    void claimNestingIsRefusedEvenForTheSameOwner() {
        String apex = host("z2same");
        var claimApex = http.post("/api/function-domains", "{\"hostname\":\"" + apex + "\"}", MANAGE);
        assertThat(claimApex.statusCode()).as(claimApex.body()).isEqualTo(201);

        var claimDeep = http.post("/api/function-domains", "{\"hostname\":\"myapp." + apex + "\"}", MANAGE);
        assertThat(claimDeep.statusCode()).as("no self-exception to the nesting rule").isEqualTo(409);
        assertThat(json(claimDeep).get("error").asString()).isEqualTo("DOMAIN_TAKEN");
    }

    // ── GET /api/function-domains/{hostname} (S3) ────────────────────────────

    private static String[] clientView(String clientId) {
        return new String[]{Authenticator.TEST_PRINCIPAL, "usr_s3_" + UUID.randomUUID().toString().substring(0, 8),
                Authenticator.TEST_SCOPE, "CLIENT", Authenticator.TEST_CLIENTS, clientId,
                Authenticator.TEST_PERMISSIONS, "platform:function:function:view"};
    }

    /// S3a: reachable ⇒ 200 with the claim's own shape; a domain owned by a
    /// DIFFERENT client the caller cannot reach ⇒ 404, never 403 (spec §2's
    /// "never confirm what exists" reach discipline, shared with every other
    /// by-resource read through [Access#byHostname]).
    @Test
    void s3aGetDomainByHostnameReachableIs200OutOfReachIs404NeverA403() {
        String clientA = "clt_s3a_" + RUN;
        String clientB = "clt_s3b_" + RUN;
        String h = host("s3reach");
        var claimed = http.post("/api/function-domains",
                "{\"hostname\":\"" + h + "\",\"clientId\":\"" + clientA + "\"}", MANAGE);
        assertThat(claimed.statusCode()).as(claimed.body()).isEqualTo(201);
        JsonNode claimedBody = json(claimed);

        // Reachable: the SAME shape claim() returned, read back through the new route.
        var reachable = http.get("/api/function-domains/" + h, clientView(clientA));
        assertThat(reachable.statusCode()).as(reachable.body()).isEqualTo(200);
        JsonNode reachableBody = json(reachable);
        assertThat(reachableBody.get("id").asString()).isEqualTo(claimedBody.get("id").asString());
        assertThat(reachableBody.get("hostname").asString()).isEqualTo(h);
        assertThat(reachableBody.get("owner").asString()).isEqualTo(clientA);
        assertThat(reachableBody.has("verification")).as("mutant: still carry a verification key").isFalse();

        // Out of reach (a DIFFERENT client's domain): 404, never 403 — pins "skip the
        // reach check", which would answer 200 with clientA's domain to clientB's caller.
        var outOfReach = http.get("/api/function-domains/" + h, clientView(clientB));
        assertThat(outOfReach.statusCode()).as("mutant: skip the reach check — clientB should not see clientA's domain")
                .isEqualTo(404);
        assertThat(json(outOfReach).get("error").asString()).isEqualTo("FunctionDomain_NOT_FOUND");
    }

    /// Z5: a DEEPER hostname than the claimed apex resolves to the zone's
    /// claim (same id, same shape) — never the exact-match-only 404 the
    /// pre-zones code would have given. A client who cannot reach the ZONE'S
    /// owner still gets 404 on the deep hostname too (never confirming the
    /// zone exists), same as S3a's exact-hostname case.
    @Test
    void z5GetDomainByADeeperHostnameResolvesToTheZonesClaimReachableIs200OutOfReachIs404() {
        String clientA = "clt_z5a_" + RUN;
        String clientB = "clt_z5b_" + RUN;
        String apex = host("z5apex");
        var claimed = http.post("/api/function-domains",
                "{\"hostname\":\"" + apex + "\",\"clientId\":\"" + clientA + "\"}", MANAGE);
        assertThat(claimed.statusCode()).as(claimed.body()).isEqualTo(201);
        JsonNode claimedBody = json(claimed);
        String deep = "qa-myapp." + apex;

        var reachable = http.get("/api/function-domains/" + deep, clientView(clientA));
        assertThat(reachable.statusCode()).as(reachable.body()).isEqualTo(200);
        JsonNode reachableBody = json(reachable);
        assertThat(reachableBody.get("id").asString())
                .as("resolves to the ZONE's own claim, not a fictional per-hostname one")
                .isEqualTo(claimedBody.get("id").asString());
        assertThat(reachableBody.get("hostname").asString()).as("the zone apex, not the deep hostname queried")
                .isEqualTo(apex);

        var outOfReach = http.get("/api/function-domains/" + deep, clientView(clientB));
        assertThat(outOfReach.statusCode()).as("never confirm the zone exists to an unreachable client")
                .isEqualTo(404);
        assertThat(json(outOfReach).get("error").asString()).isEqualTo("FunctionDomain_NOT_FOUND");
    }

    /// An unknown hostname (never claimed) is the same 404, and an invalid
    /// hostname is the same 400 `HOSTNAME_INVALID` the claim route gives —
    /// [Hostname#parse] is the one parser both routes share.
    @Test
    void s3getDomainUnknownIs404AndInvalidHostnameIs400() {
        var unknown = http.get("/api/function-domains/" + host("s3unknown"), MANAGE);
        assertThat(unknown.statusCode()).isEqualTo(404);
        assertThat(json(unknown).get("error").asString()).isEqualTo("FunctionDomain_NOT_FOUND");

        var invalid = http.get("/api/function-domains/not-a-hostname", MANAGE);
        assertThat(invalid.statusCode()).isEqualTo(400);
        assertThat(json(invalid).get("error").asString()).isEqualTo("HOSTNAME_INVALID");
    }

    // ── GET /api/function-routes ──────────────────────────────────────────

    @Test
    void listRoutesByAddressAndByHostname() {
        String appId = testApplication("froutes");
        FunctionAddress address = FunctionAddress.of(new DnsLabel("fda" + RUN), new DnsLabel("svc"),
                new DnsLabel(UUID.randomUUID().toString().substring(0, 8).toLowerCase(Locale.ROOT)));
        Function f = Function.create(appId, address, new FunctionOwner.Platform(), Runtime.JVM, null);
        uow.inTransaction(tx -> {
            functions.persist(f, tx.dbTx());
            return null;
        });
        String h = host("routes");
        FunctionRoute route = FunctionRoute.of(f.id(), Hostname.parse(h), RoutePattern.parse("/api"),
                List.of("qa", "staging"), Instant.now());
        uow.inTransaction(tx -> {
            routes.replaceForFunction(f.id(), List.of(route), tx.dbTx());
            return null;
        });

        var byAddress = http.get("/api/function-routes?address=" + address.render(), VIEW_ONLY);
        assertThat(byAddress.statusCode()).as(byAddress.body()).isEqualTo(200);
        JsonNode byAddressBody = json(byAddress);
        assertThat(byAddressBody).hasSize(1);
        assertThat(byAddressBody.get(0).get("hostname").asString()).isEqualTo(h);
        assertThat(byAddressBody.get(0).get("pathPrefix").asString()).isEqualTo("/api");
        assertThat(byAddressBody.get(0).get("address").asString()).isEqualTo(address.render());
        assertThat(byAddressBody.get(0).get("aliasPrefixes").valueStream().map(JsonNode::asString).toList())
                .as("mutant: drop aliasPrefixes from FunctionRouteResponse")
                .containsExactly("qa", "staging");

        var byHostname = http.get("/api/function-routes?hostname=" + h, VIEW_ONLY);
        assertThat(byHostname.statusCode()).as(byHostname.body()).isEqualTo(200);
        assertThat(json(byHostname)).hasSize(1);
    }

    @Test
    void listRoutesWithNeitherFilterIs400() {
        var r = http.get("/api/function-routes", VIEW_ONLY);
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(json(r).get("error").asString()).isEqualTo("FUNCTION_ROUTE_FILTER_REQUIRED");
    }

    private static String testApplication(String tag) {
        Application a = Application.create(ApplicationType.APPLICATION, "fda-" + tag + "-" + RUN
                + "-" + UUID.randomUUID().toString().substring(0, 6).toLowerCase(Locale.ROOT), "Function Domain Api " + tag);
        uow.inTransaction(tx -> {
            applications.persist(a, tx.dbTx());
            return null;
        });
        return a.id();
    }
}
