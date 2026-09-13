package io.flowcatalyst.platform.dispatch.api;

import io.flowcatalyst.http.Routes;
import io.flowcatalyst.platform.dispatch.RouterConfigDocumentBuilder;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.AuthContext;
import io.flowcatalyst.platform.shared.auth.Checks;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.flowcatalyst.sdk.usecase.UseCaseException;

import java.util.Objects;

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
/// ### Authorization is stricter than [Checks#require]'s usual anchor bypass
///
/// [Checks#require] treats `AuthContext#isAnchor()` as holding every
/// permission, by design (`Checks`' own class doc: "Anchors pass every
/// permission check"). A provisioned application service account
/// ([io.flowcatalyst.platform.principal.Principal#newService]) is itself
/// anchor-scoped, so `Checks.require(ac, DISPATCH_POOL_VIEW)` would let ANY
/// such account through with no `platform:router` role at all — silently
/// defeating the one point of the new role (spec §5.1: "403 with a token
/// whose principal lacks the permission (a provisioned application service
/// account)"). This handler therefore checks [AuthContext#hasPermission]
/// directly, after [Checks#requireAnchor] has already kept out every
/// client-scoped caller (the document lists every client's queues).
/// Un-authenticated is a 401, not the platform's usual 403 `UNAUTHENTICATED`
/// (`docs/spec/router-config-auth.md` §5.1) — the same override
/// [io.flowcatalyst.platform.ingest.api.IngestApi]'s audit-batch route uses.
public final class RouterConfigApi {

    private RouterConfigApi() {
    }

    /// The handler's one dependency: the same document builder R3 used.
    public record State(RouterConfigDocumentBuilder document) {
        public State {
            Objects.requireNonNull(document, "document");
        }
    }

    public static void register(Routes routes, State s) {
        routes.get("/api/dispatch/router-config", Auth.scoped(ctx -> {
            AuthContext ac = Auth.current();
            if (ac == null) {
                HttpError.unauthorized(ctx, "authentication required");
                return;
            }
            Checks.requireAnchor(ac);
            if (!ac.hasPermission(DISPATCH_POOL_VIEW)) {
                throw UseCaseException.authorization("PERMISSION_REQUIRED",
                        "permission required: " + DISPATCH_POOL_VIEW.code());
            }
            ctx.json(s.document().build());
        }));
    }
}
