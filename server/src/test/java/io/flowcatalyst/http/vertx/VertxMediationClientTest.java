package io.flowcatalyst.http.vertx;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VertxMediationClientTest {

    /// One event loop per CPU of the quota, never fewer than one and never more
    /// than the cap: beyond it the loops stop being the limit and each adds a
    /// thread to a small quota.
    @Test
    void eventLoopsFollowTheCpuQuotaWithinBounds() {
        assertThat(VertxMediationClient.eventLoopsFor(0)).isEqualTo(1);
        assertThat(VertxMediationClient.eventLoopsFor(1)).isEqualTo(1);
        assertThat(VertxMediationClient.eventLoopsFor(2)).isEqualTo(2);
        assertThat(VertxMediationClient.eventLoopsFor(4)).isEqualTo(4);
        assertThat(VertxMediationClient.eventLoopsFor(16)).isEqualTo(VertxMediationClient.MAX_EVENT_LOOPS);
    }

    /// The pool must allow more than one connection per origin, or a single
    /// connection's stream limit caps every delivery to that target.
    @Test
    void poolAllowsManyConnectionsPerOrigin() {
        assertThat(VertxMediationClient.MAX_H2_CONNECTIONS_PER_ORIGIN).isGreaterThan(1);
    }
}
