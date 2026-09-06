package io.flowcatalyst.platform.resetapproval.api;

import io.flowcatalyst.platform.passwordreset.ResetLinks;
import io.flowcatalyst.platform.principal.Principal;
import io.flowcatalyst.platform.principal.PrincipalRepository;
import io.flowcatalyst.platform.resetapproval.ResetApprovalRepository;
import io.flowcatalyst.platform.resetapproval.ResetApprovalRequest;
import io.flowcatalyst.platform.resetapproval.ResetApprovalStatus;
import io.flowcatalyst.platform.resetapproval.operations.DecideCommand;
import io.flowcatalyst.platform.resetapproval.operations.DecideResetApproval;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.Checks;
import io.flowcatalyst.platform.shared.auth.Permission;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import io.flowcatalyst.http.Exchange;
import io.flowcatalyst.http.Routes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/// `/api/reset-approvals` (spec `auth-identity.md` §8.6): the lost-device
/// reset-approval queue's admin surface. `GET` is gated by any user-write
/// permission (view is a read-side privilege here, not a dedicated one);
/// `approve` / `deny` are gated by [Checks#requireUserAdmin] against the
/// request's own client — which is why both handlers load the row before
/// checking, and why [DecideResetApproval] itself declares
/// `Authorize.publicAccess()` (CONVENTIONS §3).
///
/// | Method | Path | Status |
/// |---|---|---|
/// | GET | `/api/reset-approvals` | 200 [ResetApprovalListResponse] |
/// | POST | `/api/reset-approvals/{id}/approve` | 200 `{message}`; 404; 400 `ALREADY_DECIDED` |
/// | POST | `/api/reset-approvals/{id}/deny` | 200 `{message}`; 404; 400 `ALREADY_DECIDED` |
public final class ResetApprovalApi {

    private static final Logger LOG = LoggerFactory.getLogger(ResetApprovalApi.class);

    private ResetApprovalApi() {
    }

    public record State(ResetApprovalRepository repo, PrincipalRepository principals, ResetLinks links, UnitOfWork uow) {
        public State {
            Objects.requireNonNull(repo, "repo");
            Objects.requireNonNull(principals, "principals");
            Objects.requireNonNull(links, "links");
            Objects.requireNonNull(uow, "uow");
        }
    }

    public static void register(Routes routes, State s) {
        routes.get("/api/reset-approvals", Auth.scoped(ctx -> list(ctx, s)));
        routes.post("/api/reset-approvals/{id}/approve", Auth.scoped(ctx -> approve(ctx, s)));
        routes.post("/api/reset-approvals/{id}/deny", Auth.scoped(ctx -> deny(ctx, s)));
    }

    // ── Handlers ───────────────────────────────────────────────────────────

    private static void list(Exchange ctx, State s) {
        Checks.requireAny(Auth.current(), Permission.USER_CREATE, Permission.USER_UPDATE, Permission.USER_DELETE);
        List<ResetApprovalResponse> items = s.repo().findPending(Auth.current().visibility()).stream()
                .map(r -> response(s, r)).toList();
        ctx.json(new ResetApprovalListResponse(items));
    }

    /// §8.6: decide APPROVED, then — only on success — best-effort mail a
    /// reset link; a principal that no longer exists is a 404 even though
    /// the decision already landed.
    private static void approve(Exchange ctx, State s) {
        String id = ctx.pathParam("id");
        ResetApprovalRequest request = load(s, id);
        Checks.requireUserAdmin(Auth.current(), request.clientId());
        DecideResetApproval.of(s.repo())
                .run(s.uow(), new DecideCommand(id, ResetApprovalStatus.APPROVED, noteFrom(ctx)), Auth.executionContext());
        Principal p = s.principals().findById(request.principalId())
                .orElseThrow(() -> HttpError.notFound("Principal", request.principalId()));
        try {
            s.links().sendResetEmail(p, request.reset2fa());
        } catch (RuntimeException e) {
            LOG.warn("reset approval email not sent principal={}", p.id(), e);
        }
        ctx.json(Map.of("message", "Reset approved — the user has been emailed a link"));
    }

    private static void deny(Exchange ctx, State s) {
        String id = ctx.pathParam("id");
        ResetApprovalRequest request = load(s, id);
        Checks.requireUserAdmin(Auth.current(), request.clientId());
        DecideResetApproval.of(s.repo())
                .run(s.uow(), new DecideCommand(id, ResetApprovalStatus.DENIED, noteFrom(ctx)), Auth.executionContext());
        ctx.json(Map.of("message", "Reset request denied"));
    }

    // ── Read-side helpers ──────────────────────────────────────────────────

    private static ResetApprovalRequest load(State s, String id) {
        return s.repo().findById(id).orElseThrow(() -> HttpError.notFound("ResetApprovalRequest", id));
    }

    private static String noteFrom(Exchange ctx) {
        String body = ctx.body();
        return body == null || body.isBlank() ? null : ctx.bodyAsClass(DecideRequest.class).note();
    }

    /// Row → wire, with the principal's e-mail / name resolved live —
    /// blank when the principal is gone (spec §8.6).
    private static ResetApprovalResponse response(State s, ResetApprovalRequest r) {
        var p = s.principals().findById(r.principalId());
        String email = p.map(Principal::email).orElse("");
        String name = p.map(Principal::name).orElse("");
        return new ResetApprovalResponse(r.id(), r.principalId(), email == null ? "" : email, name,
                r.clientId(), r.expiresAt(), r.createdAt());
    }

    // ── Wire DTOs ────────────────────────────────────────────────────────────

    /// The optional body of `POST …/approve` and `POST …/deny` — a
    /// reviewer's note persisted alongside the decision (defect 11).
    public record DecideRequest(String note) {
    }

    public record ResetApprovalResponse(
            String id,
            String principalId,
            String email,
            String name,
            String clientId,
            Instant expiresAt,
            Instant createdAt) {
    }

    /// `{"requests": [...]}` — no total (spec §8.6).
    public record ResetApprovalListResponse(List<ResetApprovalResponse> requests) {
        public ResetApprovalListResponse {
            requests = requests == null ? List.of() : List.copyOf(requests);
        }
    }
}
