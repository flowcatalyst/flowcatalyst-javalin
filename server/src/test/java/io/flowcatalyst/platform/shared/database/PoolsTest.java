package io.flowcatalyst.platform.shared.database;

import io.flowcatalyst.http.Group;
import io.flowcatalyst.server.EnvReader;
import io.flowcatalyst.testpg.TestPg;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import io.prometheus.metrics.model.snapshots.MetricSnapshots;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// `docs/spec/admission.md` §11.7 / §11.3a: four physical pools opened from
/// one budget `B`, sized `api = B/2`, `bff = B/4`, `dispatch = B/4`,
/// `background = 4` (outside `B`), each overridable with
/// `FC_DB_POOL_SIZE_<GROUP>`, every pool at least [Pools#MIN_POOL_SIZE].
class PoolsTest {

    private static String url() {
        return TestPg.instance().getJdbcUrl("postgres", "postgres");
    }

    @Test
    void opensFourPoolsSizedFromTheDefaultBudget() {
        // Mutant: swap a share, e.g. api = B/4 instead of B/2 — this fails.
        try (Pools pools = Pools.open(url(), new EnvReader(Map.of()))) {
            assertThat(pools.api().poolSize()).isEqualTo(Pools.DEFAULT_BUDGET / 2);
            assertThat(pools.bff().poolSize()).isEqualTo(Pools.DEFAULT_BUDGET / 4);
            assertThat(pools.dispatch().poolSize()).isEqualTo(Pools.DEFAULT_BUDGET / 4);
            assertThat(pools.background().poolSize()).isEqualTo(Pools.BACKGROUND_POOL_SIZE);
        }
    }

    @Test
    void fcDbPoolSizeRescalesEveryShareTogether() {
        try (Pools pools = Pools.open(url(), new EnvReader(Map.of("FC_DB_POOL_SIZE", "40")))) {
            assertThat(pools.api().poolSize()).isEqualTo(20);
            assertThat(pools.bff().poolSize()).isEqualTo(10);
            assertThat(pools.dispatch().poolSize()).isEqualTo(10);
            // background is fixed, outside B.
            assertThat(pools.background().poolSize()).isEqualTo(Pools.BACKGROUND_POOL_SIZE);
        }
    }

    @Test
    void perGroupOverrideWinsOverTheDerivedShare() {
        try (Pools pools = Pools.open(url(), new EnvReader(Map.of(
                "FC_DB_POOL_SIZE_API", "9",
                "FC_DB_POOL_SIZE_BFF", "5",
                "FC_DB_POOL_SIZE_DISPATCH", "6",
                "FC_DB_POOL_SIZE_BACKGROUND", "7")))) {
            assertThat(pools.api().poolSize()).isEqualTo(9);
            assertThat(pools.bff().poolSize()).isEqualTo(5);
            assertThat(pools.dispatch().poolSize()).isEqualTo(6);
            assertThat(pools.background().poolSize()).isEqualTo(7);
        }
    }

    @Test
    void everyPoolIsAtLeastTwoEvenFromATinyBudgetOrOverride() {
        try (Pools pools = Pools.open(url(), new EnvReader(Map.of(
                "FC_DB_POOL_SIZE", "1",
                "FC_DB_POOL_SIZE_BACKGROUND", "1")))) {
            // budget=1 -> api=0, bff=0, dispatch=0 before the floor.
            assertThat(pools.api().poolSize()).isEqualTo(Pools.MIN_POOL_SIZE);
            assertThat(pools.bff().poolSize()).isEqualTo(Pools.MIN_POOL_SIZE);
            assertThat(pools.dispatch().poolSize()).isEqualTo(Pools.MIN_POOL_SIZE);
            assertThat(pools.background().poolSize()).isEqualTo(Pools.MIN_POOL_SIZE);
        }
    }

    @Test
    void forGroupMapsToTheRightPhysicalPoolAndNoDbHasNone() {
        try (Pools pools = Pools.open(url(), new EnvReader(Map.of()))) {
            assertThat(pools.forGroup(Group.API_READ)).isSameAs(pools.api());
            assertThat(pools.forGroup(Group.API_WRITE)).isSameAs(pools.api());
            assertThat(pools.forGroup(Group.LOGIN)).isSameAs(pools.api());
            assertThat(pools.forGroup(Group.OIDC)).isSameAs(pools.api());
            assertThat(pools.forGroup(Group.BFF)).isSameAs(pools.bff());
            assertThat(pools.forGroup(Group.DISPATCH)).isSameAs(pools.dispatch());
            assertThatThrownBy(() -> pools.forGroup(Group.NO_DB)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void theCollectorExposesFourLabelledSeries() {
        try (Pools pools = Pools.open(url(), new EnvReader(Map.of("FC_DB_POOL_SIZE", "8")))) {
            var registry = new PrometheusRegistry();
            pools.registerCollectors(registry);

            // Four SEPARATE registered collectors, so scrape() returns four separate
            // same-named GaugeSnapshots (`MetricSnapshots` does not merge across
            // collectors, `MetricSnapshots.Builder#metricSnapshot`) — collect the
            // `pool` label across every one of them, not just the first.
            MetricSnapshots snapshot = registry.scrape();
            Set<String> poolLabels = snapshot.stream()
                    .filter(s -> s.getMetadata().getName().equals("fc_db_gate_waiting"))
                    .flatMap(s -> ((io.prometheus.metrics.model.snapshots.GaugeSnapshot) s).getDataPoints().stream())
                    .map(dp -> dp.getLabels().get("pool"))
                    .collect(Collectors.toSet());

            assertThat(poolLabels).containsExactlyInAnyOrder("api", "bff", "dispatch", "background");
        }
    }
}
