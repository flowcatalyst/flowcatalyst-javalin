package io.flowcatalyst.platform.event;

import io.flowcatalyst.platform.event.Event.ContextEntry;
import io.flowcatalyst.platform.event.EventFixture.Payload;
import io.flowcatalyst.platform.event.EventRepository.Facet;
import io.flowcatalyst.platform.event.EventRepository.ListFilter;
import io.flowcatalyst.platform.shared.auth.Visibility;
import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.sdk.tsid.Tsid;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static io.flowcatalyst.db.generated.Tables.MSG_EVENTS;
import static io.flowcatalyst.platform.event.EventFixture.DB;
import static io.flowcatalyst.platform.event.EventFixture.DS;
import static io.flowcatalyst.platform.event.EventFixture.NOW;
import static io.flowcatalyst.platform.event.EventFixture.emit;
import static io.flowcatalyst.platform.event.EventFixture.insert;
import static io.flowcatalyst.platform.event.EventFixture.project;
import static io.flowcatalyst.platform.event.EventFixture.readRow;
import static io.flowcatalyst.platform.event.EventFixture.application;
import static io.flowcatalyst.platform.event.EventFixture.type;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The reads (spec §1, §3–7, §9): a sink row read whole from the write side,
/// the same row after the projection, every list filter, the SQL-side
/// visibility, the ordering, the guards, the facets and the foreign-shape
/// `data` text column. Write-side rows are seeded through a real unit of
/// work; read-side rows by the projection or directly with a chosen
/// `created_at` / `client_id`.
@SuppressWarnings("deprecation")
class EventRepositoryTest {

    private static final EventRepository repo = new EventRepository(DS);

    private static final String APP = application("repo");
    private static final String FOREIGN = application("foreign");
    private static final String ORDER_CREATED = type(APP, "orders", "order", "created");
    private static final String ORDER_SHIPPED = type(APP, "orders", "order", "shipped");
    private static final String INVOICE_ISSUED = type(APP, "billing", "invoice", "issued");
    private static final String CLIENT_A = "cli_" + EventFixture.RUN + "00000a";
    private static final String CLIENT_B = "cli_" + EventFixture.RUN + "00000b";

    private static final Instant T0 = NOW.minusSeconds(600);
    /// The anchor's view — every test that is not about scoping reads with it.
    private static final Visibility ALL = Visibility.Everything.INSTANCE;
    private static String principal;
    private static String entity;
    private static String sinkRow;      // emitted + projected, platform-scoped
    private static String sinkRowTwo;   // emitted + projected
    private static String platformRow;  // direct, created T0
    private static String rowA1;        // direct, client A, created T0 - 10s
    private static String rowA2;        // direct, client A, created T0 - 20s
    private static String rowB;         // direct, client B, created T0 - 30s

    @BeforeAll
    static void seedRows() {
        principal = EntityType.PRINCIPAL.generate();
        entity = EntityType.EVENT_TYPE.generate();
        sinkRow = emit(ORDER_CREATED, "platform.order." + entity, principal, NOW.minusSeconds(5), "corr-" + EventFixture.RUN, "grp-1", new Payload("first", 1));
        sinkRowTwo = emit(ORDER_SHIPPED, "platform.order." + entity, null, NOW.minusSeconds(4), null, null, new Payload("second", 2));
        assertThat(project(List.of(sinkRow, sinkRowTwo))).isEqualTo(2);

        var p = readRow(ORDER_CREATED, null, T0);
        p.setCorrelationId("corr-direct-" + EventFixture.RUN);
        platformRow = insert(p);
        var a1 = readRow(ORDER_SHIPPED, CLIENT_A, T0.minusSeconds(10));
        a1.setMessageGroup("grp-a");
        rowA1 = insert(a1);
        rowA2 = insert(readRow(INVOICE_ISSUED, CLIENT_A, T0.minusSeconds(20)));
        rowB = insert(readRow(INVOICE_ISSUED, CLIENT_B, T0.minusSeconds(30)));
    }

    /// Every row of this run: the `application` segment is the namespace.
    private static ListFilter inRun() {
        return inRun(ALL);
    }

    private static ListFilter inRun(Visibility v) {
        return new ListFilter(null, null, null, null, null, null, null, null, null, List.of(APP), null, null, v);
    }

    private static List<String> ids(List<Event> rows) {
        return rows.stream().map(Event::id).toList();
    }

    // ── The sink row, write side and projected ─────────────────────────────

