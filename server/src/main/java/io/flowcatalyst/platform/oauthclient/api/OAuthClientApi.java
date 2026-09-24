package io.flowcatalyst.platform.oauthclient.api;

import io.flowcatalyst.platform.oauthclient.OAuthClient;
import io.flowcatalyst.platform.oauthclient.OAuthClientRepository;
import io.flowcatalyst.platform.portalapp.PortalApp;
import io.flowcatalyst.platform.portalapp.PortalAppRepository;
import io.flowcatalyst.platform.principal.PrincipalRepository;
import io.flowcatalyst.platform.oauthclient.operations.ActivateOAuthClientCommand;
import io.flowcatalyst.platform.oauthclient.operations.ActivateOAuthClient;
import io.flowcatalyst.platform.oauthclient.operations.CreateOAuthClientCommand;
import io.flowcatalyst.platform.oauthclient.operations.CreateOAuthClient;
import io.flowcatalyst.platform.oauthclient.operations.DeactivateOAuthClientCommand;
import io.flowcatalyst.platform.oauthclient.operations.DeactivateOAuthClient;
import io.flowcatalyst.platform.oauthclient.operations.DeleteOAuthClientCommand;
import io.flowcatalyst.platform.oauthclient.operations.DeleteOAuthClient;
import io.flowcatalyst.platform.oauthclient.operations.RevokeOAuthClientPreviousSecret;
import io.flowcatalyst.platform.oauthclient.operations.RevokeOAuthClientPreviousSecretCommand;
import io.flowcatalyst.platform.oauthclient.operations.RotateOAuthClientSecret;
import io.flowcatalyst.platform.oauthclient.operations.RotateOAuthClientSecretCommand;
import io.flowcatalyst.platform.oauthclient.operations.UpdateOAuthClientCommand;
import io.flowcatalyst.platform.oauthclient.operations.UpdateOAuthClient;
import io.flowcatalyst.platform.shared.apicommon.SuccessResponse;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.Checks;
import io.flowcatalyst.platform.shared.encryption.Encryption;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import io.flowcatalyst.http.Exchange;
import io.flowcatalyst.http.Handler;
import io.flowcatalyst.http.Group;
import io.flowcatalyst.http.Routes;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static io.flowcatalyst.platform.shared.auth.Permission.OAUTH_CLIENT_CREATE;
import static io.flowcatalyst.platform.shared.auth.Permission.OAUTH_CLIENT_DELETE;
import static io.flowcatalyst.platform.shared.auth.Permission.OAUTH_CLIENT_REGENERATE_SECRET;
import static io.flowcatalyst.platform.shared.auth.Permission.OAUTH_CLIENT_UPDATE;
import static io.flowcatalyst.platform.shared.auth.Permission.OAUTH_CLIENT_VIEW;

/// The `/api/oauth-clients` surface (spec `auth-core.md` §6.3). **Every
/// route is anchor-only** (`Checks.requireAnchor`, checked first in every
/// handler), followed by the permission gate (`docs/spec/reach-only-routes.md`)
/// — the ten routes the lockfile documents, plus
/// `revoke-previous-secret` (A-22, `docs/improvements.md`; not yet in the
/// vendored lockfile — see the class-level gap note below). A write handler
/// does exactly: anchor gate → command from DTO → `Operation.run` →
/// response; the plaintext secret on create/rotate comes back from the
/// operation via a local sink, never a process-wide stash (no concurrent
/// request can observe another's plaintext).
///
/// | Method | Path | Status |
/// |---|---|---|
/// | GET | `/api/oauth-clients` | 200 [OAuthClientListResponse] |
/// | POST | `/api/oauth-clients` | 201 [CreateOAuthClientResponse] |
/// | GET | `/api/oauth-clients/{id}` | 200 [OAuthClientResponse]; 404 |
/// | GET | `/api/oauth-clients/by-client-id/{clientId}` | 200; 404 |
/// | PUT | `/api/oauth-clients/{id}` | 204 |
/// | POST | `/api/oauth-clients/{id}/activate` | 200 [SuccessResponse] |
/// | POST | `/api/oauth-clients/{id}/deactivate` | 200 [SuccessResponse] |
/// | POST | `/api/oauth-clients/{id}/rotate-secret` (+`regenerate-secret` SDK alias) | 200 [RotateOAuthClientSecretResponse] |
/// | POST | `/api/oauth-clients/{id}/revoke-previous-secret` | 200 [SuccessResponse] |
/// | DELETE | `/api/oauth-clients/{id}` | 204 |
///
/// **Gap, not improvised around (report, don't silently patch):** the
/// vendored `openapi.lock.json` predates the A-22 secret-rotation-grace
/// ruling — it has no `revoke-previous-secret` path and no
/// `graceSeconds`/`previousSecretExpiresAt`/`previousSecretLastUsedAt`
/// fields on the rotate/response schemas. CONVENTIONS forbids hand-editing
/// the lockfile, so this route is listed in `LockfileCoverageTestOAuthClient`'s
/// (or `LockfileCoverageTest`'s) exact-route exemption list rather than the
/// contract itself; the extra response fields are additive and unenforced
/// at runtime (no response-schema validation in this stack), so they cost
/// nothing to add ahead of the lockfile catching up.
public final class OAuthClientApi {

