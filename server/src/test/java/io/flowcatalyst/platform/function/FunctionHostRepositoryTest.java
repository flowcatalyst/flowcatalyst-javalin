package io.flowcatalyst.platform.function;

import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.shared.platformsink.PlatformSink;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import io.flowcatalyst.testpg.TestPg;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/// `FunctionHostRepository` against the embedded Postgres (spec
/// `function-registry.md` §6.3, §8 M14).
class FunctionHostRepositoryTest {

    private static final DataSource DS = TestPg.dataSource();
    private static final FunctionHostRepository REPO = new FunctionHostRepository(DS);
    private static final UnitOfWork UOW = new UnitOfWork(DS, new PlatformSink(Json.MAPPER));

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toLowerCase(Locale.ROOT);
    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime());

    private static String fresh() {
        return "t" + Long.toString(SEQ.incrementAndGet(), 36);
    }

    private static void persist(FunctionHost h) {
        UOW.inTransaction(tx -> {
            REPO.persist(h, tx.dbTx());
            return null;
        });
    }

    // ── round-trip ─────────────────────────────────────────────────────────

    @Test
    void registerHeartbeatAndFindRoundTrip() {
        DnsLabel pool = new DnsLabel("pool-" + RUN + "-" + fresh());
        String id = "host-" + RUN + "-" + fresh();
        Instant startedAt = Instant.now();
        FunctionHost host = FunctionHost.register(id, pool, startedAt);
        persist(host);

        FunctionHost reloaded = REPO.findById(id).orElseThrow();
        assertThat(reloaded.pool()).isEqualTo(pool);
        assertThat(reloaded.state()).isEqualTo(FunctionHost.HostState.ACTIVE);
        assertThat(reloaded.loaded()).isEmpty();

        FunctionAddress addr = FunctionAddress.parse("billing.invoices.create");
        Instant beat = startedAt.plusSeconds(5);
        FunctionHost withLoaded = host.heartbeat(FunctionHost.HostState.DRAINING,
                List.of(new FunctionHost.LoadedVersion(addr, 3, new FunctionHost.LoadState.Loaded()),
                        new FunctionHost.LoadedVersion(addr, 2, new FunctionHost.LoadState.Failed("boom"))),
                beat);
        persist(withLoaded);

        FunctionHost reloadedAgain = REPO.findById(id).orElseThrow();
        assertThat(reloadedAgain.state()).isEqualTo(FunctionHost.HostState.DRAINING);
        assertThat(reloadedAgain.pool()).as("pool never changes").isEqualTo(pool);
        assertThat(reloadedAgain.startedAt()).as("started_at is insert-only")
                .isEqualTo(startedAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        assertThat(reloadedAgain.loaded()).hasSize(2);
        assertThat(reloadedAgain.loaded()).anySatisfy(lv -> {
            assertThat(lv.version()).isEqualTo(3);
            assertThat(lv.state()).isInstanceOf(FunctionHost.LoadState.Loaded.class);
        });
        assertThat(reloadedAgain.loaded()).anySatisfy(lv -> {
            assertThat(lv.version()).isEqualTo(2);
            assertThat(lv.state()).isEqualTo(new FunctionHost.LoadState.Failed("boom"));
        });
    }

    @Test
    void listByPoolListLiveAndPoolsCountOnlyLiveHosts() {
        DnsLabel pool = new DnsLabel("pool-" + RUN + "-" + fresh());
        Instant now = Instant.now();
        FunctionHost live = FunctionHost.register("host-live-" + fresh(), pool, now);
        FunctionHost stale = FunctionHost.register("host-stale-" + fresh(), pool, now.minusSeconds(3600));
        persist(live);
        persist(stale.heartbeat(FunctionHost.HostState.ACTIVE, List.of(), now.minusSeconds(3600)));

        assertThat(REPO.listByPool(pool)).extracting(FunctionHost::id).containsExactlyInAnyOrder(live.id(), stale.id());

        Instant cutoff = now.minusSeconds(60);
        assertThat(REPO.listLive(pool, cutoff)).extracting(FunctionHost::id).containsExactly(live.id());

        List<FunctionHostRepository.PoolSummary> pools = REPO.pools(cutoff);
        assertThat(pools).anySatisfy(p -> {
            assertThat(p.pool()).isEqualTo(pool);
            assertThat(p.hosts()).isEqualTo(1);
        });
    }

    // ── §8 M14: fn_hosts.loaded — a bad entry does not hide the good ones ────

    @Test
    void loadedDropsUnreadableEntriesButKeepsTheGoodOnes() throws SQLException {
        String id = "host-" + RUN + "-" + fresh();
        String loadedJson = """
                [
                  {"address":"not-a-valid-address","version":1,"state":"LOADED"},
                  {"address":"billing.invoices.create","version":2,"state":"WEIRD_UNKNOWN_STATE"},
                  {"address":"billing.invoices.create","version":3,"state":"LOADED"}
                ]""";
        try (Connection c = DS.getConnection(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO fn_hosts (id, pool, state, loaded) VALUES (?, 'default', 'ACTIVE', ?::jsonb)")) {
            ps.setString(1, id);
            ps.setString(2, loadedJson);
            ps.executeUpdate();
        }

        FunctionHost reloaded = REPO.findById(id).orElseThrow();
        assertThat(reloaded.loaded()).as("the bad-address and unknown-state entries are dropped, the good one survives")
                .hasSize(1);
        FunctionHost.LoadedVersion kept = reloaded.loaded().get(0);
        assertThat(kept.version()).isEqualTo(3);
        assertThat(kept.address()).isEqualTo(FunctionAddress.parse("billing.invoices.create"));
        assertThat(kept.state()).isInstanceOf(FunctionHost.LoadState.Loaded.class);
    }

    @Test
    void loadedReadsAsEmptyWhenTheColumnIsAnEmptyArray() throws SQLException {
        String id = "host-" + RUN + "-" + fresh();
        try (Connection c = DS.getConnection(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO fn_hosts (id, pool, state, loaded) VALUES (?, 'default', 'ACTIVE', '[]'::jsonb)")) {
            ps.setString(1, id);
            ps.executeUpdate();
        }
        assertThat(REPO.findById(id).orElseThrow().loaded()).isEmpty();
    }
}
