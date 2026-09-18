package io.flowcatalyst.platform.function;

import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.shared.platformsink.PlatformSink;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import io.flowcatalyst.testpg.TestPg;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/// `FunctionRepository` against the embedded Postgres (spec
/// `function-registry.md` §6.1, §8 M1, M3, M11, M16). The fixture never
/// truncates; every row is namespaced by a per-JVM run suffix or a fresh id.
class FunctionRepositoryTest {

    private static final DataSource DS = TestPg.dataSource();
    private static final FunctionRepository REPO = new FunctionRepository(DS);
    private static final FunctionVersionRepository VERSION_REPO = new FunctionVersionRepository(DS);
    private static final UnitOfWork UOW = new UnitOfWork(DS, new PlatformSink(Json.MAPPER));

    private static final FunctionLimits DEFAULTS = FunctionLimits.defaults();
    private static final ClientCeilings UNRESTRICTED = ClientCeilings.of(DEFAULTS);

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toLowerCase(Locale.ROOT);
    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime());

    private static final String MINIMAL_JVM = """
            {
              "runtime": "jvm",
              "entrypoint": "com.acme.billing.CreateInvoice",
              "triggers": [ { "type": "event", "eventType": "billing:invoices:invoice:created" } ]
            }
            """;

    private static Manifest manifest() {
        return Manifest.parseStrict(Json.MAPPER.readTree(MINIMAL_JVM), Runtime.JVM, DEFAULTS, UNRESTRICTED);
    }

    private static String fresh() {
        return "t" + Long.toString(SEQ.incrementAndGet(), 36);
    }

    private static DnsLabel randomAppCode() {
        return new DnsLabel("app-" + RUN + "-" + fresh());
    }

    private static Function persist(Function f) {
        UOW.inTransaction(tx -> {
            REPO.persist(f, tx.dbTx());
            return null;
        });
        return f;
    }

    private static FunctionVersion persist(FunctionVersion v) {
        UOW.inTransaction(tx -> {
            VERSION_REPO.persist(v, tx.dbTx());
            return null;
        });
        return v;
    }

    private static Digest freshDigest() {
        return Digest.parse("sha256:" + String.format("%064x", SEQ.incrementAndGet()));
    }

    private static FunctionVersion publishVersion(Function f, int version) {
        return persist(FunctionVersion.publish(f.id(), version, "oci://artifact:" + version,
                freshDigest(), null, null, null, manifest(), "prn_publisher", Instant.now()));
    }

    // ── basic round-trip ──────────────────────────────────────────────────

    @Test
    void createFindAndDescribeRoundTrip() {
        DnsLabel app = randomAppCode();
        FunctionAddress address = FunctionAddress.of(app, new DnsLabel("invoices"), new DnsLabel("create"));
        Function f = persist(Function.create(fresh(), address, fresh(), Runtime.JVM, "desc"));

        Function reloaded = REPO.findById(f.id()).orElseThrow();
        assertThat(reloaded.address()).isEqualTo(address);
        assertThat(reloaded.description()).isEqualTo("desc");
        assertThat(reloaded.status()).isEqualTo(FunctionStatus.ACTIVE);
        assertThat(REPO.findByAddress(address)).map(Function::id).contains(f.id());

        Function described = persist(reloaded.describe("updated", Instant.now()));
        assertThat(REPO.findById(f.id())).map(Function::description).contains("updated");
        assertThat(described.description()).isEqualTo("updated");
    }

    // ── §8 M1: fn_functions.service_name LABEL check agrees with DnsLabel.parse ──

    /// A fresh `(application_code, name)` pair per call — `fn_functions` has
    /// a unique constraint spanning `service_name`, and `TestPg` is never
    /// truncated, so a literal `application_code`/`name` here would collide
    /// with rows other tests (including `FunctionSchemaTest`) have already
    /// inserted under the same `service_name` values.
    private boolean dbAcceptsServiceName(String serviceName) {
        try (Connection c = DS.getConnection(); PreparedStatement ps = c.prepareStatement("""
                INSERT INTO fn_functions (id, application_id, application_code, service_name, name, client_id, runtime)
                VALUES (?, ?, ?, ?, ?, ?, 'JVM')""")) {
            ps.setString(1, fresh());
            ps.setString(2, fresh());
            ps.setString(3, "app-" + RUN + "-" + fresh());
            if (serviceName == null) {
                ps.setNull(4, Types.VARCHAR);
            } else {
                ps.setString(4, serviceName);
            }
            ps.setString(5, "fn-" + RUN + "-" + fresh());
            ps.setString(6, fresh());
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    private static boolean parserAcceptsLabel(String value) {
        try {
            DnsLabel.parse("service_name", value);
            return true;
        } catch (UseCaseException e) {
            return false;
        }
    }

    /// Spec §3.1's Accepted/Rejected table, driven against both the SQL
    /// LABEL check and [DnsLabel#parse] — the two must never disagree (§8 M1).
    @Test
    void serviceNameLabelCheckAgreesWithDnsLabelParseForEveryRuleRow() {
        record Row(String rule, String value) {
        }
        List<Row> rows = List.of(
                new Row("charset", "billing"), new Row("charset", "a"), new Row("charset", "0"),
                new Row("charset", "inv-2"), new Row("charset", "9lives"),
                new Row("charset", "Billing"), new Row("charset", "in_voices"), new Row("charset", "a.b"),
                new Row("charset", "a b"), new Row("charset", "é"),
                new Row("length", "a".repeat(63)), new Row("length", ""), new Row("length", "a".repeat(64)),
                new Row("hyphen edges", "a-b"), new Row("hyphen edges", "a--b"),
                new Row("hyphen edges", "-a"), new Row("hyphen edges", "a-"), new Row("hyphen edges", "-"),
                new Row("whitespace", " a"), new Row("whitespace", "a "));

        for (Row row : rows) {
            boolean db = dbAcceptsServiceName(row.value());
            boolean parser = parserAcceptsLabel(row.value());
            assertThat(db).as("[%s] '%s': DB accepted=%s, DnsLabel.parse accepted=%s", row.rule(), row.value(), db, parser)
                    .isEqualTo(parser);
        }
        assertThat(dbAcceptsServiceName(null)).as("absent").isEqualTo(parserAcceptsLabel(null));
    }

    // ── §8 M3: pattern matching is whole-segment, in list() too ──────────────

    @Test
    void listByPatternIsWholeSegmentAcrossServiceAndApplication() {
        DnsLabel app = randomAppCode();
        Function invoices = persist(Function.create(fresh(),
                FunctionAddress.of(app, new DnsLabel("invoices"), new DnsLabel("create")), fresh(), Runtime.JVM, null));
        Function invoicesV2 = persist(Function.create(fresh(),
                FunctionAddress.of(app, new DnsLabel("invoices-v2"), new DnsLabel("create")), fresh(), Runtime.JVM, null));

        List<Function> byService = REPO.list(new FunctionRepository.ListFilter(
                FunctionAddressPattern.parse(app.value() + ".invoices.*"), null, null));
        assertThat(byService).as("whole-segment: 'invoices.*' must not also match 'invoices-v2'")
                .extracting(Function::id).containsExactly(invoices.id());

        List<Function> byApp = REPO.list(new FunctionRepository.ListFilter(
                FunctionAddressPattern.parse(app.value() + ".*"), null, null));
        assertThat(byApp).extracting(Function::id).containsExactlyInAnyOrder(invoices.id(), invoicesV2.id());
    }

    // ── §8 M11: a function cannot move ───────────────────────────────────────

    @Test
    void persistNeverMovesAFunctionsAddressClientOrRuntime() {
        DnsLabel app = randomAppCode();
        FunctionAddress original = FunctionAddress.of(app, new DnsLabel("invoices"), new DnsLabel("create"));
        Function f = persist(Function.create(fresh(), original, "clt_original", Runtime.JVM, null));

        FunctionAddress moved = FunctionAddress.of(app, new DnsLabel("moved"), new DnsLabel("elsewhere"));
        Function corrupted = new Function(f.id(), "app_moved", moved, "clt_moved", Runtime.WASM,
                "attempted move", f.status(), f.aliases(), f.createdAt(), Instant.now());
        persist(corrupted);

        Function reloaded = REPO.findById(f.id()).orElseThrow();
        assertThat(reloaded.address()).as("address unchanged").isEqualTo(original);
        assertThat(reloaded.clientId()).as("client unchanged").isEqualTo("clt_original");
        assertThat(reloaded.runtime()).as("runtime unchanged").isEqualTo(Runtime.JVM);
        assertThat(reloaded.description()).as("description IS in the SET list, so it does change").isEqualTo("attempted move");
    }

    // ── §8 M16: alias replacement ─────────────────────────────────────────

    @Test
    void promoteTwiceLeavesExactlyOneLiveRowPointingAtTheLatestVersion() {
        DnsLabel app = randomAppCode();
        Function f = persist(Function.create(fresh(),
                FunctionAddress.of(app, new DnsLabel("invoices"), new DnsLabel("create")), fresh(), Runtime.JVM, null));
        FunctionVersion v1 = publishVersion(f, 1);
        FunctionVersion v2 = publishVersion(f, 2);

        Function afterFirst = persist(f.promote(Function.LIVE, v1, "prn_1", Instant.now()).function());
        assertThat(aliasRowCount(f.id())).isEqualTo(1);

        Function afterSecond = persist(afterFirst.promote(Function.LIVE, v2, "prn_2", Instant.now()).function());
        assertThat(aliasRowCount(f.id())).as("still exactly one live row").isEqualTo(1);

        Function reloaded = REPO.findById(f.id()).orElseThrow();
        assertThat(reloaded.liveVersionId()).contains(v2.id());
        assertThat(reloaded.aliases()).hasSize(1);
        assertThat(afterSecond.liveVersionId()).contains(v2.id());
    }

    @Test
    void persistDeletesAnAliasRemovedFromTheRecord() {
        DnsLabel app = randomAppCode();
        Function f = persist(Function.create(fresh(),
                FunctionAddress.of(app, new DnsLabel("invoices"), new DnsLabel("create")), fresh(), Runtime.JVM, null));
        FunctionVersion v1 = publishVersion(f, 1);
        FunctionVersion v2 = publishVersion(f, 2);
        Instant now = Instant.now();

        Function withTwoAliases = new Function(f.id(), f.applicationId(), f.address(), f.clientId(), f.runtime(),
                f.description(), f.status(),
                List.of(new Function.FunctionAlias("live", v1.id(), "prn_1", now),
                        new Function.FunctionAlias("canary", v2.id(), "prn_1", now)),
                f.createdAt(), now);
        persist(withTwoAliases);
        assertThat(aliasRowCount(f.id())).isEqualTo(2);

        Function withOneAlias = new Function(f.id(), f.applicationId(), f.address(), f.clientId(), f.runtime(),
                f.description(), f.status(), List.of(new Function.FunctionAlias("live", v1.id(), "prn_1", now)),
                f.createdAt(), Instant.now());
        persist(withOneAlias);
        assertThat(aliasRowCount(f.id())).as("'canary' was removed from the record and must be gone from the table").isEqualTo(1);
    }

    private static int aliasRowCount(String functionId) {
        try (Connection c = DS.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM fn_aliases WHERE function_id = ?")) {
            ps.setString(1, functionId);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
