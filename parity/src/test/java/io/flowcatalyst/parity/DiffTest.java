package io.flowcatalyst.parity;

import io.flowcatalyst.platform.shared.json.Json;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// Pins the structural diff engine (parity-harness spec §6): absent vs JSON
/// `null` are distinct, array position is significant, identical trees
/// produce nothing, and status/header mismatches surface with their own
/// pointers.
class DiffTest {

    private static Normalised of(int status, Map<String, String> headers, ObjectNode body) {
        return new Normalised(status, headers, body);
    }

    private static ObjectNode obj() {
        return Json.MAPPER.createObjectNode();
    }

    @Test
    void identicalBodiesProduceNoDiff() {
        ObjectNode go = obj();
        go.put("id", "evt_1");
        ObjectNode javaSide = obj();
        javaSide.put("id", "evt_1");

        assertThat(Diff.compare(of(200, Map.of(), go), of(200, Map.of(), javaSide))).isEmpty();
    }

    /// A field present-and-null on one side is not the same finding as the
    /// field being entirely absent on the other — `Diff.ABSENT` must never
    /// collide with the rendered text of JSON `null`.
    @Test
    void absentIsDistinctFromJsonNull() {
        ObjectNode go = obj();
        go.putNull("description");
        ObjectNode javaSide = obj(); // no "description" key at all

        List<DiffEntry> diffs = Diff.compare(of(200, Map.of(), go), of(200, Map.of(), javaSide));

        assertThat(diffs).hasSize(1);
        DiffEntry d = diffs.getFirst();
        assertThat(d.pointer()).isEqualTo("/description");
        assertThat(d.go()).isEqualTo("null");
        assertThat(d.java()).isEqualTo(Diff.ABSENT);
        assertThat(d.go()).isNotEqualTo(d.java());
    }

    @Test
    void bothSidesAbsentProducesNoDiff() {
        ObjectNode go = obj();
        ObjectNode javaSide = obj();
        assertThat(Diff.compare(of(200, Map.of(), go), of(200, Map.of(), javaSide))).isEmpty();
    }

    /// Order is part of the contract (spec §5) unless the step names the
    /// pointer `unordered` — which is `Normaliser`'s job, not `Diff`'s;
    /// `Diff` itself always compares positionally.
    @Test
    void arrayElementOrderIsSignificant() {
        ObjectNode go = obj();
        go.putArray("items").add("a").add("b");
        ObjectNode javaSide = obj();
        javaSide.putArray("items").add("b").add("a");

        List<DiffEntry> diffs = Diff.compare(of(200, Map.of(), go), of(200, Map.of(), javaSide));

        assertThat(diffs).extracting(DiffEntry::pointer).containsExactlyInAnyOrder("/items/0", "/items/1");
    }

    @Test
    void aStatusMismatchIsReportedAtSlashStatus() {
        List<DiffEntry> diffs = Diff.compare(of(201, Map.of(), obj()), of(500, Map.of(), obj()));
        assertThat(diffs).containsExactly(new DiffEntry("/status", "201", "500"));
    }

    @Test
    void aHeaderPresentOnOnlyOneSideIsAbsentOnTheOther() {
        List<DiffEntry> diffs = Diff.compare(
                of(200, Map.of("Location", "/x"), obj()),
                of(200, Map.of(), obj()));

        assertThat(diffs).containsExactly(new DiffEntry("/headers/Location", "/x", Diff.ABSENT));
    }
}