    @Test
    void theRawReadReturnsTheSinkRowWholeWithItsContext() {
        var e = repo.findRecentRaw(1000).stream().filter(x -> x.id().equals(sinkRow)).findFirst().orElseThrow();
        assertThat(e.specVersion()).isEqualTo("1.0");
        assertThat(e.type()).isEqualTo(ORDER_CREATED);
        assertThat(e.source()).isEqualTo("platform:admin");
        assertThat(e.subject()).isEqualTo("platform.order." + entity);
        assertThat(e.time()).isEqualTo(NOW.minusSeconds(5));
        assertThat(e.data().get("note").asText()).isEqualTo("first");
        assertThat(e.data().get("n").asInt()).isEqualTo(1);
        assertThat(e.context()).containsExactly(new ContextEntry("principalId", principal), new ContextEntry("aggregateType", "Order"));
        assertThat(e.deduplicationId()).isEqualTo(ORDER_CREATED + "-" + sinkRow);
        assertThat(e.clientId()).isNull();
        assertThat(e.messageGroup()).isEqualTo("grp-1");
        assertThat(e.correlationId()).isEqualTo("corr-" + EventFixture.RUN);
        assertThat(e.causationId()).isNull();
        assertThat(e.projection()).as("the write side has no projection").isNull();
    }

    @Test
    void theRawReadIsNewestFirst() {
        var rows = repo.findRecentRaw(1000);
        assertThat(rows).extracting(Event::createdAt).isSortedAccordingTo((x, y) -> y.compareTo(x));
        assertThat(ids(rows)).contains(sinkRow, sinkRowTwo);
    }

    @Test
    void findByIdReadsTheProjectedSinkRowWithoutContext() {
        var e = repo.findById(sinkRow).orElseThrow();
        assertThat(e.specVersion()).isEqualTo("1.0");
        assertThat(e.type()).isEqualTo(ORDER_CREATED);
        assertThat(e.subject()).isEqualTo("platform.order." + entity);
        assertThat(e.time()).isEqualTo(NOW.minusSeconds(5));
        assertThat(e.data().get("note").asText()).isEqualTo("first");
        assertThat(e.context()).as("the projection drops context_data").isEmpty();
        assertThat(e.deduplicationId()).isEqualTo(ORDER_CREATED + "-" + sinkRow);
        assertThat(e.messageGroup()).isEqualTo("grp-1");
        assertThat(e.correlationId()).isEqualTo("corr-" + EventFixture.RUN);
        var p = e.projection();
        assertThat(p.application()).isEqualTo(APP);
        assertThat(p.subdomain()).isEqualTo("orders");
        assertThat(p.aggregate()).isEqualTo("order");
        assertThat(p.projectedAt()).isAfterOrEqualTo(e.createdAt());
        // the projection preserves the source created_at (the partition key)
        var sourceCreated = DB.select(MSG_EVENTS.CREATED_AT).from(MSG_EVENTS).where(MSG_EVENTS.ID.eq(sinkRow)).fetchOne(MSG_EVENTS.CREATED_AT);
        assertThat(e.createdAt()).isEqualTo(sourceCreated.toInstant());
        assertThat(DB.select(MSG_EVENTS.PROJECTED_AT).from(MSG_EVENTS).where(MSG_EVENTS.ID.eq(sinkRow)).fetchOne(MSG_EVENTS.PROJECTED_AT)).isNotNull();
    }

    @Test
    void findByIdUnknownIsEmpty() {
        assertThat(repo.findById(Tsid.generate())).isEmpty();
    }

    // ── Filtered read ──────────────────────────────────────────────────────

    @Test
    void filteredReadIsNewestFirstByCreatedAtWithLimitAndOffset() {
        var all = repo.findWithFilters(inRun(), 1000, 0);
        assertThat(ids(all)).containsExactly(sinkRowTwo, sinkRow, platformRow, rowA1, rowA2, rowB);
        assertThat(ids(repo.findWithFilters(inRun(), 2, 0))).containsExactly(sinkRowTwo, sinkRow);
        assertThat(ids(repo.findWithFilters(inRun(), 2, 2))).containsExactly(platformRow, rowA1);
        assertThat(ids(repo.findWithFilters(inRun(), 2, -5))).as("a negative offset is none").containsExactly(sinkRowTwo, sinkRow);
    }

    @Test
    void equalityFiltersNarrowOnTheirColumn() {
        assertThat(ids(repo.findWithFilters(new ListFilter(ORDER_CREATED, null, null, null, null, null, null, null, null, List.of(APP), null, null, ALL), 100, 0)))
                .containsExactly(sinkRow, platformRow);
        assertThat(ids(repo.findWithFilters(new ListFilter(null, "platform:admin", null, null, null, null, null, null, null, List.of(APP), null, null, ALL), 100, 0)))
                .containsExactly(sinkRowTwo, sinkRow);
        assertThat(ids(repo.findWithFilters(new ListFilter(null, null, "platform.order." + entity, null, null, null, null, null, null, List.of(APP), null, null, ALL), 100, 0)))
                .containsExactly(sinkRowTwo, sinkRow);
        assertThat(ids(repo.findWithFilters(new ListFilter(null, null, null, CLIENT_A, null, null, null, null, null, List.of(APP), null, null, ALL), 100, 0)))
                .containsExactly(rowA1, rowA2);
        assertThat(ids(repo.findWithFilters(new ListFilter(null, null, null, null, "corr-direct-" + EventFixture.RUN, null, null, null, null, List.of(APP), null, null, ALL), 100, 0)))
                .containsExactly(platformRow);
    }

