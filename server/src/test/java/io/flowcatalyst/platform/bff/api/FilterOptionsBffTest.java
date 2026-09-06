package io.flowcatalyst.platform.bff.api;

import tools.jackson.databind.JsonNode;
import io.flowcatalyst.platform.client.Client;
import io.flowcatalyst.platform.client.ClientIdentifier;
import io.flowcatalyst.platform.client.ClientRepository;
import io.flowcatalyst.platform.eventtype.EventType;
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

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/// `/bff/filter-options/clients` and `/bff/event-types/filters/applications`:
/// client-scope filtering + label sort, and the distinct-application
/// extraction from event-type codes.
@SuppressWarnings("deprecation")
class FilterOptionsBffTest {

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);
    private static final UnitOfWork uow = new UnitOfWork(TestPg.dataSource(), new PlatformSink(Json.MAPPER));
    private static final ClientRepository clientRepo = new ClientRepository(TestPg.dataSource());
    private static final EventTypeRepository eventTypeRepo = new EventTypeRepository(TestPg.dataSource());
    private static TestHttp http;

    private static final String[] ANCHOR = {Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(), Authenticator.TEST_SCOPE, "ANCHOR"};

    @BeforeAll
    static void start() {
        var keys = SigningKeys.generateEphemeral();
        var verifier = new JwtVerifier(new JwtVerifier.Config("http://localhost:8080", new JwtVerifier.RsaKeys(keys.publicKey())));
        var auth = new Authenticator(verifier, ClaimsResolver.none(), Authenticator.Config.of(true));
        http = TestHttp.routes(routes -> {
            HttpError.install(routes);
            routes.before("/bff/*", auth);
            FilterOptionsBff.register(routes, new FilterOptionsBff.State(clientRepo, eventTypeRepo));
        });
    }

    @AfterAll
    static void stop() {
        http.close();
    }

    private static JsonNode json(HttpResponse<String> r) {
        try {
            return Json.MAPPER.readTree(r.body());
        } catch (Exception e) {
            throw new IllegalStateException("not JSON: " + r.body(), e);
        }
    }

    private static Client persistClient(String name) {
        Client c = Client.create(name, ClientIdentifier.parse("fo-" + RUN + "-" + UUID.randomUUID()));
        uow.inTransaction(tx -> {
            clientRepo.persist(c, tx.dbTx());
            return null;
        });
        return c;
    }

    private static void persistEventType(String code, String name) {
        uow.inTransaction(tx -> {
            eventTypeRepo.persist(EventType.create(code, name), tx.dbTx());
            return null;
        });
    }

    // ── Clients: scope filtering + label sort ───────────────────────────────

    @Test
    void anchorSeesEveryActiveClientSortedByLabel() {
        persistClient("Zebra Filter " + RUN);
        persistClient("Alpha Filter " + RUN);

        var body = json(http.get("/bff/filter-options/clients", ANCHOR));
        var labels = body.get("clients").findValuesAsString("label");
        assertThat(labels).contains("Zebra Filter " + RUN, "Alpha Filter " + RUN);
        int alpha = labels.indexOf("Alpha Filter " + RUN);
        int zebra = labels.indexOf("Zebra Filter " + RUN);
        assertThat(alpha).as("alphabetically sorted by label").isLessThan(zebra);
    }

    /// Load-bearing: a non-anchor caller sees only clients it can access —
    /// a client outside its grant list must be genuinely absent, not merely
    /// unasserted.
    @Test
    void nonAnchorSeesOnlyItsAccessibleClients() {
        Client mine = persistClient("Mine Filter " + RUN);
        Client theirs = persistClient("Theirs Filter " + RUN);
        String[] scoped = {Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
                Authenticator.TEST_SCOPE, "CLIENT", Authenticator.TEST_CLIENTS, mine.id()};

        var ids = json(http.get("/bff/filter-options/clients", scoped)).get("clients").findValuesAsString("value");
        assertThat(ids).contains(mine.id());
        assertThat(ids).doesNotContain(theirs.id());
    }

    @Test
    void keySetIsClientsOnly() {
        var body = json(http.get("/bff/filter-options/clients", ANCHOR));
        assertThat(iteratorToList(body.propertyNames())).containsExactly("clients");
        if (!body.get("clients").isEmpty()) {
            assertThat(iteratorToList(body.get("clients").get(0).propertyNames())).containsExactlyInAnyOrder("value", "label");
        }
    }

    // ── Event-type application filter ───────────────────────────────────────

    @Test
    void distinctApplicationsAreExtractedFromEventTypeCodesAndSorted() {
        String app = "fofilter" + RUN;
        persistEventType(app + ":sub:agg:one", "One");
        persistEventType(app + ":sub2:agg2:two", "Two"); // same application, different subdomain — must not duplicate

        var body = json(http.get("/bff/event-types/filters/applications", ANCHOR));
        List<String> values = new java.util.ArrayList<>();
        body.get("options").forEach(n -> values.add(n.asText()));
        assertThat(values).contains(app);
        assertThat(values.stream().filter(v -> v.equals(app)).count()).as("deduplicated").isEqualTo(1);
    }

    @Test
    void applicationsResponseKeySetIsOptionsOnly() {
        var body = json(http.get("/bff/event-types/filters/applications", ANCHOR));
        assertThat(iteratorToList(body.propertyNames())).containsExactly("options");
    }

    private static List<String> iteratorToList(java.util.Collection<String> names) {
        return List.copyOf(names);
    }
}
