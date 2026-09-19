package io.flowcatalyst.function;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// Spec `docs/spec/function-host-core.md` §1, L9: `Event.data()` is a
/// defensive copy both ways, and its `equals`/`toString` treat `data` by
/// content (length only in `toString`).
class EventTest {

    @Test
    void dataIsIndependentOfTheArrayPassedInAndReadOut() {
        byte[] data = {1, 2, 3};
        Event event = event(data);
        data[0] = 99;
        assertThat(event.data()).containsExactly(1, 2, 3);

        byte[] read = event.data();
        read[0] = 42;
        assertThat(event.data()).containsExactly(1, 2, 3);
    }

    @Test
    void equalityIsByContentIncludingData() {
        Event a = event(new byte[] {1, 2});
        Event b = event(new byte[] {1, 2});
        Event c = event(new byte[] {1, 3});
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(c);
    }

    @Test
    void toStringReportsDataLengthNotBytes() {
        Event event = event(new byte[] {1, 2, 3});
        assertThat(event.toString()).contains("data.length=3").doesNotContain("[1, 2, 3]");
    }

    private static Event event(byte[] data) {
        return new Event("id-1", "type", "source", "subject", Instant.EPOCH, "application/json",
                data, "corr", "cause", "group", "dedup");
    }
}