    @Test
    void listFiltersAreInListsAndTypeAndTypesBothApply() {
        assertThat(ids(repo.findWithFilters(new ListFilter(null, null, null, null, null, null, null, List.of(ORDER_SHIPPED, INVOICE_ISSUED), null, List.of(APP), null, null, ALL), 100, 0)))
                .containsExactly(sinkRowTwo, rowA1, rowA2, rowB);
        assertThat(ids(repo.findWithFilters(new ListFilter(ORDER_CREATED, null, null, null, null, null, null, List.of(ORDER_SHIPPED), null, List.of(APP), null, null, ALL), 100, 0)))
                .as("type AND types").isEmpty();
        assertThat(ids(repo.findWithFilters(new ListFilter(null, null, null, null, null, null, null, null, List.of(CLIENT_A, CLIENT_B), List.of(APP), null, null, ALL), 100, 0)))
                .containsExactly(rowA1, rowA2, rowB);
        assertThat(ids(repo.findWithFilters(new ListFilter(null, null, null, null, null, null, null, null, null, List.of(APP), List.of("billing"), null, ALL), 100, 0)))
                .containsExactly(rowA2, rowB);
        assertThat(ids(repo.findWithFilters(new ListFilter(null, null, null, null, null, null, null, null, null, List.of(APP), null, List.of("order"), ALL), 100, 0)))
                .containsExactly(sinkRowTwo, sinkRow, platformRow, rowA1);
        assertThat(repo.findWithFilters(new ListFilter(null, null, null, null, null, null, null, null, null, List.of("app_none"), null, null, ALL), 100, 0)).isEmpty();
    }

    @Test
    void sinceAndUntilAreInclusiveBoundsOnCreatedAt() {
        var window = new ListFilter(null, null, null, null, null, T0.minusSeconds(20), T0.minusSeconds(10), null, null, List.of(APP), null, null, ALL);
        assertThat(ids(repo.findWithFilters(window, 100, 0))).containsExactly(rowA1, rowA2);
        var since = new ListFilter(null, null, null, null, null, T0, null, null, null, List.of(APP), null, null, ALL);
        assertThat(ids(repo.findWithFilters(since, 100, 0))).containsExactly(sinkRowTwo, sinkRow, platformRow);
        var until = new ListFilter(null, null, null, null, null, null, T0.minusSeconds(30), null, null, List.of(APP), null, null, ALL);
        assertThat(ids(repo.findWithFilters(until, 100, 0))).containsExactly(rowB);
    }

    // ── Visibility (spec §8) ───────────────────────────────────────────────

    @Test
    void visibilityIsEnforcedInSqlAndIntersectsTheCallersClientFilters() {
        // anchor: everything
        assertThat(ids(repo.findWithFilters(inRun(Visibility.Everything.INSTANCE), 100, 0))).hasSize(6);
        // tenant A: own rows + platform-scoped, never B
        var tenantA = new Visibility.Tenants(List.of(CLIENT_A));
        assertThat(ids(repo.findWithFilters(inRun(tenantA), 100, 0))).containsExactly(sinkRowTwo, sinkRow, platformRow, rowA1, rowA2);
        // the attack shape: tenant A asks for B's rows — the intersection is empty
        assertThat(repo.findWithFilters(new ListFilter(null, null, null, null, null, null, null, null, List.of(CLIENT_B), List.of(APP), null, null, tenantA), 100, 0))
                .as("cross-tenant filter must not leak another tenant's events").isEmpty();
        assertThat(repo.findWithFilters(new ListFilter(null, null, null, CLIENT_B, null, null, null, null, null, List.of(APP), null, null, tenantA), 100, 0)).isEmpty();
        // asking for both narrows to the accessible one
        assertThat(ids(repo.findWithFilters(new ListFilter(null, null, null, null, null, null, null, null, List.of(CLIENT_A, CLIENT_B), List.of(APP), null, null, tenantA), 100, 0)))
                .containsExactly(rowA1, rowA2);
        // no clients at all: platform-scoped rows only
        assertThat(ids(repo.findWithFilters(inRun(new Visibility.Tenants(List.of())), 100, 0))).containsExactly(sinkRowTwo, sinkRow, platformRow);
    }

