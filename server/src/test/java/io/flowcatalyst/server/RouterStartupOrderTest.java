package io.flowcatalyst.server;

import io.flowcatalyst.platform.seed.RouterClientBootstrap;
import io.flowcatalyst.platform.seed.Seeder;
import io.flowcatalyst.platform.shared.encryption.Encryption;
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

/// The defect this pins: `Server#start` used to run the router's election —
/// and therefore its first configuration fetch — BEFORE the API listener was
/// bound. Since R3′ (`docs/spec/router-config-auth.md`) moved
/// `/api/dispatch/router-config` (and the `/oauth/token` endpoint the router
/// mints its credential from) onto the API listener, that ordering made the
/// router's first fetch race a listener that was not up yet: `HttpConfigSource`
/// retries 12 times, 5s apart (~60s), and the first attempt fails every time
/// the API listener has not bound yet.
///
/// The fix (this unit): `Router.start` split into `Router.build` (everything
/// except contending for leadership) and `Router#startElection` (just that) —
/// `Server#start` now calls `build` where it used to call `start`, binds BOTH
/// listeners, and only then calls `startElection`.
///
/// R-A (2026-09-12) changed the first apply itself from synchronous
/// (`RouterServer#gainLeadership` calling `applyConfiguration` on the
/// election's calling thread) to asynchronous (its own
/// `router-initial-apply` virtual thread), precisely so `Server#start`
/// never waits on a config fetch at all. That means assertion (a) below can
/// no longer read `consumerNames()` the instant `Server#start` returns — the
/// apply may still be in flight — so it polls for up to 3s instead.
///
/// This test picks TWO free ports itself (bound and released before
/// `Server#start` runs) for `FC_API_PORT` and `FC_METRICS_PORT`, points
/// `FLOWCATALYST_CONFIG_URL` and `FC_ROUTER_PLATFORM_URL` at the API port
/// (where the document and the token endpoint now live), and bootstraps a
/// real `platform:router`-holding client-credentials principal via
/// [RouterClientBootstrap] — the same idempotent upsert `fcdev start` runs —
/// against the real, migrated embedded Postgres, seeded first (a
/// client-credentials token for a role the seeder never wrote carries no
/// permissions at all: `RouterConfigEndpointTest`'s own note applies here
/// too). No queue seeding is needed beyond that because the platform
/// tenant's `DEFAULT` queue is always present in the served document (R5,
/// `RouterConfigDocumentBuilderTest#platformTenantAlwaysHasADefaultQueue`).
///
/// The assertion pins "the document was fetched from our own API listener,
/// via a real token, on the first attempt": a consumer for the platform's
/// DEFAULT queue exists within 3s of `start()`, which a first attempt
/// against an unbound port (or a rejected/unauthorized request) can never
/// meet — its retry is 5s away. Mutant: move `router.startElection()` back
/// to right after `Router.build` (before the API bind) in `Server#start` —
/// the await must time out.
class RouterStartupOrderTest {

    private static Server.Running running;

    @AfterAll
    static void stop() {
        if (running != null) {
            running.stop();
        }
    }

    @Test
    void theRouterAppliesItsOwnServedDocumentWithoutWaitingOnTheRetryWindow() throws Exception {
        int apiPort;
        int metricsPort;
        try (ServerSocket apiProbe = new ServerSocket(0); ServerSocket metricsProbe = new ServerSocket(0)) {
            apiPort = apiProbe.getLocalPort();
            metricsPort = metricsProbe.getLocalPort();
        }

        // The seeder must run before a client-credentials token is minted:
        // `platform:router` (and every other built-in role) only carries
        // permissions once its rows exist in `iam_roles`/`iam_role_permissions`
        // (`DbClaimsResolver#ceiling`) — same reasoning as
        // `RouterConfigEndpointTest`.
        new Seeder(TestPg.dataSource()).run();

        // One app key, used both to build the Env the server verifies
        // client secrets under, and to hash the router's freshly minted
        // secret the same way — mirrors the env `RouterConfigEndpointTest`
        // sets for the same reason (a client-credentials mint needs
        // FLOWCATALYST_APP_KEY to verify the OAuth client's stored secret ref).
        String appKey = Encryption.generateKey();
        Encryption encryption = Encryption.fromKeys(appKey, "").orElseThrow();
        RouterClientBootstrap.Credentials credentials = RouterClientBootstrap.bootstrap(TestPg.dataSource(), encryption);

        Env env = Env.load(Map.of(
                "FC_API_PORT", String.valueOf(apiPort),
                "FC_METRICS_PORT", String.valueOf(metricsPort),
                "FC_PLATFORM_ENABLED", "true",
                "FC_ROUTER_ENABLED", "true",
                "FC_AUTH_ALLOW_TEST_HEADERS", "true",
                "FLOWCATALYST_APP_KEY", appKey,
                "FLOWCATALYST_CONFIG_URL", "http://localhost:" + apiPort + "/api/dispatch/router-config",
                "FC_ROUTER_PLATFORM_URL", "http://localhost:" + apiPort,
                "FC_ROUTER_CLIENT_ID", credentials.clientId(),
                "FC_ROUTER_CLIENT_SECRET", credentials.secret()));

        Instant before = Instant.now();
        running = new Server(env, new Server.Mode.Platform(TestPg.dataSource()), Server.Spa.none(),
                new PrometheusRegistry()).start();

        // The document served on OUR OWN API listener must be fetched and
        // applied on the router's FIRST attempt: the always-present
        // "platform" tenant's DEFAULT queue becomes a running consumer, named
        // exactly as DispatchQueueName.compose produces it with no
        // FC_DISPATCH_QUEUE_PREFIX configured: "platform-DEFAULT"
        // (FC_DISPATCH_QUEUE_TYPE is unset here, so the document names a
        // Postgres-backed queue, never .fifo-suffixed).
        //
        // The window is the whole assertion. R-A made the first apply
        // asynchronous, so the mutant (start the election before the API
        // listener binds) no longer stalls start() itself: its first
        // attempt fails against an unbound port/unauthenticated token
        // request and HttpConfigSource's SECOND attempt, 5s later,
        // succeeds. A healthy first attempt takes milliseconds, so 3s is
        // what separates "fetched on the first attempt" from "fetched on a
        // retry".
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
