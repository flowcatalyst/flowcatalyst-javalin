package io.flowcatalyst.function;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// `OutboundEvent`'s own invariants (`docs/spec/function-context.md` §3:
/// "`dedupId` is required by the API type").
class OutboundEventTest {

    @Test
    void dedupIdIsRequiredAndMayNotBeBlank() {
        assertThatThrownBy(() -> event(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> event("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> event("   ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aNonBlankDedupIdIsAccepted() {
        OutboundEvent e = event("dedup-1");
        assertThat(e.dedupId()).isEqualTo("dedup-1");
    }

    private static OutboundEvent event(String dedupId) {
        return new OutboundEvent("app:sub:agg:evt", "src", "subj", "application/json",
                "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8), null, null, null, dedupId);
    }
}
