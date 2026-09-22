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
import io.flowcatalyst.platform.function.DnsException;
import io.flowcatalyst.platform.function.TxtResolver;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/// `/api/function-domains` + `/api/function-routes` end to end (spec
/// `function-public-routes.md` §1, §2): routing, the coarse permission gate,
/// and the claim → verify → list → release round trip. Exhaustive
/// exact-match / dev-mode-localhost mutation coverage lives in
/// `FunctionDomainOperationsTest`, which this file does not repeat.
@SuppressWarnings("deprecation")
class FunctionDomainApiTest {

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);

    private static final ApplicationRepository applications = new ApplicationRepository(TestPg.dataSource());
    private static final FunctionRepository functions = new FunctionRepository(TestPg.dataSource());
    private static final FunctionDomainRepository domains = new FunctionDomainRepository(TestPg.dataSource());
    private static final FunctionRouteRepository routes = new FunctionRouteRepository(TestPg.dataSource());
    private static final UnitOfWork uow = new UnitOfWork(TestPg.dataSource(), new PlatformSink(Json.MAPPER));

    /// The test's TXT-resolver seam: each test sets what the next `verify`
    /// call should "find" — read by [#RESOLVER], the one instance the harness
    /// registers ([FunctionDomainApi.State] takes exactly one [TxtResolver]).
    private static final AtomicReference<List<String>> NEXT_TXT_VALUES = new AtomicReference<>(List.of());
    private static final TxtResolver RESOLVER = name -> NEXT_TXT_VALUES.get();

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
    /// A SECOND harness, sharing every repository, whose resolver always
    /// fails — the ONLY way to reach `503 DNS_UNAVAILABLE` through the real
    /// HTTP handler (spec §1, §6 M1): [FunctionDomainApi.State] takes one
    /// fixed [TxtResolver] at registration, so the "always fails" behaviour
    /// needs its own route registration, not a per-test toggle.
    private static TestHttp httpDnsDown;

    @BeforeAll
    static void start() {
        var keys = SigningKeys.generateEphemeral();
        var verifier = new JwtVerifier(new JwtVerifier.Config("http://localhost:8080", new JwtVerifier.RsaKeys(keys.publicKey())));
        var auth = new Authenticator(verifier, ClaimsResolver.none(), Authenticator.Config.of(true));
        http = TestHttp.routes(r -> {
            HttpError.install(r);
            r.before("/api/*", auth);
            FunctionDomainApi.register(r, new FunctionDomainApi.State(domains, routes, functions, uow, RESOLVER, false));
        });
        TxtResolver failing = name -> {
            throw new DnsException("simulated resolver failure");
        };
        httpDnsDown = TestHttp.routes(r -> {
            HttpError.install(r);
            r.before("/api/*", auth);
            FunctionDomainApi.register(r, new FunctionDomainApi.State(domains, routes, functions, uow, failing, false));
        });
    }

    @AfterAll
    static void stop() {
        http.close();
        httpDnsDown.close();
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

    // ── Claim → verify → list → release round trip ───────────────────────────

    @Test
    void claimVerifyListAndReleaseRoundTrip() {
        String h = host("roundtrip");
        var claimed = http.post("/api/function-domains", "{\"hostname\":\"" + h + "\"}", MANAGE);
        assertThat(claimed.statusCode()).as(claimed.body()).isEqualTo(201);
        JsonNode claimedBody = json(claimed);
        assertThat(claimedBody.get("hostname").asString()).isEqualTo(h);
        assertThat(claimedBody.get("owner").asString()).isEqualTo("platform");
        assertThat(claimedBody.get("verification").get("state").asString()).isEqualTo("PENDING");
        String recordValue = claimedBody.get("verification").get("record").get("value").asString();
        assertThat(recordValue).startsWith("fc-verify=");

        // A second claim of the SAME hostname conflicts and never names the holder.
        var retaken = http.post("/api/function-domains", "{\"hostname\":\"" + h + "\"}", MANAGE);
        assertThat(retaken.statusCode()).isEqualTo(409);
        assertThat(json(retaken).get("error").asString()).isEqualTo("DOMAIN_TAKEN");

        // GET list, filtered to the platform owner.
        var listed = http.get("/api/function-domains?clientId=platform", MANAGE);
        assertThat(listed.statusCode()).as(listed.body()).isEqualTo(200);
        assertThat(json(listed)).anySatisfy(n -> assertThat(n.get("hostname").asString()).isEqualTo(h));

        // Verify with a resolver that answers the exact token.
        NEXT_TXT_VALUES.set(List.of(recordValue));
        var verified = http.post("/api/function-domains/" + h + "/verify", "", MANAGE);
        assertThat(verified.statusCode()).as(verified.body()).isEqualTo(200);
        assertThat(json(verified).get("verification").get("state").asString()).isEqualTo("VERIFIED");
        assertThat(json(verified).get("verification").has("record")).as("no record once verified").isFalse();

        // Release.
        var released = http.delete("/api/function-domains/" + h, MANAGE);
        assertThat(released.statusCode()).isEqualTo(204);
        var afterRelease = http.get("/api/function-domains?clientId=platform", MANAGE);
        assertThat(json(afterRelease)).noneSatisfy(n -> assertThat(n.get("hostname").asString()).isEqualTo(h));
    }

    /// spec §1, §6 M1: no TXT value matches ⇒ `409 DOMAIN_NOT_VERIFIED`, not
    /// `503` — the negative control for the resolver-failure test below.
    @Test
    void verifyWithNoMatchingTxtValueIs409DomainNotVerified() {
        String h = host("nomatch");
        http.post("/api/function-domains", "{\"hostname\":\"" + h + "\"}", MANAGE);
        NEXT_TXT_VALUES.set(List.of("v=spf1 ~all"));

        var r = http.post("/api/function-domains/" + h + "/verify", "", MANAGE);
        assertThat(r.statusCode()).as("no matching TXT value ⇒ 409, not 503").isEqualTo(409);
        assertThat(json(r).get("error").asString()).isEqualTo("DOMAIN_NOT_VERIFIED");
    }

    /// spec §1, §6 M1: a resolver failure is `503 DNS_UNAVAILABLE`, never
    /// silently treated as "not verified" — pinned through the REAL HTTP
    /// handler's `catch (DnsUnavailableException)`, not just the operation.
    @Test
    void verifyWithAResolverFailureIs503DnsUnavailable() {
        String h = host("dnsdown");
        http.post("/api/function-domains", "{\"hostname\":\"" + h + "\"}", MANAGE);

        var r = httpDnsDown.post("/api/function-domains/" + h + "/verify", "", MANAGE);
        assertThat(r.statusCode()).as("mutant: treat failure as unverified").isEqualTo(503);
        assertThat(json(r).get("error").asString()).isEqualTo("DNS_UNAVAILABLE");
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
        FunctionRoute route = FunctionRoute.of(f.id(), Hostname.parse(h), RoutePattern.parse("/"), Instant.now());
        uow.inTransaction(tx -> {
            routes.replaceForFunction(f.id(), List.of(route), tx.dbTx());
            return null;
        });

        var r = http.delete("/api/function-domains/" + h, MANAGE);
        assertThat(r.statusCode()).isEqualTo(409);
        assertThat(json(r).get("error").asString()).isEqualTo("DOMAIN_IN_USE");
        assertThat(r.body()).contains(f.address().render());
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
        assertThat(reachableBody.get("verification").get("state").asString()).isEqualTo("PENDING");

        // Out of reach (a DIFFERENT client's domain): 404, never 403 — pins "skip the
        // reach check", which would answer 200 with clientA's domain to clientB's caller.
        var outOfReach = http.get("/api/function-domains/" + h, clientView(clientB));
        assertThat(outOfReach.statusCode()).as("mutant: skip the reach check — clientB should not see clientA's domain")
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
        FunctionRoute route = FunctionRoute.of(f.id(), Hostname.parse(h), RoutePattern.parse("/api"), Instant.now());
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
