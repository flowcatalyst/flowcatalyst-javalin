package io.flowcatalyst.platform.function;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The aggregate's pure rules (spec `function-registry.md` §6.3): `register`,
/// `heartbeat`, and each [FunctionHost.LoadState]'s `ok()` — no database involved.
class FunctionHostTest {

    @Test
    void registerIsActiveWithNothingLoaded() {
        Instant now = Instant.now();
        FunctionHost h = FunctionHost.register("host-1", new DnsLabel("default"), now);
        assertThat(h.id()).isEqualTo("host-1");
        assertThat(h.state()).isEqualTo(FunctionHost.HostState.ACTIVE);
        assertThat(h.loaded()).isEmpty();
        assertThat(h.startedAt()).isEqualTo(now);
        assertThat(h.lastHeartbeat()).isEqualTo(now);
    }

    @Test
    void heartbeatReplacesStateAndLoadedButKeepsStartedAtAndPool() {
        Instant startedAt = Instant.now();
        FunctionHost h = FunctionHost.register("host-1", new DnsLabel("default"), startedAt);
        FunctionAddress addr = FunctionAddress.parse("billing.invoices.create");
        Instant beat = startedAt.plusSeconds(30);
        FunctionHost afterBeat = h.heartbeat(FunctionHost.HostState.DRAINING,
                List.of(new FunctionHost.LoadedVersion(addr, 3, new FunctionHost.LoadState.Loaded())), beat);

        assertThat(afterBeat.state()).isEqualTo(FunctionHost.HostState.DRAINING);
        assertThat(afterBeat.loaded()).hasSize(1);
        assertThat(afterBeat.lastHeartbeat()).isEqualTo(beat);
        assertThat(afterBeat.startedAt()).as("insert-only, unchanged by a heartbeat").isEqualTo(startedAt);
        assertThat(afterBeat.pool()).as("pool never changes after register").isEqualTo(new DnsLabel("default"));
    }

    @Test
    void hostStateParsesTheStoredConstantName() {
        assertThat(FunctionHost.HostState.parse("ACTIVE")).isEqualTo(FunctionHost.HostState.ACTIVE);
        assertThat(FunctionHost.HostState.parse("DRAINING")).isEqualTo(FunctionHost.HostState.DRAINING);
        assertThatThrownBy(() -> FunctionHost.HostState.parse("BOGUS"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── LoadState.ok() — every implementation states its own opinion ────────

    @Test
    void registeredAndLoadedAreOkFailedIsNot() {
        assertThat(new FunctionHost.LoadState.Registered().ok()).isTrue();
        assertThat(new FunctionHost.LoadState.Loaded().ok()).isTrue();
        assertThat(new FunctionHost.LoadState.Failed("boom").ok()).isFalse();
    }
}
