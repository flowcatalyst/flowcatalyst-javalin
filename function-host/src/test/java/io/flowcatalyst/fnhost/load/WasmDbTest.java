package io.flowcatalyst.fnhost.load;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.flowcatalyst.fnhost.context.AllowlistHttpCaller;
import io.flowcatalyst.fnhost.context.ContextFactory;
import io.flowcatalyst.fnhost.context.DbPools;
import io.flowcatalyst.fnhost.context.InvocationDeadline;
import io.flowcatalyst.fnhost.reconcile.DesiredDocument;
import io.flowcatalyst.fnhost.reconcile.FakeControlPlane;
import io.flowcatalyst.fnhost.wasm.WasmFixtures;
import io.flowcatalyst.function.Caller;
import io.flowcatalyst.function.FunctionContext;
import io.flowcatalyst.function.Request;
import io.flowcatalyst.function.Result;
import io.flowcatalyst.platform.function.Digest;
import io.flowcatalyst.platform.function.FunctionAddress;
import io.flowcatalyst.platform.function.Manifest;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.testpg.TestPg;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/// Database access for Wasm functions (`docs/spec/function-wasm-db.md`, tests
/// 1–5): the committed Rust guest's `db` export runs a script of `fc_db_*` calls
/// against this class's own test database, through the real loader, the real
/// [io.flowcatalyst.fnhost.context.HostFunctionContext] and the real
/// [DbPools] a JVM function would get. "The connection is returned" is Hikari's
/// own active-connection count ([DbPools#activeConnectionsForTest]) — observed
/// moving up inside the call, and back afterwards.
class WasmDbTest {

    private static final FunctionAddress ADDRESS = FunctionAddress.parse("wasm.svc.db");
    private static final String SECRET = "MAIN_DSN";

    private static final DbPools POOLS = new DbPools(4);
    private static final FakeControlPlane CONTROL_PLANE = new FakeControlPlane();
    private static String dsn;

    @BeforeAll
    static void schema() throws SQLException {
        sql("CREATE TABLE wasm_db_items (id bigint PRIMARY KEY, name text NOT NULL)");
        sql("CREATE TABLE wasm_db_typed (id bigint PRIMARY KEY, at timestamptz NOT NULL, doc jsonb NOT NULL, "
                + "ref uuid NOT NULL)");
        // Embedded Postgres trusts local connections: the password is never checked.
        dsn = "postgresql://postgres:unused@localhost:" + TestPg.instance().getPort() + "/" + TestPg.databaseName();
    }

    @AfterAll
    static void closePools() {
        POOLS.close();
    }

    // ── test 1: query and execute through the real pools; params bound ────

