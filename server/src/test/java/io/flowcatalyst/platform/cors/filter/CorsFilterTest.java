package io.flowcatalyst.platform.cors.filter;

import io.flowcatalyst.platform.cors.CorsOriginRepository;
import io.flowcatalyst.platform.cors.api.CorsOriginApi;
import io.flowcatalyst.platform.shared.TestHttp;
import io.flowcatalyst.platform.shared.auth.Authenticator;
import io.flowcatalyst.platform.shared.auth.ClaimsResolver;
import io.flowcatalyst.platform.shared.auth.JwtVerifier;
import io.flowcatalyst.platform.shared.auth.SigningKeys;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.shared.platformsink.PlatformSink;
import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import io.flowcatalyst.testpg.TestPg;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/// End-to-end tests for [CorsFilter] against a real [CorsOriginRepository]
/// backed by the embedded Postgres (`docs/spec/cors.md` §9): the filter is
/// mounted as a plain `before` handler (not scoped to `isPlatformPath` — that
/// scoping is [io.flowcatalyst.server.Platform]'s concern, not the filter's)
/// in front of one probe route that counts its own invocations.
@SuppressWarnings("deprecation")
class CorsFilterTest {

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);

    private static final String[] ANCHOR = {
            Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
            Authenticator.TEST_SCOPE, "ANCHOR"};

    private static final CorsOriginRepository REPO = new CorsOriginRepository(TestPg.dataSource());
    private static final UnitOfWork UOW = new UnitOfWork(TestPg.dataSource(), new PlatformSink(Json.MAPPER));

    /// TTL of zero: every [CorsAllowlist#matches] call reloads, so a freshly
    /// POSTed origin is visible without needing [CorsAllowlist#invalidate] —
    /// isolates the filter-behaviour tests below from the cache/invalidation
    /// concern, which gets its own dedicated harness further down.
    private static final CorsAllowlist ALWAYS_FRESH_ALLOWLIST = new CorsAllowlist(REPO::allowedOrigins, Duration.ZERO, Clock.systemUTC());

    private static final AtomicInteger ROUTE_HITS = new AtomicInteger();

    private static TestHttp http;

    @BeforeAll
    static void start() {
        var keys = SigningKeys.generateEphemeral();
        var verifier = new JwtVerifier(new JwtVerifier.Config("http://localhost:8080", new JwtVerifier.RsaKeys(keys.publicKey())));
        var auth = new Authenticator(verifier, ClaimsResolver.none(), Authenticator.Config.of(true));
        http = new TestHttp(cfg -> {
            HttpError.install(cfg.routes);
            cfg.routes.before(new CorsFilter(ALWAYS_FRESH_ALLOWLIST));
            cfg.routes.before("/api/*", auth);
            CorsOriginApi.register(cfg.routes, new CorsOriginApi.State(REPO, UOW, ALWAYS_FRESH_ALLOWLIST::invalidate));
            cfg.routes.get("/probe", ctx -> {
                ROUTE_HITS.incrementAndGet();
                ctx.status(200).result("ok");
            });
        });
    }

    @AfterAll
    static void stop() {
        http.close();
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private static String origin(String tag) {
        return "https://" + tag + "-" + RUN + ".example.com";
    }

    private static void addOrigin(String origin) {
        var r = http.post("/api/platform/cors", "{\"origin\":\"" + origin + "\"}", ANCHOR);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
    }

    private static HttpResponse<String> getWithOrigin(String path, String origin) {
        return http.send("GET", path, null, "Origin", origin);
    }

    private static HttpResponse<String> preflight(String path, String origin) {
        return http.send("OPTIONS", path, null, "Origin", origin, "Access-Control-Request-Method", "GET");
    }

    // ── Simple (non-preflight) requests ─────────────────────────────────────

    /// Pins two behaviours at once: the allowed origin is echoed verbatim on
    /// `Access-Control-Allow-Origin` (mutant: echo replaced by `*` — a
    /// literal `"*"` would fail the `isEqualTo(origin)` check even though a
    /// browser would happily accept `*` for a non-credentialed request, which
    /// is exactly the bug this pins), and `Access-Control-Allow-Credentials`
    /// is present (mutant: credentials header dropped).
    @Test
    void allowedSimpleRequestIsEchoedWithCredentialsAndTheRouteRuns() {
        String o = origin("simple-allowed");
        addOrigin(o);
        int before = ROUTE_HITS.get();

        var r = getWithOrigin("/probe", o);

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).isEqualTo("ok");
        assertThat(ROUTE_HITS.get()).as("a simple request still reaches the route handler").isEqualTo(before + 1);
        assertThat(r.headers().firstValue("Access-Control-Allow-Origin")).as("echoed, not a wildcard").contains(o);
        assertThat(r.headers().firstValue("Access-Control-Allow-Credentials")).contains("true");
        assertThat(r.headers().allValues("Vary")).anyMatch(v -> v.contains("Origin"));
    }

    @Test
    void disallowedSimpleRequestGetsNoCorsHeadersButTheRouteStillRuns() {
        String o = origin("simple-denied-" + UUID.randomUUID());
        int before = ROUTE_HITS.get();

        var r = getWithOrigin("/probe", o);

        assertThat(r.statusCode()).as("server-side enforcement is the browser's job, not this filter's, for a simple request").isEqualTo(200);
        assertThat(ROUTE_HITS.get()).isEqualTo(before + 1);
        assertThat(r.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
        assertThat(r.headers().firstValue("Access-Control-Allow-Credentials")).isEmpty();
    }

    @Test
    void noOriginHeaderIsLeftEntirelyAlone() {
        var r = http.get("/probe");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
        assertThat(r.headers().firstValue("Vary")).as("nothing depended on Origin, so no reason to vary on it").isEmpty();
    }

    // ── Preflight ────────────────────────────────────────────────────────────

    /// Pins the chain-stopping behaviour: a preflight for an allowed origin
    /// must answer 204 itself and must NOT invoke `/probe`'s handler (mutant:
    /// preflight not calling `skipRemainingHandlers` — caught here because the
    /// counter would otherwise increment).
    @Test
    void preflightForAnAllowedOriginAnswers204AndNeverInvokesTheRouteHandler() {
        String o = origin("preflight-allowed");
        addOrigin(o);
        int before = ROUTE_HITS.get();

        var r = preflight("/probe", o);

        assertThat(r.statusCode()).isEqualTo(204);
        assertThat(r.body()).isEmpty();
        assertThat(r.headers().firstValue("Access-Control-Allow-Origin")).contains(o);
        assertThat(r.headers().firstValue("Access-Control-Allow-Credentials")).contains("true");
        assertThat(r.headers().firstValue("Access-Control-Allow-Methods")).contains("GET, POST, PUT, PATCH, DELETE, OPTIONS");
        assertThat(r.headers().firstValue("Access-Control-Allow-Headers"))
                .as("no Access-Control-Request-Headers sent → the default trio")
                .contains("Authorization, Content-Type, X-Requested-With");
        assertThat(r.headers().firstValue("Access-Control-Max-Age")).contains("600");
        assertThat(ROUTE_HITS.get()).as("the route handler must not run for a preflight").isEqualTo(before);
    }

    @Test
    void preflightEchoesTheRequestedHeadersVerbatim() {
        String o = origin("preflight-headers");
        addOrigin(o);

        var r = http.send("OPTIONS", "/probe", null,
                "Origin", o, "Access-Control-Request-Method", "POST",
                "Access-Control-Request-Headers", "x-custom-one, content-type");

        assertThat(r.statusCode()).isEqualTo(204);
        assertThat(r.headers().firstValue("Access-Control-Allow-Headers")).contains("x-custom-one, content-type");
    }

    @Test
    void preflightForADisallowedOriginAnswers403EnvelopeAndNeverInvokesTheRouteHandler() {
        String o = origin("preflight-denied-" + UUID.randomUUID());
        int before = ROUTE_HITS.get();

        var r = preflight("/probe", o);

        assertThat(r.statusCode()).isEqualTo(403);
        assertThat(r.body()).contains("CORS_ORIGIN_NOT_ALLOWED");
        assertThat(r.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
        assertThat(ROUTE_HITS.get()).as("the route handler must not run for a denied preflight either").isEqualTo(before);
    }

    // ── Wildcard, end to end ───────────────────────────────────────────────

    /// The end-to-end counterpart of `CorsAllowlistTest`'s apex-vs-subdomain
    /// case (mutant: wildcard regex allowing zero labels so the apex
    /// matches), through the real filter and a real stored row.
    @Test
    void wildcardAllowlistEntryMatchesASubdomainButNotItsOwnApex() {
        String base = "wild-" + RUN + ".example.com";
        addOrigin("https://*." + base);

        var sub = getWithOrigin("/probe", "https://sub." + base);
        assertThat(sub.headers().firstValue("Access-Control-Allow-Origin")).as("subdomain is covered by the wildcard").contains("https://sub." + base);

        var apex = getWithOrigin("/probe", "https://" + base);
        assertThat(apex.headers().firstValue("Access-Control-Allow-Origin")).as("the apex itself is not a subdomain").isEmpty();
    }

    // ── Vary ───────────────────────────────────────────────────────────────

    /// A dedicated harness where an earlier `before` handler sets `Vary`
    /// before [CorsFilter] runs, pinning "appended, not replaced".
    @Test
    void varyOriginIsAppendedToAnExistingVaryHeaderRatherThanReplacingIt() {
        try (var varyHttp = new TestHttp(cfg -> {
            HttpError.install(cfg.routes);
            cfg.routes.before("/vary-probe", ctx -> ctx.header("Vary", "Accept-Encoding"));
            cfg.routes.before(new CorsFilter(ALWAYS_FRESH_ALLOWLIST));
            cfg.routes.get("/vary-probe", ctx -> ctx.result("ok"));
        })) {
            String o = origin("vary");
            addOrigin(o);

            var r = varyHttp.send("GET", "/vary-probe", null, "Origin", o);

            var vary = String.join(",", r.headers().allValues("Vary"));
            assertThat(vary).as("the pre-existing value must survive").contains("Accept-Encoding");
            assertThat(vary).as("Origin must be appended, not substituted for it").contains("Origin");
        }
    }

    // ── Cache invalidation ───────────────────────────────────────────────────

    /// A one-hour TTL allowlist that would NOT see a change on its own for an
    /// hour; [CorsOriginApi]'s `onChange` must invalidate it so the very next
    /// request after the POST already carries the headers (mutant: dropping
    /// the `onChange` call — this test would then only pass after an hour,
    /// i.e. never, inside the suite's runtime).
    @Test
    void addingAnOriginInvalidatesTheCacheSoTheVeryNextRequestSeesIt() throws Exception {
        var repo = new CorsOriginRepository(TestPg.dataSource());
        var uow = new UnitOfWork(TestPg.dataSource(), new PlatformSink(Json.MAPPER));
        var longTtlAllowlist = new CorsAllowlist(repo::allowedOrigins, Duration.ofHours(1), Clock.systemUTC());

        var keys = SigningKeys.generateEphemeral();
        var verifier = new JwtVerifier(new JwtVerifier.Config("http://localhost:8080", new JwtVerifier.RsaKeys(keys.publicKey())));
        var auth = new Authenticator(verifier, ClaimsResolver.none(), Authenticator.Config.of(true));

        try (var invalidationHttp = new TestHttp(cfg -> {
            HttpError.install(cfg.routes);
            cfg.routes.before(new CorsFilter(longTtlAllowlist));
            cfg.routes.before("/api/*", auth);
            CorsOriginApi.register(cfg.routes, new CorsOriginApi.State(repo, uow, longTtlAllowlist::invalidate));
            cfg.routes.get("/probe", ctx -> ctx.result("ok"));
        })) {
            String o = origin("invalidation");

            // Before the add: the hour-long TTL allowlist has never heard of it.
            var before = invalidationHttp.send("GET", "/probe", null, "Origin", o);
            assertThat(before.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();

            var add = invalidationHttp.post("/api/platform/cors", "{\"origin\":\"" + o + "\"}", ANCHOR);
            assertThat(add.statusCode()).as(add.body()).isEqualTo(201);

            // The very next request — no sleeping, no waiting out the TTL.
            var after = invalidationHttp.send("GET", "/probe", null, "Origin", o);
            assertThat(after.headers().firstValue("Access-Control-Allow-Origin"))
                    .as("onChange must have invalidated the one-hour cache")
                    .contains(o);
        }
    }
}
