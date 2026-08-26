package io.flowcatalyst.router.api;

import io.flowcatalyst.router.api.RouterApi.State;
import io.javalin.http.Context;
import io.javalin.router.JavalinDefaultRoutingApi;

import java.util.concurrent.ThreadLocalRandom;

/// The dev mock targets (§9.1) — endpoints the router can be pointed at to
/// exercise a pool without a real downstream.
///
/// Always mounted, as in Go. They are the only handlers here that own mutable
/// state ([RouterApi.MockCounters]), and it is theirs alone: nothing else
/// reads it.
final class MockRoutes {

    /// Mounts this group. Called by [RouterApi#register].
    static void register(JavalinDefaultRoutingApi routes, State s) {
        var p = s.prefix();
        routes.post(p + "/api/test/fast", ctx -> testFast(ctx, s));
        routes.post(p + "/api/test/success", ctx -> testSuccess(ctx, s));
        routes.post(p + "/api/test/slow", ctx -> testSlow(ctx, s));
        routes.post(p + "/api/test/faulty", ctx -> testFaulty(ctx, s));
        routes.post(p + "/api/test/fail", ctx -> testFail(ctx, s));
        routes.post(p + "/api/test/server-error", ctx -> testServerError(ctx, s));
        routes.post(p + "/api/test/client-error", ctx -> testClientError(ctx, s));
        routes.post(p + "/api/test/pending", ctx -> testPending(ctx, s));
        routes.get(p + "/api/test/stats", ctx -> testStats(ctx, s));
        routes.post(p + "/api/test/stats/reset", ctx -> testStatsReset(ctx, s));
        
        // Benchmark aliases of the same handlers, as in Go.
        routes.post(p + "/api/benchmark/process", ctx -> testFast(ctx, s));
        routes.post(p + "/api/benchmark/process-slow", ctx -> testSlow(ctx, s));
        routes.get(p + "/api/benchmark/stats", ctx -> testStats(ctx, s));
        routes.post(p + "/api/benchmark/reset", ctx -> testStatsReset(ctx, s));
    }

    private static void testFast(Context ctx, State s) {
        s.mocks().fast.incrementAndGet();
        ctx.json(new Wire.MockOkResponse(true, "fast"));
    }

    private static void testSuccess(Context ctx, State s) {
        s.mocks().success.incrementAndGet();
        ctx.json(new Wire.MockOkResponse(true, "success"));
    }

    private static void testSlow(Context ctx, State s) {
        s.mocks().slow.incrementAndGet();
        long delayMs = Http.queryInt(ctx, "delay_ms", 0);
        if (delayMs <= 0 || delayMs > 30_000) {
            delayMs = 500;
        }
        sleep(delayMs);
        ctx.json(new Wire.MockOkResponse(true, "slow"));
    }

    private static void testFaulty(Context ctx, State s) {
        s.mocks().faulty.incrementAndGet();
        if (ThreadLocalRandom.current().nextInt(2) == 0) {
            s.mocks().faultyFail.incrementAndGet();
            ctx.status(500).json(new Http.ErrorBody("faulty endpoint randomly failed"));
            return;
        }
        s.mocks().faultySuccess.incrementAndGet();
        ctx.json(new Wire.MockOkResponse(true, "faulty"));
    }

    private static void testFail(Context ctx, State s) {
        s.mocks().fail.incrementAndGet();
        ctx.status(500).json(new Http.ErrorBody("test/fail"));
    }

    private static void testServerError(Context ctx, State s) {
        s.mocks().serverError.incrementAndGet();
        ctx.status(500).json(new Http.ErrorBody("test/server-error"));
    }

    private static void testClientError(Context ctx, State s) {
        s.mocks().clientError.incrementAndGet();
        ctx.status(400).json(new Http.ErrorBody("test/client-error"));
    }

    private static void testPending(Context ctx, State s) {
        s.mocks().pending.incrementAndGet();
        sleep(30_000);
        ctx.json(new Wire.MockOkResponse(true, "pending"));
    }

    private static void testStats(Context ctx, State s) {
        var m = s.mocks();
        ctx.json(new Wire.MockStatsResponse(m.fast.get(), m.slow.get(), m.faulty.get(), m.faultySuccess.get(),
                m.faultyFail.get(), m.fail.get(), m.success.get(), m.pending.get(), m.clientError.get(),
                m.serverError.get()));
    }

    private static void testStatsReset(Context ctx, State s) {
        s.mocks().reset();
        ctx.json(new Wire.ResetResponse(true));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private MockRoutes() {
    }
}