    private OAuthClientApi() {
    }

    /// The handlers' dependencies. `encryption` is `Optional` at construction
    /// (spec §6.3: a write that carries a secret with no `FLOWCATALYST_APP_KEY`
    /// configured fails 500 `SECRET`). `portalApps` resolves `portalAppId` on
    /// create/update (spec §4.5) — read-only here, this surface never writes
    /// a portal app. `principals` resolves a create's `principalId`
    /// (security-fixes S1.4: it must be an active, reachable `SERVICE` principal).
    public record State(OAuthClientRepository repo, UnitOfWork uow, Optional<Encryption> encryption,
                        PortalAppRepository portalApps, PrincipalRepository principals) {
        public State {
            Objects.requireNonNull(repo, "repo");
            Objects.requireNonNull(uow, "uow");
            Objects.requireNonNull(encryption, "encryption");
            Objects.requireNonNull(portalApps, "portalApps");
            Objects.requireNonNull(principals, "principals");
        }
    }

    public static void register(Routes routes, State s) {
        Routes write = routes.in(Group.API_WRITE);
        routes.get("/api/oauth-clients", Auth.scoped(ctx -> list(ctx, s)));
        write.post("/api/oauth-clients", Auth.scoped(ctx -> create(ctx, s)));
        routes.get("/api/oauth-clients/by-client-id/{clientId}", Auth.scoped(ctx -> getByClientId(ctx, s)));
        routes.get("/api/oauth-clients/{id}", Auth.scoped(ctx -> getById(ctx, s)));
        write.put("/api/oauth-clients/{id}", Auth.scoped(ctx -> update(ctx, s)));
        write.post("/api/oauth-clients/{id}/activate", Auth.scoped(ctx -> activate(ctx, s)));
        write.post("/api/oauth-clients/{id}/deactivate", Auth.scoped(ctx -> deactivate(ctx, s)));

        Handler rotate = Auth.scoped(ctx -> rotateSecret(ctx, s));
        write.post("/api/oauth-clients/{id}/rotate-secret", rotate);
        write.post("/api/oauth-clients/{id}/regenerate-secret", rotate); // SDK alias

        write.post("/api/oauth-clients/{id}/revoke-previous-secret", Auth.scoped(ctx -> revokePreviousSecret(ctx, s)));
        write.delete("/api/oauth-clients/{id}", Auth.scoped(ctx -> delete(ctx, s)));
    }

    // ── Handlers ───────────────────────────────────────────────────────────

    private static void list(Exchange ctx, State s) {
        Checks.requireAnchor(Auth.current());
        Checks.require(Auth.current(), OAUTH_CLIENT_VIEW);
        List<OAuthClientResponse> items = s.repo().findAll().stream().map(c -> response(s, c)).toList();
        ctx.json(new OAuthClientListResponse(items));
    }

    private static void getById(Exchange ctx, State s) {
        Checks.requireAnchor(Auth.current());
        Checks.require(Auth.current(), OAUTH_CLIENT_VIEW);
        String id = ctx.pathParam("id");
        OAuthClient c = s.repo().findById(id).orElseThrow(() -> HttpError.notFound("OAuthClient", id));
        ctx.json(response(s, c));
    }

