package io.flowcatalyst.platform.dispatch.api;

import io.flowcatalyst.http.Routes;
import io.flowcatalyst.platform.dispatch.RouterConfigDocumentBuilder;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.AuthContext;
import io.flowcatalyst.platform.shared.auth.Checks;
import io.flowcatalyst.platform.shared.httperror.HttpError;

import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import static io.flowcatalyst.platform.shared.auth.Permission.DISPATCH_POOL_VIEW;

/// `GET /api/dispatch/router-config` (`docs/spec/router-config-auth.md`,
/// R3′): the router's `{processingPools, queues}` document, served on the
/// **API listener** behind the platform's ordinary bearer authentication —
/// superseding R3, which served the same body unauthenticated on the
/// internal listener ([io.flowcatalyst.server.Metrics]).
///
/// Platform mode only — [io.flowcatalyst.server.Platform#register] is the
/// only caller, and it never runs outside [io.flowcatalyst.server.Server.Mode.Platform].
///
/// ### Authorization is the ordinary anchor-reach + permission gate
///
/// [Checks#requireAnchor] keeps out every client-scoped caller (the document
/// lists every client's queues); [Checks#require] then requires
/// `DISPATCH_POOL_VIEW` from the caller's roles like any other permission —
/// permissions always come from roles at every tier, anchor included
/// (`docs/spec/permissions-from-roles.md`), so a provisioned application
/// service account with no `platform:router` role is refused exactly as
/// spec §5.1 describes ("403 with a token whose principal lacks the
/// permission"). Un-authenticated is a 401, not the platform's usual 403
/// `UNAUTHENTICATED` (`docs/spec/router-config-auth.md` §5.1) — the same
/// override [io.flowcatalyst.platform.ingest.api.IngestApi]'s audit-batch
/// route uses.
public final class RouterConfigApi {

    private RouterConfigApi() {
    }

    /// The handler's one dependency: the document builder R3 used, supplied
    /// **per request** rather than built at wiring time. The builder needs the
    /// dispatch-queue settings, and resolving those refuses an SQS deployment
    /// without a queue prefix (`DispatchQueueSettings.resolve`) — the right
    /// refusal for the scheduler, which would otherwise publish to nonsense
    /// queue names, but no reason for the API tier to fail to boot: nothing on
    /// it publishes. So the platform passes a supplier, a misconfiguration
    /// surfaces here as a 503 on this one route, and the boot goes ahead
    /// (owner, 2026-09-14: "it should not use that when not running the outbox
    /// or dispatch-job scheduler").
    public record State(Supplier<RouterConfigDocumentBuilder> document) {
        public State {
            Objects.requireNonNull(document, "document");
        }

        /// A builder fixed at construction — tests and callers that already
        /// hold one.
        public static State of(RouterConfigDocumentBuilder document) {
            Objects.requireNonNull(document, "document");
            return new State(() -> document);
        }
    }

    /// The code answered when the document cannot be built because the
    /// dispatch-queue settings are unusable (an SQS deployment without
    /// `FC_DISPATCH_QUEUE_PREFIX`, or without an account id / region).
    public static final String UNCONFIGURED = "DISPATCH_QUEUE_UNCONFIGURED";

    public static void register(Routes routes, State s) {
        routes.get("/api/dispatch/router-config", Auth.scoped(ctx -> {
            AuthContext ac = Auth.current();
            if (ac == null) {
                HttpError.unauthorized(ctx, "authentication required");
                return;
            }
            Checks.requireAnchor(ac);
            Checks.require(ac, DISPATCH_POOL_VIEW);
            RouterConfigDocumentBuilder builder;
            try {
                builder = s.document().get();
            } catch (IllegalStateException e) {
                HttpError.write(ctx, 503, UNCONFIGURED, e.getMessage(), Map.of());
                return;
            }
            ctx.json(builder.build());
        }));
    }
}
