package io.flowcatalyst.platform.bff.api;

import io.flowcatalyst.platform.application.Application;
import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.platform.application.ApplicationType;
import io.flowcatalyst.platform.client.ClientRepository;
import io.flowcatalyst.platform.eventtype.EventTypeRepository;
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
import tools.jackson.databind.JsonNode;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/// The SPA's event-type reads make `GET /api/event-types`'s checks (bug 1,
/// `docs/go-mirror/2026-10-10-platform-bug-fixes.md`): the read permission,
/// client scoping and application confinement; and the SPA's repeated facet
/// parameters match any of their values (bug 2). Mirrors Go's
/// `TestBffEventTypeReads_AuthorizedScopedAndMultiValued`.
class EventTypeBffReadsTest {

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);
    private static final String A = "bffeta" + RUN;
    private static final String B = "bffetb" + RUN;
    private static final String C = "bffetc" + RUN;

    private static final String[] ANCHOR = {
            Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
            Authenticator.TEST_SCOPE, "ANCHOR",
            Authenticator.TEST_PERMISSIONS, "platform:*:*:*"};
    private static final String[] VIEWER = {
            Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
            Authenticator.TEST_SCOPE, "CLIENT",
            Authenticator.TEST_CLIENTS, "clt_bffmine" + RUN,
            Authenticator.TEST_PERMISSIONS, "platform:messaging:event-type:view"};
    private static final String[] NOBODY = {
            Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
            Authenticator.TEST_SCOPE, "ANCHOR",
            Authenticator.TEST_PERMISSIONS, "platform:messaging:event:view"};

    private static TestHttp http;
    private static String[] appServiceAccount;
    private static String foreign;

    @BeforeAll
    static void start() {
        var ds = TestPg.dataSource();
        var uow = new UnitOfWork(ds, new PlatformSink(Json.MAPPER));
        var apps = new ApplicationRepository(ds);
        var eventTypes = new EventTypeRepository(ds);
        var appA = Application.create(ApplicationType.INTEGRATION, A, "A");
        uow.inTransaction(tx -> {
            apps.persist(appA, tx.dbTx());
            apps.persist(Application.create(ApplicationType.INTEGRATION, B, "B"), tx.dbTx());
            apps.persist(Application.create(ApplicationType.INTEGRATION, C, "C"), tx.dbTx());
            return null;
        });
        appServiceAccount = new String[] {
                Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
                Authenticator.TEST_SCOPE, "ANCHOR",
                Authenticator.TEST_APPLICATIONS, appA.id(),
                Authenticator.TEST_PERMISSIONS, "platform:application-service:event-type:view"};

        var keys = SigningKeys.generateEphemeral();
        var verifier = new JwtVerifier(new JwtVerifier.Config("http://localhost:8080", new JwtVerifier.RsaKeys(keys.publicKey())));
        var auth = new Authenticator(verifier, ClaimsResolver.none(), Authenticator.Config.of(true));
        http = TestHttp.routes(routes -> {
            HttpError.install(routes);
            routes.before("/bff/*", auth);
            FilterOptionsBff.register(routes, new FilterOptionsBff.State(new ClientRepository(ds), eventTypes, apps));
            EventTypesBff.register(routes, new EventTypesBff.State(eventTypes, apps, uow));
        });

        create(A + ":orders:order:created");
        create(A + ":billing:invoice:sent");
        create(B + ":orders:order:created");
        foreign = create(C + ":orders:order:shipped");
    }

    @AfterAll
    static void stop() {
        http.close();
    }

    private static String create(String code) {
        var r = http.post("/bff/event-types", "{\"code\":\"" + code + "\",\"name\":\"" + code + "\"}", ANCHOR);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        return json(r).get("id").asString();
    }

    private static JsonNode json(HttpResponse<String> r) {
        try {
            return Json.MAPPER.readTree(r.body());
        } catch (Exception e) {
            throw new IllegalStateException("not JSON: " + r.body(), e);
        }
    }

    private static List<String> codes(HttpResponse<String> r) {
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        var out = new ArrayList<String>();
        json(r).get("items").forEach(n -> out.add(n.get("code").asString()));
        return out;
    }

    /// The options, restricted to this run's applications (the table is shared).
    private static List<String> options(HttpResponse<String> r) {
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        var out = new ArrayList<String>();
        json(r).get("options").forEach(n -> out.add(n.asString()));
        return out;
    }

    private static List<String> mine(List<String> values) {
        return values.stream().filter(v -> v.endsWith(RUN) || v.contains(RUN + ":")).toList();
    }

    @Test
    void withoutTheReadPermissionEveryReadIsRefused() {
        for (String path : List.of("/bff/event-types", "/bff/event-types/" + foreign,
                "/bff/event-types/filters/applications", "/bff/event-types/filters/subdomains",
                "/bff/event-types/filters/aggregates")) {
            var r = http.get(path, NOBODY);
            assertThat(r.statusCode()).as(path + ": " + r.body()).isEqualTo(403);
        }
    }

    @Test
    void anApplicationServiceAccountSeesOnlyItsOwnApplications() {
        assertThat(http.get("/bff/event-types/" + foreign, appServiceAccount).statusCode()).isEqualTo(403);
        assertThat(mine(codes(http.get("/bff/event-types", appServiceAccount))))
                .containsExactlyInAnyOrder(A + ":orders:order:created", A + ":billing:invoice:sent");
        assertThat(options(http.get("/bff/event-types/filters/applications", appServiceAccount))).containsExactly(A);
    }

    @Test
    void repeatedFacetsMatchAnyOfTheirValues() {
        assertThat(mine(codes(http.get("/bff/event-types?application=" + A + "&application=" + B, VIEWER))))
                .containsExactlyInAnyOrder(A + ":orders:order:created", A + ":billing:invoice:sent", B + ":orders:order:created");
        assertThat(codes(http.get("/bff/event-types?application=" + A + "&application=" + B
                + "&subdomain=billing&subdomain=nothing", VIEWER)))
                .containsExactly(A + ":billing:invoice:sent");
        assertThat(options(http.get("/bff/event-types/filters/subdomains?application=" + A + "&application=" + B, VIEWER)))
                .containsExactly("billing", "orders");
        assertThat(options(http.get("/bff/event-types/filters/aggregates?application=" + A + "&application=" + B
                + "&subdomain=orders", VIEWER)))
                .containsExactly("order");
    }
}