    private static void getByClientId(Exchange ctx, State s) {
        Checks.requireAnchor(Auth.current());
        Checks.require(Auth.current(), OAUTH_CLIENT_VIEW);
        String clientId = ctx.pathParam("clientId");
        OAuthClient c = s.repo().findByClientId(clientId).orElseThrow(() -> HttpError.notFound("OAuthClient", clientId));
        ctx.json(response(s, c));
    }

    private static void create(Exchange ctx, State s) {
        Checks.requireAnchor(Auth.current());
        Checks.require(Auth.current(), OAUTH_CLIENT_CREATE);
        var req = ctx.bodyAsClass(CreateOAuthClientRequest.class);
        String resolvedPortalClientId = resolvePortalClientId(s, req.portalAppId(), req.portalClientId());
        var cmd = req.toCommand(resolvedPortalClientId);
        // Local sink: the plaintext cannot outlive this request, and is only
        // read below, on the success path — a failed commit discloses nothing.
        var secret = new AtomicReference<String>();
        var event = CreateOAuthClient.of(s.repo(), s.principals(), s.encryption(), secret::set).run(s.uow(), cmd, Auth.executionContext());
        OAuthClient created = s.repo().findById(event.oauthClientId())
                .orElseThrow(() -> HttpError.internal("REPO", "oauth client created but row not found", null));
        ctx.status(201).json(new CreateOAuthClientResponse(response(s, created), secret.get()));
    }

    private static void update(Exchange ctx, State s) {
        Checks.requireAnchor(Auth.current());
        Checks.require(Auth.current(), OAUTH_CLIENT_UPDATE);
        var req = ctx.bodyAsClass(UpdateOAuthClientRequest.class);
        String resolvedPortalClientId = resolvePortalClientId(s, req.portalAppId(), req.portalClientId());
        var cmd = req.toCommand(ctx.pathParam("id"), resolvedPortalClientId);
        UpdateOAuthClient.of(s.repo()).run(s.uow(), cmd, Auth.executionContext());
        ctx.status(204);
    }

    /// The §4.5 controller pre-check, shared by create and update: a
    /// non-blank `portalAppId` must resolve to a real app (`PortalApp_NOT_FOUND`),
    /// a non-blank `portalClientId` that names a DIFFERENT client is a 400
    /// `PORTAL_APP_CLIENT_MISMATCH`, and the app's own client always wins —
    /// the returned value is what the command's `portalClientId` becomes,
    /// overriding whatever the request carried (including a blank one: a
    /// non-blank `portalAppId` always implies its owning client). A blank/`null`
    /// `portalAppId` passes `portalClientId` through unchanged (`null` =
    /// untouched, `""` = clear, a value = set — spec §6.3).
    private static String resolvePortalClientId(State s, String portalAppId, String portalClientId) {
        if (portalAppId == null || portalAppId.isBlank()) {
            return portalClientId;
        }
        PortalApp app = s.portalApps().findById(portalAppId)
                .orElseThrow(() -> HttpError.notFound("PortalApp", portalAppId));
        if (portalClientId != null && !portalClientId.isBlank() && !portalClientId.equals(app.clientId())) {
            throw HttpError.badRequest("PORTAL_APP_CLIENT_MISMATCH",
                    "portalAppId belongs to a different client than portalClientId");
        }
        return app.clientId();
    }

    private static void activate(Exchange ctx, State s) {
        Checks.requireAnchor(Auth.current());
        Checks.require(Auth.current(), OAUTH_CLIENT_UPDATE);
        ActivateOAuthClient.of(s.repo()).run(s.uow(), new ActivateOAuthClientCommand(ctx.pathParam("id")), Auth.executionContext());
        ctx.json(new SuccessResponse(true, "OAuth client activated"));
    }

    private static void deactivate(Exchange ctx, State s) {
        Checks.requireAnchor(Auth.current());
        Checks.require(Auth.current(), OAUTH_CLIENT_UPDATE);
        DeactivateOAuthClient.of(s.repo()).run(s.uow(), new DeactivateOAuthClientCommand(ctx.pathParam("id")), Auth.executionContext());
        ctx.json(new SuccessResponse(true, "OAuth client deactivated"));
    }

