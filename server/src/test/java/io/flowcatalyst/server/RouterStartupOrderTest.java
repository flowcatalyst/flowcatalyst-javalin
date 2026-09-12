package io.flowcatalyst.server;

import io.flowcatalyst.testpg.TestPg;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// The defect this pins: `Server#start` used to start the router BEFORE
/// binding the internal (metrics) listener. Since fcdev points the router's
/// own `FLOWCATALYST_CONFIG_URL` at that SAME listener's
/// `/api/dispatch/router-config` route (R3, `docs/spec/deployed-dispatch.md`
/// §3), that ordering made the router's first fetch race a listener that was
/// not up yet: `HttpConfigSource` retries 12 times, 5s apart (~60s), fails
/// every one of them, and the router ran with no queues until the next
/// 5-minute config poll.
///
/// R-A (2026-09-12) changed the first apply itself from synchronous
/// (`RouterServer#gainLeadership` calling `applyConfiguration` on the
/// election's calling thread) to asynchronous (its own
/// `router-initial-apply` virtual thread), precisely so `Server#start`
/// never waits on a config fetch at all. That means assertion (a) below can
/// no longer read `consumerNames()` the instant `Server#start` returns — the
/// apply may still be in flight — so it polls for up to 10s instead. The
/// test still pins what matters: the document is fetched from our own,
/// already-bound internal listener promptly, rather than only after
/// `HttpConfigSource`'s ~60s retry window (which 10s is comfortably under).
///
/// This test picks the internal listener's port itself (an unused ephemeral
/// port, bound and released before `Server#start` runs) so the config URL is
/// knowable up front, points `FLOWCATALYST_CONFIG_URL` at that same port, and
/// boots the real [Server] against the migrated embedded Postgres — no
/// seeding is needed because the platform tenant's `DEFAULT` queue is always
/// present in the served document (R5,
/// `RouterConfigDocumentBuilderTest#platformTenantAlwaysHasADefaultQueue`).
///
/// The assertion pins "the document was fetched from our own listener on
/// the first attempt": a consumer for the platform's DEFAULT queue exists
/// within 3 s of start(), which a first attempt against an unbound port can
/// never meet (its retry is 5 s away). Mutant: move the `Metrics` bind back
/// below `Router.start` in `Server#start` — the await times out.
class RouterStartupOrderTest {

    private static Server.Running running;

    @AfterAll
    static void stop() {
        if (running != null) {
            running.stop();
        }
    }

    @Test
    void theRouterAppliesItsOwnServedDocumentWithoutWaitingOnTheRetryWindow() throws IOException {
        int metricsPort;
        try (ServerSocket probe = new ServerSocket(0)) {
            metricsPort = probe.getLocalPort();
        }

        Env env = Env.load(Map.of(
                "FC_API_PORT", "0",
                "FC_METRICS_PORT", String.valueOf(metricsPort),
                "FC_PLATFORM_ENABLED", "true",
                "FC_ROUTER_ENABLED", "true",
                "FC_AUTH_ALLOW_TEST_HEADERS", "true",
                "FLOWCATALYST_CONFIG_URL", "http://localhost:" + metricsPort + "/api/dispatch/router-config"));

        Instant before = Instant.now();
        running = new Server(env, new Server.Mode.Platform(TestPg.dataSource()), Server.Spa.none(),
                new PrometheusRegistry()).start();

        // The document served on OUR OWN internal listener must be fetched
        // and applied on the router's FIRST attempt: the always-present
        // "platform" tenant's DEFAULT queue becomes a running consumer, named
        // exactly as DispatchQueueName.compose produces it with no
        // FC_DISPATCH_QUEUE_PREFIX configured: "platform-DEFAULT"
        // (FC_DISPATCH_QUEUE_TYPE is unset here, so the document names a
        // Postgres-backed queue, never .fifo-suffixed).
        //
        // The window is the whole assertion. R-A made the first apply
        // asynchronous, so the mutant (bind the internal listener AFTER the
        // router starts) no longer stalls start(): its first attempt fails
        // against an unbound port and HttpConfigSource's SECOND attempt, 5s
        // later, succeeds. A healthy first attempt takes milliseconds, so 3s
        // is what separates "fetched on the first attempt" from "fetched on
        // a retry". Anything looser than the 5s retry interval would pass on
        // the mutant. start() returning promptly at all is pinned by
        // RouterServerTest's startReturnsWhileTheFirstFetchIsStillInFlight.
        await(() -> running.router().manager().consumerNames().contains("platform-DEFAULT"),
                Duration.ofSeconds(3));
        assertThat(Duration.between(before, Instant.now()))
                .as("start plus the first apply, on the first attempt")
                .isLessThan(Duration.ofSeconds(5));
    }

    private static void await(java.util.function.BooleanSupplier condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
        assertThat(condition.getAsBoolean()).as("condition met within " + timeout).isTrue();
    }
}
