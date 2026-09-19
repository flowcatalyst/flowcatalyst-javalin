package io.flowcatalyst.function;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// `Event`'s own invariants — parsing it out of a delivery body is
/// `WebhookTest`'s job (`docs/spec/function-invocation.md` §7).
class EventTest {

    @Test
    void idAndTypeAreRequired() {
        assertThatThrownBy(() -> event(null, "type")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> event("id", null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void everyOptionalFieldMayBeNull() {
        Event event = new Event("id-1", "type", 1, null, null, null, null, null, null, null);
        assertThat(event.source()).isNull();
        assertThat(event.subject()).isNull();
        assertThat(event.correlationId()).isNull();
        assertThat(event.messageGroup()).isNull();
        assertThat(event.clientId()).isNull();
        assertThat(event.clientCode()).isNull();
        assertThat(event.dataJson()).isNull();
    }

    @Test
    void equalityIsByContent() {
        Event a = new Event("id-1", "type", 1, "src", "subj", "corr", "grp", "cid", "code", "{\"x\":1}");
        Event b = new Event("id-1", "type", 1, "src", "subj", "corr", "grp", "cid", "code", "{\"x\":1}");
        Event c = new Event("id-1", "type", 2, "src", "subj", "corr", "grp", "cid", "code", "{\"x\":1}");
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(c);
    }

    private static Event event(String id, String type) {
        return new Event(id, type, 1, null, null, null, null, null, null, null);
    }
}