    private static void rotateSecret(Exchange ctx, State s) {
        Checks.requireAnchor(Auth.current());
        Checks.require(Auth.current(), OAUTH_CLIENT_REGENERATE_SECRET);
        String id = ctx.pathParam("id");
        Long graceSeconds = ctx.body().isBlank() ? null : ctx.bodyAsClass(RotateOAuthClientSecretRequest.class).graceSeconds();
        var secret = new AtomicReference<String>();
        var event = RotateOAuthClientSecret.of(s.repo(), s.encryption(), secret::set)
                .run(s.uow(), new RotateOAuthClientSecretCommand(id, graceSeconds), Auth.executionContext());
        OAuthClient c = s.repo().findById(event.oauthClientId()).orElseThrow(() -> HttpError.notFound("OAuthClient", id));
        ctx.json(new RotateOAuthClientSecretResponse(c.clientId(), secret.get(), event.previousSecretExpiresAt()));
    }

    private static void revokePreviousSecret(Exchange ctx, State s) {
        Checks.requireAnchor(Auth.current());
        Checks.require(Auth.current(), OAUTH_CLIENT_REGENERATE_SECRET);
        RevokeOAuthClientPreviousSecret.of(s.repo())
                .run(s.uow(), new RevokeOAuthClientPreviousSecretCommand(ctx.pathParam("id")), Auth.executionContext());
        ctx.json(new SuccessResponse(true, "Previous client secret revoked"));
    }

    private static void delete(Exchange ctx, State s) {
        Checks.requireAnchor(Auth.current());
        Checks.require(Auth.current(), OAUTH_CLIENT_DELETE);
        DeleteOAuthClient.of(s.repo()).run(s.uow(), new DeleteOAuthClientCommand(ctx.pathParam("id")), Auth.executionContext());
        ctx.status(204);
    }

    // ── Read-side helpers ──────────────────────────────────────────────────

    private static OAuthClientResponse response(State s, OAuthClient c) {
        return OAuthClientResponse.from(c, s.repo().applicationRefs(c.applicationIds()).stream()
                .map(OAuthClientApplicationRef::from).toList());
    }

    // ── Wire DTOs (lockfile components + A-22 additions) ────────────────────

    /// Body of `POST /api/oauth-clients`. The OAuth2 `client_id` is NOT part
    /// of the request — it is always backend-generated. `defaultScopes` is
    /// `array<string>` (Fix 5, `oauthapi-fixes.md`) — no legacy `scopes` alias.
    public record CreateOAuthClientRequest(
            String clientName,
            String clientType,
            List<String> redirectUris,
            List<String> grantTypes,
            List<String> defaultScopes,
            Boolean pkceRequired,
            List<String> postLogoutRedirectUris,
            List<String> allowedOrigins,
            List<String> applicationIds,
            String principalId,
            String portalClientId,
            String portalAppId,
            Boolean apiAccess) {
        /// @param resolvedPortalClientId [#resolvePortalClientId]'s answer —
        ///                               NOT `this.portalClientId` verbatim,
        ///                               since a non-blank `portalAppId` overrides it (spec §4.5)
        public CreateOAuthClientCommand toCommand(String resolvedPortalClientId) {
            return new CreateOAuthClientCommand(null, clientName, clientType, redirectUris, postLogoutRedirectUris, grantTypes,
                    defaultScopes, allowedOrigins, applicationIds, principalId, pkceRequired, resolvedPortalClientId,
                    portalAppId, apiAccess);
        }
    }

