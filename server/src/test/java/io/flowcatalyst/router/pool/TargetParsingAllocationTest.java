package io.flowcatalyst.router.pool;

import io.flowcatalyst.router.policy.BreakerRegistry;
import io.flowcatalyst.router.support.AllocProbe;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// The mediation target is the same string for every message of a subscription;
/// deriving the breaker key and the URI from it must not re-parse each time.
class TargetParsingAllocationTest {

    private static final String TARGET = "https://hooks.example.com:8443/api/v1/webhook?x=1&y=2#frag";

    @Test
    @DisplayName("keyFor on a repeated target allocates nothing after the first call")
    void keyForIsCached() {
        BreakerRegistry.keyFor(TARGET);
        double bytes = AllocProbe.bytesPerCall(20_000, 50_000, () -> BreakerRegistry.keyFor(TARGET));
        System.out.println("ALLOC keyFor bytes/call=" + bytes);
        assertThat(bytes).isLessThan(64);
    }

    @Test
    @DisplayName("parseTarget on a repeated target allocates nothing after the first call")
    void parseTargetIsCached() {
        HttpMediator.parseTarget(TARGET);
        double bytes = AllocProbe.bytesPerCall(20_000, 50_000, () -> HttpMediator.parseTarget(TARGET));
        System.out.println("ALLOC parseTarget bytes/call=" + bytes);
        assertThat(bytes).isLessThan(64);
    }

    @Test
    @DisplayName("results are unchanged: key strips query and fragment, invalid targets report as before")
    void behaviourUnchanged() {
        assertThat(BreakerRegistry.keyFor(TARGET)).isEqualTo("https://hooks.example.com:8443/api/v1/webhook");
        assertThat(BreakerRegistry.keyFor("not a url")).isEqualTo("not a url");
        assertThat(BreakerRegistry.keyFor("/relative")).isEqualTo("/relative");
        assertThat(HttpMediator.parseTarget(TARGET)).isPresent();
        assertThat(HttpMediator.parseTarget(TARGET)).isPresent();
        assertThat(HttpMediator.parseTarget("not a url")).isEmpty();
        assertThat(HttpMediator.parseTarget("not a url")).isEmpty();
        assertThat(HttpMediator.parseTarget("/relative")).isEmpty();
    }

    @Test
    @DisplayName("past the cap targets are still derived correctly, just not stored")
    void beyondCapStillCorrect() {
        for (int i = 0; i < 6_000; i++) {
            var t = "https://h" + i + ".example.com/p";
            assertThat(BreakerRegistry.keyFor(t)).isEqualTo(t);
            assertThat(HttpMediator.parseTarget(t)).isPresent();
        }
    }
}
