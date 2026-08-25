package io.flowcatalyst.router.config;

import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.router.config.PoolSpec;
import io.flowcatalyst.router.pool.Pool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The router configuration wire shape and merge (`docs/spec/router.md`
/// §2.5, §8.1).
class RouterConfigTest {

    // ── Wire shape ──────────────────────────────────────────────────────

    @Test
    @DisplayName("legacy name and uri aliases are accepted on input")
    void legacyAliasesAccepted() throws Exception {
        // Deployed config documents use them; refusing would break a running
        // deployment on upgrade.
        var config = Json.MAPPER.readValue(
                "{\"uri\":\"postgres://db/q\",\"name\":\"orders\"}", QueueConfig.class);

        assertThat(config.queueUri()).isEqualTo("postgres://db/q");
        assertThat(config.queueName()).isEqualTo("orders");
    }

    @Test
    @DisplayName("canonical keys win when both spellings are present")
    void canonicalWinsOverAlias() throws Exception {
        var config = Json.MAPPER.readValue(
                "{\"queueUri\":\"a://x\",\"uri\":\"b://y\",\"queueName\":\"canonical\",\"name\":\"legacy\"}",
                QueueConfig.class);

        assertThat(config.queueUri()).isEqualTo("a://x");
        assertThat(config.queueName()).isEqualTo("canonical");
    }

    @Test
    @DisplayName("a round trip normalises to canonical keys")
    void roundTripNormalises() throws Exception {
        var parsed = Json.MAPPER.readValue("{\"uri\":\"postgres://db/q\"}", QueueConfig.class);

        var json = Json.MAPPER.writeValueAsString(parsed);

        assertThat(json).contains("queueUri").doesNotContain("\"uri\"");
    }

    @Test
    @DisplayName("a bare uri gets every default, so a minimal entry is usable")
    void defaultsApplied() {
        var config = QueueConfig.of("postgres://db/q");

        assertThat(config.queueName()).as("name defaults to the uri").isEqualTo("postgres://db/q");
        assertThat(config.connections()).isEqualTo(QueueConfig.DEFAULT_CONNECTIONS);
        assertThat(config.visibilityTimeout()).isEqualTo(QueueConfig.DEFAULT_VISIBILITY_TIMEOUT);
    }

    @Test
    @DisplayName("a queue without a uri is rejected rather than silently ignored")
    void uriIsRequired() {
        assertThatThrownBy(() -> new QueueConfig("  ", "orders", 1, 30))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("unknown fields are ignored, so the contract can grow")
    void unknownFieldsIgnored() throws Exception {
        var config = Json.MAPPER.readValue(
                "{\"queueUri\":\"a://x\",\"somethingNew\":123}", QueueConfig.class);

        assertThat(config.queueUri()).isEqualTo("a://x");
    }

    // ── Merge ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("a single source passes through unchanged")
    void singleSourcePassesThrough() {
        var source = config(List.of(pool("A", 4, 0)), List.of(QueueConfig.of("q://1")));

        assertThat(RouterConfig.merge(List.of(source), RouterConfig.ConflictReporter.IGNORE))
                .isEqualTo(source);
    }

    @Test
    @DisplayName("sources are unioned, and the first definition wins")
    void firstDefinitionWins() {
        // Position is precedence: an operator can tell which document is
        // authoritative without reading all of them.
        var first = config(List.of(pool("A", 4, 0)), List.of(QueueConfig.of("q://1")));
        var second = config(List.of(pool("A", 99, 0), pool("B", 2, 0)), List.of(QueueConfig.of("q://2")));

        var merged = RouterConfig.merge(List.of(first, second), RouterConfig.ConflictReporter.IGNORE);

        assertThat(merged.processingPools()).containsExactly(pool("A", 4, 0), pool("B", 2, 0));
        assertThat(merged.queues()).hasSize(2);
    }

    @Test
    @DisplayName("a conflicting duplicate is reported, an identical one is not")
    void conflictsAreReportedButIdenticalDuplicatesAreNot() {
        // Two sources agreeing is not a problem worth telling anyone about.
        var reported = new ArrayList<String>();
        var a = config(List.of(pool("A", 4, 0)), List.of());
        var disagrees = config(List.of(pool("A", 99, 0)), List.of());
        var agrees = config(List.of(pool("A", 4, 0)), List.of());

        RouterConfig.merge(List.of(a, agrees), reported::add);
        assertThat(reported).isEmpty();

        RouterConfig.merge(List.of(a, disagrees), reported::add);
        assertThat(reported).singleElement().asString().contains("pool").contains("\"A\"").contains("keeping the first");
    }

    @Test
    @DisplayName("merging nothing yields an empty configuration, not a failure")
    void mergingNothing() {
        assertThat(RouterConfig.merge(List.of(), RouterConfig.ConflictReporter.IGNORE))
                .isEqualTo(RouterConfig.EMPTY);
    }

    // ── Change detection ────────────────────────────────────────────────

    @Test
    @DisplayName("an identical configuration compares equal, so no reconfigure is triggered")
    void identicalConfigsAreEqual() {
        // Structural equality rather than comparing serialised bytes: it says
        // the same thing without depending on how the mapper happens to order
        // or format fields.
        var a = config(List.of(pool("A", 4, 10)), List.of(QueueConfig.of("q://1")));
        var b = config(List.of(pool("A", 4, 10)), List.of(QueueConfig.of("q://1")));

        assertThat(a).isEqualTo(b);
    }

    @ParameterizedTest(name = "a changed {0} is detected")
    @CsvSource({"concurrency", "rate limit", "pool set", "queue set"})
    void changesAreDetected(String what) {
        var base = config(List.of(pool("A", 4, 10)), List.of(QueueConfig.of("q://1")));
        var changed = switch (what) {
            case "concurrency" -> config(List.of(pool("A", 8, 10)), List.of(QueueConfig.of("q://1")));
            case "rate limit" -> config(List.of(pool("A", 4, 20)), List.of(QueueConfig.of("q://1")));
            case "pool set" -> config(List.of(pool("A", 4, 10), pool("B", 1, 0)), List.of(QueueConfig.of("q://1")));
            default -> config(List.of(pool("A", 4, 10)), List.of(QueueConfig.of("q://2")));
        };

        assertThat(base).isNotEqualTo(changed);
    }

    @Test
    @DisplayName("a changed connections count is a change, even though nothing reads it")
    void connectionsCountsAsAChange() {
        // It has no other effect: its entire purpose is that changing it
        // restarts the consumer.
        var a = new QueueConfig("q://1", "orders", 1, 30);
        var b = new QueueConfig("q://1", "orders", 4, 30);

        assertThat(a.sameConsumerTopology(b)).isFalse();
        assertThat(a.sameConsumerTopology(new QueueConfig("q://1", "orders", 1, 30))).isTrue();
    }

    private static RouterConfig config(List<PoolSpec> pools, List<QueueConfig> queues) {
        return new RouterConfig(pools, queues);
    }

    private static PoolSpec pool(String code, int concurrency, int rpm) {
        return new PoolSpec(code, concurrency, rpm);
    }
}