    @Test
    void queryAndExecuteRunThroughTheRealPoolAndAParameterIsDataNeverSql(@TempDir Path dir) throws Exception {
        String hostile = "x'); DROP TABLE wasm_db_items; --";
        try (Fn fn = load(dir, 16)) {
            JsonNode results = fn.run(script(
                    step("execute", in("INSERT INTO wasm_db_items (id, name) VALUES (?, ?)", 101, hostile)),
                    step("query", in("SELECT id, name FROM wasm_db_items WHERE id = ?", 101))));

            assertThat(results.get(0).path("updated").asLong()).as(results.toString()).isEqualTo(1);
            JsonNode rows = results.get(1).path("rows");
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).path("name").asString())
                    .as("mutant: interpolate the parameter into the SQL — the quote ends the literal")
                    .isEqualTo(hostile);
            assertThat(rows.get(0).path("id").isIntegralNumber()).isTrue();
            assertThat(rows.get(0).path("id").asLong()).isEqualTo(101);
            assertThat(count("SELECT count(*) FROM wasm_db_items WHERE id = 101"))
                    .as("the table is still there and holds the row, committed (autocommit)").isEqualTo(1);
            assertThat(POOLS.activeConnectionsForTest(dsn))
                    .as("mutant: an autocommit statement keeps its borrow — every one is back").isZero();
        }
    }

    @Test
    void aStringParameterTakesTheColumnsTypeAndADecimalOneStaysExact(@TempDir Path dir) throws Exception {
        try (Fn fn = load(dir, 16)) {
            JsonNode results = fn.run(script(
                    step("execute", in("INSERT INTO wasm_db_typed (id, at, doc, ref) VALUES (?, ?, ?, ?)", 1,
                            "2026-09-24T10:15:30Z", "{\"k\":[1,2]}", "7f3d9a52-1c1e-4c1b-9d7e-2b1f0c6a4e11")),
                    step("query", in("SELECT at, doc, ref FROM wasm_db_typed WHERE id = ?", 1)),
                    step("query", in("SELECT ?::numeric AS v", Json.MAPPER.readTree("0.1")))));

            assertThat(results.get(0).path("updated").asLong())
                    .as("mutant: bind strings as varchar — timestamptz/jsonb/uuid columns refuse them: "
                            + results.get(0))
                    .isEqualTo(1);
            JsonNode row = results.get(1).path("rows").get(0);
            assertThat(row.path("at").asString()).isEqualTo("2026-09-24T10:15:30Z");
            assertThat(row.path("doc").isObject()).as("jsonb comes back parsed").isTrue();
            assertThat(row.path("doc").path("k").get(1).asInt()).isEqualTo(2);
            assertThat(row.path("ref").asString()).isEqualTo("7f3d9a52-1c1e-4c1b-9d7e-2b1f0c6a4e11");
            assertThat(results.get(2).path("rows").get(0).path("v").asString())
                    .as("a decimal reaches the server as numeric, not 0.1000000000000000055…; digits past a "
                            + "double's are pinned in DbSessionTest (this guest's serde_json reads f64)")
                    .isEqualTo("0.1");
        }
    }

    @Test
    void everyMappedTypeBecomesItsJsonShape(@TempDir Path dir) throws Exception {
        try (Fn fn = load(dir, 16)) {
            JsonNode row = fn.run(script(step("query", in("SELECT 7::int4 AS i, 9007199254740993::int8 AS big, "
                    + "12.50::numeric AS n, 1.5::float8 AS f, 'NaN'::float8 AS nan, true AS b, 'hé'::text AS t, "
                    + "timestamptz '2026-09-24 10:15:00+02' AS ts, timestamp '2026-09-24 10:15:00' AS lts, "
                    + "date '2026-09-24' AS d, '\\x0102ff'::bytea AS bin, '[1,{\"a\":null}]'::json AS j, "
                    + "NULL::int4 AS nul, ARRAY[1,2]::int4[] AS arr")))).get(0).path("rows").get(0);

            assertThat(row.path("i").isIntegralNumber()).isTrue();
            assertThat(row.path("i").asInt()).isEqualTo(7);
            assertThat(row.path("big").asLong()).as("int8 past 2^53 is still exact").isEqualTo(9007199254740993L);
            assertThat(row.path("n").isString()).as("mutant: numeric as a double").isTrue();
            assertThat(row.path("n").asString()).isEqualTo("12.50");
            assertThat(row.path("f").isNumber()).isTrue();
            assertThat(row.path("f").asDouble()).isEqualTo(1.5);
            assertThat(row.path("nan").asString()).isEqualTo("NaN");
            assertThat(row.path("b").isBoolean()).isTrue();
            assertThat(row.path("b").asBoolean()).isTrue();
            assertThat(row.path("t").asString()).isEqualTo("hé");
            assertThat(row.path("ts").asString()).isEqualTo("2026-09-24T08:15:00Z");
            assertThat(row.path("lts").asString()).isEqualTo("2026-09-24T10:15:00");
            assertThat(row.path("d").asString()).isEqualTo("2026-09-24");
            assertThat(row.path("bin").asString()).as("mutant: bytea as text (\\x0102ff)").isEqualTo("AQL/");
            assertThat(row.path("j").isArray()).as("mutant: json as a string").isTrue();
            assertThat(row.path("j").get(1).path("a").isNull()).isTrue();
            assertThat(row.has("nul")).isTrue();
            assertThat(row.path("nul").isNull()).isTrue();
            assertThat(row.path("arr").asString()).as("other types: PostgreSQL's text form").isEqualTo("{1,2}");
        }
    }

    @Test
    void sqlErrorsAreValuesByClassAndNeitherTheSqlNorAParameterIsLogged(@TempDir Path dir) throws Exception {
        String sqlMarker = "wasm_db_sql_marker_4c1e";
        String paramMarker = "wasm-db-param-marker-9a52";
        ListAppender<ILoggingEvent> appender = attachRootAppender();
        try (Fn fn = load(dir, 16)) {
            JsonNode results = fn.run(script(
                    step("execute", in("INSERT INTO wasm_db_items (id, name) VALUES (?, ?)", 201, paramMarker)),
                    step("execute", in("INSERT INTO wasm_db_items (id, name) VALUES (?, ?)", 201, paramMarker)),
                    step("query", in("SELEC " + sqlMarker + " FROM nowhere WHERE x = ?", paramMarker)),
                    step("query", in("SELECT 1 / ?", 0))));

            assertThat(code(results.get(1))).as("duplicate key: class 23").isEqualTo("DB_CONSTRAINT");
            assertThat(code(results.get(2))).as("syntax: class 42").isEqualTo("DB_SYNTAX");
            assertThat(code(results.get(3))).as("division by zero: class 22").isEqualTo("DB_ERROR");
            assertThat(results.get(2).path("error").path("message").asString()).isNotBlank();
        } finally {
            detachRootAppender(appender);
        }
        for (ILoggingEvent event : appender.list) {
            String line = event.getFormattedMessage() + " " + event.getKeyValuePairs();
            assertThat(line).as("mutant: log the failed statement").doesNotContain(sqlMarker)
                    .doesNotContain(paramMarker);
        }
    }

    // ── test 2: commit is visible; a transaction left open is rolled back ──

    @Test
    void aCommittedTransactionIsVisibleAfterwardsAndItsConnectionIsBack(@TempDir Path dir) throws Exception {
        try (Fn fn = load(dir, 16)) {
            JsonNode results = fn.run(script(
                    step("begin", db()),
                    step("execute", tx(in("INSERT INTO wasm_db_items (id, name) VALUES (?, ?)", 301, "committed"))),
                    step("query", tx(in("SELECT name FROM wasm_db_items WHERE id = ?", 301))),
                    step("commit", txOnly())));

            assertThat(results.get(0).path("tx").asString()).as(results.toString()).isNotBlank();
            assertThat(results.get(2).path("rows").get(0).path("name").asString())
                    .as("the transaction sees its own write").isEqualTo("committed");
            assertThat(results.get(3).path("ok").asBoolean()).as(results.toString()).isTrue();
            assertThat(count("SELECT count(*) FROM wasm_db_items WHERE id = 301"))
                    .as("mutant: commit rolls back").isEqualTo(1);
            assertThat(POOLS.activeConnectionsForTest(dsn)).isZero();
        }
    }

    /// Every way a call can end with a transaction still open: a normal return,
    /// a trap, Extism's error code. The row the transaction wrote is gone and
    /// the connection it held is back in the pool.
    @ParameterizedTest(name = "then={0}")
    @ValueSource(strings = {"return", "trap", "fail"})
    void aTransactionLeftOpenWhenTheCallEndsIsRolledBackAndItsConnectionReturned(String then, @TempDir Path dir)
            throws Exception {
        long id = switch (then) {
            case "return" -> 401;
            case "trap" -> 402;
            default -> 403;
        };
        List<Integer> activeDuringCall = new ArrayList<>();
        CONTROL_PLANE.emitDoes(request -> activeDuringCall.add(POOLS.activeConnectionsForTest(dsn)));
        try (Fn fn = load(dir, 16)) {
            int before = POOLS.activeConnectionsForTest(dsn);
            Result result = fn.invoke(script(then,
                    step("begin", db()),
                    step("execute", tx(in("INSERT INTO wasm_db_items (id, name) VALUES (?, ?)", id, "left open"))),
                    step("emit", Json.MAPPER.createObjectNode())));

            assertThat(result.status()).isEqualTo("return".equals(then) ? 200 : 500);
            assertThat(activeDuringCall).as("the transaction held a connection during the call")
                    .containsExactly(before + 1);
            assertThat(POOLS.activeConnectionsForTest(dsn))
                    .as("mutant: skip the end-of-call release — the connection stays borrowed")
                    .isEqualTo(before);
            assertThat(count("SELECT count(*) FROM wasm_db_items WHERE id = " + id))
                    .as("the open transaction was rolled back, not committed").isZero();
            assertThat(fn.invoke(script("return", step("query", in("SELECT 1 AS one")))).status())
                    .as("the version keeps serving").isEqualTo(200);
        } finally {
            CONTROL_PLANE.emitDoes(request -> {
            });
        }
    }

    /// The deadline path: the call's thread is interrupted while the guest spins
    /// with a transaction open (what the listener does at the deadline).
    @Test
    void aTransactionOpenAtTheDeadlineInterruptIsRolledBackAndItsConnectionReturned(@TempDir Path dir)
            throws Exception {
        CountDownLatch opened = new CountDownLatch(1);
        CONTROL_PLANE.emitDoes(request -> opened.countDown());
        try (Fn fn = load(dir, 16)) {
            int before = POOLS.activeConnectionsForTest(dsn);
            CompletableFuture<Throwable> outcome = new CompletableFuture<>();
            Request request = script("spin",
                    step("begin", db()),
                    step("execute", tx(in("INSERT INTO wasm_db_items (id, name) VALUES (?, ?)", 501, "spun"))),
                    step("emit", Json.MAPPER.createObjectNode()));
            Thread worker = Thread.ofVirtual().start(() -> {
                try {
                    fn.fn.invoke(request, fn.ctx);
                    outcome.complete(null);
                } catch (Throwable t) {
                    outcome.complete(t);
                }
            });
            assertThat(opened.await(30, TimeUnit.SECONDS)).isTrue();
            assertThat(POOLS.activeConnectionsForTest(dsn)).isEqualTo(before + 1);
            worker.interrupt();

            assertThat(outcome.get(10, TimeUnit.SECONDS)).isInstanceOf(InterruptedException.class);
            assertThat(POOLS.activeConnectionsForTest(dsn))
                    .as("mutant: release only on a normal return — the interrupted call keeps its connection")
                    .isEqualTo(before);
            assertThat(count("SELECT count(*) FROM wasm_db_items WHERE id = 501")).isZero();
        } finally {
            CONTROL_PLANE.emitDoes(request -> {
            });
        }
    }

    // ── test 3: a tx id belongs to the call that opened it ────────────────

    @Test
    void aTxIdFromOneCallIsRefusedInTheNext(@TempDir Path dir) throws Exception {
        try (Fn fn = load(dir, 16)) {
            JsonNode first = fn.runBody(script(step("begin", db()), step("begin", db())));
            String id = first.path("results").get(0).path("tx").asString();
            String second = first.path("results").get(1).path("tx").asString();
            assertThat(id).as("128 random bits, base64url").matches("[A-Za-z0-9_-]{22}");
            assertThat(second).isNotEqualTo(id);

            JsonNode results = fn.run(script(
                    step("query", withTx(in("SELECT 1"), id)),
                    step("execute", withTx(in("INSERT INTO wasm_db_items (id, name) VALUES (?, ?)", 601, "late"),
                            id)),
                    step("commit", txOnly(id)),
                    step("rollback", txOnly(id))));

            for (JsonNode answer : results) {
                assertThat(code(answer)).as(results.toString()).isEqualTo("DB_TX_UNKNOWN");
            }
            assertThat(count("SELECT count(*) FROM wasm_db_items WHERE id = 601")).isZero();
        }
    }

    /// A tx id names a transaction on one database: presented with another
    /// declared `db`, it is unknown — the statement never runs on a connection
    /// the guest did not name.
    @Test
    void aTxIdIsRefusedWithADifferentDatabaseThanTheOneItWasOpenedOn(@TempDir Path dir) throws Exception {
        try (Fn fn = load(dir, 16, "second")) {
            JsonNode results = fn.run(script(
                    step("begin", db()),
                    step("execute", tx(in("INSERT INTO wasm_db_items (id, name) VALUES (?, ?)", 651, "wrong db")
                            .put("db", "second"))),
                    step("query", in("SELECT 1 AS one").put("db", "second")),
                    step("commit", txOnly())));

            assertThat(code(results.get(1))).as("mutant: resolve tx without its db: " + results)
                    .isEqualTo("DB_TX_UNKNOWN");
            assertThat(results.get(2).path("rows").get(0).path("one").asInt())
                    .as("the second declared db itself works").isEqualTo(1);
            assertThat(results.get(3).path("ok").asBoolean()).isTrue();
            assertThat(count("SELECT count(*) FROM wasm_db_items WHERE id = 651")).isZero();
        }
    }

    /// The case a per-call registry exists for: call A holds a transaction open
    /// (parked in `fc_emit_event`, which the control plane holds) while call B,
    /// on another instance, presents A's id.
    @Test
    void aConcurrentCallCannotUseATransactionAnotherCallHoldsOpen(@TempDir Path dir) throws Exception {
        CompletableFuture<String> seen = new CompletableFuture<>();
        CountDownLatch release = new CountDownLatch(1);
        CONTROL_PLANE.emitDoes(request -> {
            seen.complete(Json.MAPPER.readTree(request.events().getFirst().data()).path("tx").asString());
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        try (Fn fn = load(dir, 16); ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<Result> callA = CompletableFuture.supplyAsync(() -> {
                try {
                    return fn.invoke(script("return",
                            step("begin", db()),
                            step("execute", tx(in("INSERT INTO wasm_db_items (id, name) VALUES (?, ?)", 701, "A"))),
                            step("emit", Json.MAPPER.createObjectNode())));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }, threads);
            String idOfA = seen.get(30, TimeUnit.SECONDS);

            JsonNode results;
            try {
                results = fn.run(script(
                        step("execute", withTx(in("INSERT INTO wasm_db_items (id, name) VALUES (?, ?)", 702, "B"),
                                idOfA)),
                        step("commit", txOnly(idOfA))));
            } finally {
                release.countDown();
            }

            assertThat(code(results.get(0)))
                    .as("mutant: one tx map for every call — B would write through A's transaction: " + results)
                    .isEqualTo("DB_TX_UNKNOWN");
            assertThat(code(results.get(1))).as("…and commit it").isEqualTo("DB_TX_UNKNOWN");
            assertThat(callA.get(30, TimeUnit.SECONDS).status()).isEqualTo(200);
            assertThat(count("SELECT count(*) FROM wasm_db_items WHERE id IN (701, 702)"))
                    .as("A's transaction ended open (rolled back); B wrote nothing").isZero();
            assertThat(POOLS.activeConnectionsForTest(dsn)).isZero();
        } finally {
            CONTROL_PLANE.emitDoes(request -> {
            });
        }
    }

    // ── test 4: undeclared db; the deadline ───────────────────────────────

    @Test
    void anUndeclaredDatabaseIsDbNotDeclared(@TempDir Path dir) throws Exception {
        try (Fn fn = load(dir, 16)) {
            ObjectNode other = Json.MAPPER.createObjectNode().put("db", "other").put("sql", "SELECT 1");
            JsonNode results = fn.run(script(step("query", other), step("execute", other),
                    step("begin", Json.MAPPER.createObjectNode().put("db", "other"))));

            for (JsonNode answer : results) {
                assertThat(code(answer)).as(results.toString()).isEqualTo("DB_NOT_DECLARED");
            }
        }
    }

    /// 400 ms left: the statement is cancelled at the deadline (not at a whole
    /// second — `setQueryTimeout` rounding either way is a mutant this kills),
    /// the guest reads `DB_TIMEOUT`, and the connection is back.
    @ParameterizedTest(name = "in a transaction: {0}")
    @ValueSource(booleans = {false, true})
    void aStatementPastTheDeadlineIsDbTimeoutAndItsConnectionIsReturned(boolean inTx, @TempDir Path dir)
            throws Exception {
        try (Fn fn = load(dir, 16)) {
            fn.run(script(step("query", in("SELECT 1")))); // a warm instance: time only the statement
            int before = POOLS.activeConnectionsForTest(dsn);
            ObjectNode sleep = in("SELECT pg_sleep(5)");
            Request request = inTx
                    ? script("return", step("begin", db()), step("query", tx(sleep)))
                    : script("return", step("query", sleep));

            long start = System.nanoTime();
            Result result = ScopedValue.where(InvocationDeadline.CURRENT, Instant.now().plusMillis(400))
                    .call(() -> fn.fn.invoke(request, fn.ctx));
            Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

            JsonNode results = Json.MAPPER.readTree(result.body()).path("results");
            JsonNode answer = results.get(inTx ? 1 : 0);
            assertThat(code(answer)).as("mutant: no statement timeout — pg_sleep runs its 5 s: " + results)
                    .isEqualTo("DB_TIMEOUT");
            assertThat(elapsed).as("mutant: a whole-second timeout (rounded up) — cancelled at 1 s, not 400 ms")
                    .isLessThan(Duration.ofMillis(900));
            assertThat(POOLS.activeConnectionsForTest(dsn)).isEqualTo(before);
        }
    }

    /// Past the deadline a statement is not sent — in autocommit and inside a
    /// transaction alike (a timeout of zero would mean "no timeout" to the driver).
    @Test
    void withNoTimeLeftTheStatementIsNotSentAtAll(@TempDir Path dir) throws Exception {
        try (Fn fn = load(dir, 16)) {
            Request request = script("return",
                    step("execute", in("INSERT INTO wasm_db_items (id, name) VALUES (?, ?)", 801, "too late")),
                    step("begin", db()),
                    step("execute", tx(in("INSERT INTO wasm_db_items (id, name) VALUES (?, ?)", 802, "too late"))),
                    step("commit", txOnly()));
            Result result = ScopedValue.where(InvocationDeadline.CURRENT, Instant.now().minusSeconds(1))
                    .call(() -> fn.fn.invoke(request, fn.ctx));

            JsonNode results = Json.MAPPER.readTree(result.body()).path("results");
            assertThat(code(results.get(0))).as(results.toString()).isEqualTo("DB_TIMEOUT");
            assertThat(code(results.get(2))).as("mutant: send it with a zero (= unlimited) timeout: " + results)
                    .isEqualTo("DB_TIMEOUT");
            assertThat(count("SELECT count(*) FROM wasm_db_items WHERE id IN (801, 802)")).isZero();
        }
    }

    // ── test 5: the caps ──────────────────────────────────────────────────

    @Test
    void aQueryStopsAtTheRowCapAndSaysSo(@TempDir Path dir) throws Exception {
        try (Fn fn = load(dir, 64)) {
            JsonNode results = fn.run(script(
                    summary(in("SELECT g FROM generate_series(1, 10005) g")),
                    summary(in("SELECT g FROM generate_series(1, 10000) g")),
                    summary(in("SELECT g FROM generate_series(1, 9999) g"))));

            assertThat(results.get(0).path("rowCount").asInt()).as(results.toString()).isEqualTo(10_000);
            assertThat(results.get(0).path("truncated").asBoolean()).isTrue();
            assertThat(results.get(1).path("rowCount").asInt())
                    .as("mutant: off by one at the cap").isEqualTo(10_000);
            assertThat(results.get(1).path("truncated").asBoolean())
                    .as("exactly the cap is complete, not truncated").isFalse();
            assertThat(results.get(2).path("rowCount").asInt()).isEqualTo(9_999);
            assertThat(results.get(2).path("truncated").asBoolean()).isFalse();
        }
    }

    @Test
    void aQueryStopsBeforeEightMibOfRowJson(@TempDir Path dir) throws Exception {
        try (Fn fn = load(dir, 128)) {
            // Each row is {"x":"<1 MiB of x>"} — a little over 1 MiB — so the eighth would cross 8 MiB.
            JsonNode answer = fn.run(script(summary(in("SELECT repeat('x', 1048576) AS x "
                    + "FROM generate_series(1, 20)")))).get(0);

            assertThat(answer.path("rowCount").asInt()).as(answer.toString()).isEqualTo(7);
            assertThat(answer.path("truncated").asBoolean()).isTrue();
            assertThat(answer.path("bytes").asLong()).isLessThanOrEqualTo(8L * 1024 * 1024 + 64);
        }
    }

    // ── fixtures ──────────────────────────────────────────────────────────

    /// A loaded, initialised Wasm version of the guest's `db` export declaring
    /// one database, `main`, over this class's test database.
    private static Fn load(Path dir, int wasmMemoryMb, String... moreDbs) throws Exception {
        Path wasm = WasmFixtures.guest(dir);
        ObjectNode root = (ObjectNode) WasmFixtures.manifest("db", 4, 30_000, wasmMemoryMb).toJson();
        root.putArray("secrets").add(SECRET);
        ArrayNode db = root.putArray("db");
        db.addObject().put("name", "main").put("secretRef", SECRET).put("poolSize", 4);
        for (String name : moreDbs) {
            db.addObject().put("name", name).put("secretRef", SECRET).put("poolSize", 4);
        }
        Manifest manifest = Manifest.readStored(root);
        assertThat(manifest.db()).hasSize(1 + moreDbs.length);

        LoadOutcome outcome = new WasmFunctionLoader().load(wasm, manifest, ADDRESS, 1);
        assertThat(outcome).isInstanceOf(Loaded.class);
        LoadedFunction fn = ((Loaded) outcome).function();
        DesiredDocument.Entry entry = new DesiredDocument.Entry(ADDRESS, "fnc_db", "db1", 1,
                DesiredDocument.Role.LIVE, DesiredDocument.Mode.LAZY, digest(wasm), wasm.toUri().toString(), null,
                null, manifest, null, null, null, Map.of(), Map.of(SECRET, dsn), List.of(), List.of());
        FunctionContext ctx = new ContextFactory(POOLS, AllowlistHttpCaller.newSharedClient(), Clock.systemUTC(),
                CONTROL_PLANE, "host-1").build(entry, fn);
        fn.attachContext(ctx);
        fn.init(ctx);
        return new Fn(fn, ctx);
    }

    private record Fn(LoadedFunction fn, FunctionContext ctx) implements AutoCloseable {
        Result invoke(Request request) throws Exception {
            return fn.invoke(request, ctx);
        }

        /// The call's body, `{"results":[…],"tx":…}`.
        JsonNode runBody(Request request) throws Exception {
            Result result = invoke(request);
            assertThat(result.status()).as(new String(result.body())).isEqualTo(200);
            return Json.MAPPER.readTree(result.body());
        }

        /// Each step's answer, in order.
        JsonNode run(Request request) throws Exception {
            return runBody(request).path("results");
        }

        @Override
        public void close() {
            fn.close();
        }
    }

    private static Request script(ObjectNode... steps) {
        return script("return", steps);
    }

    private static Request script(String then, ObjectNode... steps) {
        ObjectNode script = Json.MAPPER.createObjectNode();
        ArrayNode array = script.putArray("steps");
        for (ObjectNode step : steps) {
            array.add(step);
        }
        script.put("then", then);
        return new Request(new io.flowcatalyst.function.FunctionAddress("wasm", "svc", "db"), 1, "inv-db", "GET",
                "/db", null, null, Map.of(), Map.of("script", List.of(script.toString())), Map.of(), new byte[0],
                "127.0.0.1", Caller.Anonymous.INSTANCE);
    }

    private static ObjectNode step(String fn, ObjectNode in) {
        ObjectNode step = Json.MAPPER.createObjectNode();
        step.put("fn", fn);
        step.set("in", in);
        return step;
    }

    private static ObjectNode summary(ObjectNode in) {
        return step("query", in).put("summary", true);
    }

    private static ObjectNode db() {
        return Json.MAPPER.createObjectNode().put("db", "main");
    }

    /// `{"db":"main","sql":…,"params":[…]}`; a param already a [JsonNode] goes in as it is.
    private static ObjectNode in(String sql, Object... params) {
        ObjectNode in = db().put("sql", sql);
        ArrayNode array = in.putArray("params");
        for (Object p : params) {
            switch (p) {
                case JsonNode node -> array.add(node);
                case Integer i -> array.add(i);
                case Long l -> array.add(l);
                case String s -> array.add(s);
                default -> throw new IllegalArgumentException(String.valueOf(p));
            }
        }
        return in;
    }

    /// Adds `"tx":"$tx"` — the guest substitutes the latest `begin`'s id.
    private static ObjectNode tx(ObjectNode in) {
        return in.put("tx", "$tx");
    }

    private static ObjectNode withTx(ObjectNode in, String id) {
        return in.put("tx", id);
    }

    private static ObjectNode txOnly() {
        return Json.MAPPER.createObjectNode().put("tx", "$tx");
    }

    private static ObjectNode txOnly(String id) {
        return Json.MAPPER.createObjectNode().put("tx", id);
    }

    private static String code(JsonNode answer) {
        return answer.path("error").path("code").asString("<no error: " + answer + ">");
    }

    private static void sql(String statement) throws SQLException {
        try (Connection c = TestPg.dataSource().getConnection(); Statement st = c.createStatement()) {
            st.execute(statement);
        }
    }

    private static long count(String query) throws SQLException {
        try (Connection c = TestPg.dataSource().getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(query)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static Digest digest(Path file) throws Exception {
        var md = MessageDigest.getInstance("SHA-256");
        return Digest.parse("sha256:" + HexFormat.of().formatHex(md.digest(Files.readAllBytes(file))));
    }

    private static ListAppender<ILoggingEvent> attachRootAppender() {
        ch.qos.logback.classic.Logger root =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        return appender;
    }

    private static void detachRootAppender(ListAppender<ILoggingEvent> appender) {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME))
                .detachAppender(appender);
    }
}
