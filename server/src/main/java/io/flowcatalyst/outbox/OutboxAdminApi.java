package io.flowcatalyst.outbox;

import io.flowcatalyst.http.Budgets;
import io.flowcatalyst.http.Group;
import io.flowcatalyst.http.RequestWorkers;
import io.flowcatalyst.http.Routes;
import io.flowcatalyst.http.vertx.VertxListener;

import java.util.Map;

/// The loopback-only outbox admin surface (spec §7): `GET /outbox/groups`,
/// `GET /outbox/groups/blocked`, `POST
/// /outbox/groups/{group}/{pause|resume|unblock|skip}`. Mounted only when
/// `FC_OUTBOX_ADMIN_PORT > 0`, bound to `127.0.0.1` — never the API
/// listener — mirroring Go's shape verbatim per the spec §8 Q1 owner ruling
/// (keep Go's shape; the router-prefix alternative is not taken here).
public final class OutboxAdminApi {

    private OutboxAdminApi() {
    }

    /// Builds, registers and starts a loopback [VertxListener] on `port`.
    public static Running start(OutboxProcessor processor, int port) {
        // Unbounded (Group.NO_DB): an operator control surface, not a
        // database-bound request — nothing here should ever queue behind
        // another admin call. `Options.local` binds 127.0.0.1, never the API
        // listener's 0.0.0.0.
        var options = VertxListener.Options.local(port, Budgets.none(), RequestWorkers.of(1, Map.of()));
        var listener = VertxListener.start(options, routes -> register(routes.in(Group.NO_DB), processor));
        return new Running(listener);
    }

    /// A bound outbox admin listener.
    public static final class Running {
        private final VertxListener listener;

        private Running(VertxListener listener) {
            this.listener = listener;
        }

        public int port() {
            return listener.port();
        }

        public void stop() {
            listener.close();
        }
    }

    /// Route registration — the `XxxApi.register(routes, state)` shape
    /// CONVENTIONS §1 uses everywhere else, so a test can register against
    /// [io.flowcatalyst.platform.shared.TestHttp] instead of binding a real
    /// loopback listener.
    public static void register(Routes routes, OutboxProcessor processor) {
        routes.get("/outbox/groups", ctx -> ctx.json(processor.groupStates()));
        routes.get("/outbox/groups/blocked", ctx -> ctx.json(processor.blockedGroups()));
        routes.post("/outbox/groups/{group}/pause", ctx -> {
            processor.pauseGroup(ctx.pathParam("group"));
            ctx.status(200);
        });
        routes.post("/outbox/groups/{group}/resume", ctx -> {
            processor.resumeGroup(ctx.pathParam("group"));
            ctx.status(200);
        });
        routes.post("/outbox/groups/{group}/unblock", ctx ->
                ctx.status(processor.unblockGroup(ctx.pathParam("group")) ? 200 : 404));
        routes.post("/outbox/groups/{group}/skip", ctx ->
                ctx.status(processor.skipGroup(ctx.pathParam("group")) ? 200 : 404));
    }
}