    /// Body of `PUT /api/oauth-clients/{id}`. Every field but the path id is
    /// optional: `null` = untouched, empty list = clear, `portalClientId` /
    /// `portalAppId` blank = clear (spec §6.3, §4.5).
    public record UpdateOAuthClientRequest(
            String clientName,
            List<String> redirectUris,
            List<String> postLogoutRedirectUris,
            List<String> grantTypes,
            List<String> defaultScopes,
            List<String> allowedOrigins,
            List<String> applicationIds,
            Boolean pkceRequired,
            String portalClientId,
            String portalAppId,
            Boolean apiAccess) {
        /// @param resolvedPortalClientId [#resolvePortalClientId]'s answer
        /// `portalClientId: ""` clears both the portal flag and the app link
        /// (spec §4.5) — `effectivePortalAppId` forces `""` whenever the
        /// resolved client id is an explicit clear, regardless of what this
        /// request's own `portalAppId` said (a non-blank `portalAppId` never
        /// reaches here with a clearing `resolvedPortalClientId`: [#resolvePortalClientId]
        /// always makes the app's own client win first).
        public UpdateOAuthClientCommand toCommand(String id, String resolvedPortalClientId) {
            boolean clearingPortalClientId = resolvedPortalClientId != null && resolvedPortalClientId.isBlank();
            String effectivePortalAppId = clearingPortalClientId ? "" : portalAppId;
            return new UpdateOAuthClientCommand(id, clientName, redirectUris, postLogoutRedirectUris, grantTypes, defaultScopes,
                    allowedOrigins, applicationIds, pkceRequired, resolvedPortalClientId, effectivePortalAppId, apiAccess);
        }
    }

    /// The optional body of `POST /{id}/rotate-secret` (and its
    /// `regenerate-secret` alias); sent body-less, the outgoing secret keeps
    /// working for the default grace.
    public record RotateOAuthClientSecretRequest(Long graceSeconds) {
    }

    /// The `{id, name}` display form of one application id
    /// ([OAuthClientRepository.ApplicationRef]).
    public record OAuthClientApplicationRef(String id, String name) {
        public static OAuthClientApplicationRef from(OAuthClientRepository.ApplicationRef ref) {
            return new OAuthClientApplicationRef(ref.id(), ref.name());
        }
    }

    /// The wire shape of one OAuth client (spec §6.3's required key set, plus
    /// the A-22 overlap fields — see the class doc's lockfile gap note).
    /// `previousSecretExpiresAt`/`previousSecretLastUsedAt` are surfaced only
    /// while an overlap is actually live — ABSENT must mean "no overlap", not
    /// "one that lapsed" (A-22).
    public record OAuthClientResponse(
            String id,
            String clientId,
            String clientName,
            String clientType,
            List<String> redirectUris,
            List<String> postLogoutRedirectUris,
            List<String> allowedOrigins,
            List<String> grantTypes,
            List<String> defaultScopes,
            boolean pkceRequired,
            List<String> applicationIds,
            List<OAuthClientApplicationRef> applications,
            boolean active,
            boolean apiAccess,
            String serviceAccountPrincipalId,
            String portalClientId,
            String portalAppId,
            Instant previousSecretExpiresAt,
            Instant previousSecretLastUsedAt,
            Instant createdAt,
            Instant updatedAt) {

        public static OAuthClientResponse from(OAuthClient c, List<OAuthClientApplicationRef> applications) {
            boolean overlapLive = c.usablePreviousSecretRef(Instant.now()).isPresent();
            return new OAuthClientResponse(c.id(), c.clientId(), c.clientName(), c.clientType().name(),
                    c.redirectUris(), c.postLogoutRedirectUris(), c.allowedOrigins(), c.grantTypes(), c.defaultScopes(),
                    c.pkceRequired(), c.applicationIds(), applications, c.active(), c.apiAccess(),
                    c.principalId(), c.portalClientId(), c.portalAppId(),
                    overlapLive ? c.previousSecretExpiresAt() : null,
                    overlapLive ? c.previousSecretLastUsedAt() : null,
                    c.createdAt(), c.updatedAt());
        }
    }

    /// `{"clients": [...]}` — no total (spec §6.3).
    public record OAuthClientListResponse(List<OAuthClientResponse> clients) {
        public OAuthClientListResponse {
            clients = clients == null ? List.of() : List.copyOf(clients);
        }
    }

    /// `clientSecret` is present only for a `CONFIDENTIAL` client, once.
    public record CreateOAuthClientResponse(OAuthClientResponse client, String clientSecret) {
    }

    /// `clientId` is the public OAuth2 client_id, not the internal id.
    /// `previousSecretExpiresAt` is omitted on an immediate cutover (A-22).
    public record RotateOAuthClientSecretResponse(String clientId, String clientSecret, Instant previousSecretExpiresAt) {
    }
}
