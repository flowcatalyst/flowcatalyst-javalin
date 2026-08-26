package io.flowcatalyst.platform.event;

import tools.jackson.databind.node.NullNode;
import io.flowcatalyst.platform.event.Event.ContextEntry;
import io.flowcatalyst.platform.event.Event.Projection;
import io.flowcatalyst.platform.shared.json.Json;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The pure rules of the aggregate (spec §1): the record's invariants and
/// the two nested shapes, without a database.
class EventTest {

    private static final Instant T = Instant.parse("2026-08-22T10:11:12.123456Z");

    private static Event event(String clientId, Projection projection, List<ContextEntry> context) {
        return new Event("0HZX1Y2W3V4U5", "1.0", "app:sub:agg:verb", "platform:admin", "platform.agg.x", T,
                null, context, "app:sub:agg:verb-0HZX1Y2W3V4U5", clientId, null, null, null, T, projection);
    }

    // ── Record ─────────────────────────────────────────────────────────────

    @Test
    void requiredComponentsAreNonNull() {
        assertThatThrownBy(() -> new Event(null, null, "t", "s", null, T, null, null, null, null, null, null, null, T, null))
                .isInstanceOf(NullPointerException.class).hasMessage("id");
        assertThatThrownBy(() -> new Event("id", null, null, "s", null, T, null, null, null, null, null, null, null, T, null))
                .isInstanceOf(NullPointerException.class).hasMessage("type");
        assertThatThrownBy(() -> new Event("id", null, "t", null, null, T, null, null, null, null, null, null, null, T, null))
                .isInstanceOf(NullPointerException.class).hasMessage("source");
        assertThatThrownBy(() -> new Event("id", null, "t", "s", null, null, null, null, null, null, null, null, null, T, null))
                .isInstanceOf(NullPointerException.class).hasMessage("time");
        assertThatThrownBy(() -> new Event("id", null, "t", "s", null, T, null, null, null, null, null, null, null, null, null))
                .isInstanceOf(NullPointerException.class).hasMessage("createdAt");
    }

    @Test
    void aNullContextReadsAsEmptyAndTheListIsCopied() {
        assertThat(event(null, null, null).context()).isEmpty();
        var mutable = new ArrayList<>(List.of(new ContextEntry("principalId", "prn_1")));
        var e = event(null, null, mutable);
        mutable.clear();
        assertThat(e.context()).containsExactly(new ContextEntry("principalId", "prn_1"));
    }

    @Test
    void aJsonNullPayloadIsAbsent() throws Exception {
        var e = new Event("id", null, "t", "s", null, T, NullNode.getInstance(), null, null, null, null, null, null, T, null);
        assertThat(e.data()).isNull();
        var doc = new Event("id", null, "t", "s", null, T, Json.MAPPER.readTree("{\"k\":1}"), null, null, null, null, null, null, T, null);
        assertThat(doc.data().get("k").asInt()).isEqualTo(1);
    }

    @Test
    void platformScopedMeansNoClient() {
        assertThat(event(null, null, null).isPlatformScoped()).isTrue();
        assertThat(event("cli_1", null, null).isPlatformScoped()).isFalse();
    }

    // ── Nested shapes ──────────────────────────────────────────────────────

    @Test
    void projectionRequiresApplicationAndProjectedAtOnly() {
        var p = new Projection("app", null, null, T);
        assertThat(p.subdomain()).isNull();
        assertThat(p.aggregate()).isNull();
        assertThatThrownBy(() -> new Projection(null, "s", "a", T)).isInstanceOf(NullPointerException.class).hasMessage("application");
        assertThatThrownBy(() -> new Projection("app", "s", "a", null)).isInstanceOf(NullPointerException.class).hasMessage("projectedAt");
        assertThat(event(null, p, null).projection()).isSameAs(p);
        assertThat(event(null, null, null).projection()).as("a write-side row has no projection").isNull();
    }

    @Test
    void contextEntriesHaveBothHalves() {
        assertThatThrownBy(() -> new ContextEntry(null, "v")).isInstanceOf(NullPointerException.class).hasMessage("key");
        assertThatThrownBy(() -> new ContextEntry("k", null)).isInstanceOf(NullPointerException.class).hasMessage("value");
    }
}