    @Test
    void aFilterMustStateWhoseViewItIs() {
        assertThatThrownBy(() -> new ListFilter(null, null, null, null, null, null, null, null, null, null, null, null, null))
                .as("visibility never defaults open").isInstanceOf(NullPointerException.class).hasMessage("visibility");
        assertThat(ListFilter.none().visibility()).isSameAs(Visibility.Everything.INSTANCE);
    }

    // ── Guards ─────────────────────────────────────────────────────────────

    @Test
    void outOfRangeLimitsFallBackInsteadOfFailing() {
        assertThat(repo.findWithFilters(inRun(), 0, 0)).hasSize(6);
        assertThat(repo.findWithFilters(inRun(), 1001, 0)).hasSize(6);
        assertThat(repo.findRecentRaw(0)).hasSizeLessThanOrEqualTo(EventRepository.LIST_DEFAULT_LIMIT).isNotEmpty();
        assertThat(repo.distinctValues(Facet.APPLICATION, 0)).contains(APP);
        assertThat(repo.distinctValues(Facet.APPLICATION, 5000)).contains(APP);
    }

    // ── Facets ─────────────────────────────────────────────────────────────

    @Test
    void facetsAreDistinctNonNullAscending() {
        assertThat(repo.distinctValues(Facet.APPLICATION, 1000)).contains(APP).doesNotHaveDuplicates().doesNotContainNull().isSorted();
        assertThat(repo.distinctValues(Facet.SUBDOMAIN, 1000)).contains("orders", "billing").doesNotHaveDuplicates().isSorted();
        assertThat(repo.distinctValues(Facet.AGGREGATE, 1000)).contains("order", "invoice").doesNotHaveDuplicates().isSorted();
        assertThat(repo.distinctValues(Facet.TYPE, 1000)).contains(ORDER_CREATED, ORDER_SHIPPED, INVOICE_ISSUED).doesNotHaveDuplicates().isSorted();
        assertThat(repo.distinctValues(Facet.CLIENT_ID, 1000)).contains(CLIENT_A, CLIENT_B).doesNotContainNull().isSorted();
        assertThat(repo.distinctValues(Facet.SOURCE, 1000)).contains("platform:admin", "test://" + APP).isSorted();
        assertThat(repo.distinctValues(Facet.SUBJECT, 1000)).contains("platform.order." + entity).doesNotContainNull();
        assertThat(repo.distinctValues(Facet.CORRELATION_ID, 1000)).contains("corr-" + EventFixture.RUN, "corr-direct-" + EventFixture.RUN).doesNotContainNull();
    }

    // ── Foreign shapes: pin the read ───────────────────────────────────────

    /// `msg_events_read.data` is text another writer filled; `spec_version`,
    /// `subject` and `deduplication_id` are nullable; a type with fewer than
    /// three segments projects `NULL` subdomain / aggregate (spec §9).
    @Test
    void foreignShapedReadRowsStillRead() {
        var nullData = readRow(type(FOREIGN, "x", "y", "z"), null, T0.minusSeconds(100));
        nullData.setData(null);
        nullData.setSpecVersion(null);
        nullData.setSubject(null);
        nullData.setDeduplicationId(null);
        var e1 = repo.findById(insert(nullData)).orElseThrow();
        assertThat(e1.data()).isNull();
        assertThat(e1.specVersion()).isNull();
        assertThat(e1.subject()).isNull();
        assertThat(e1.deduplicationId()).isNull();

        var empty = readRow(type(FOREIGN, "x", "y", "z"), null, T0.minusSeconds(100));
        empty.setData("");
        assertThat(repo.findById(insert(empty)).orElseThrow().data()).isNull();

        var literalNull = readRow(type(FOREIGN, "x", "y", "z"), null, T0.minusSeconds(100));
        literalNull.setData("null");
        assertThat(repo.findById(insert(literalNull)).orElseThrow().data()).isNull();

        var loose = readRow(type(FOREIGN, "x", "y", "z"), null, T0.minusSeconds(100));
        loose.setData("{ \"a\" : [1, 2],\n \"b\" : \"x\" }");
        var doc = repo.findById(insert(loose)).orElseThrow().data();
        assertThat(doc.get("a")).hasSize(2);
        assertThat(doc.get("b").asText()).isEqualTo("x");

        var array = readRow(type(FOREIGN, "x", "y", "z"), null, T0.minusSeconds(100));
        array.setData("[1, 2, 3]");
        assertThat(repo.findById(insert(array)).orElseThrow().data().isArray()).isTrue();

        var shortType = readRow(FOREIGN + ":only", null, T0.minusSeconds(100));
        shortType.setSubdomain("only");
        shortType.setAggregate(null);
        var p = repo.findById(insert(shortType)).orElseThrow().projection();
        assertThat(p.application()).isEqualTo(FOREIGN);
        assertThat(p.subdomain()).isEqualTo("only");
        assertThat(p.aggregate()).isNull();
    }
}
