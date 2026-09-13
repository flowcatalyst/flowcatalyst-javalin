package io.flowcatalyst.platform.portalapp.api;

import io.flowcatalyst.platform.client.ClientRepository;
import io.flowcatalyst.platform.oauthclient.OAuthClientRepository;
import io.flowcatalyst.platform.portalapp.PortalApp;
import io.flowcatalyst.platform.portalapp.PortalAppRepository;
import io.flowcatalyst.platform.portalapp.operations.CreatePortalAppWithOAuthClient;
import io.flowcatalyst.platform.portalapp.operations.CreatePortalAppWithOAuthClientCommand;
import io.flowcatalyst.platform.portalapp.operations.DeletePortalApp;
import io.flowcatalyst.platform.portalapp.operations.DeletePortalAppCommand;
import io.flowcatalyst.platform.portalapp.operations.UpdatePortalApp;
import io.flowcatalyst.platform.portalapp.operations.UpdatePortalAppCommand;
import io.flowcatalyst.platform.portalidentity.PortalIdentityRepository;
import io.flowcatalyst.platform.portalidentity.operations.AssignUnassignedToApp;
import io.flowcatalyst.platform.portalidentity.operations.AssignUnassignedToAppCommand;
import io.flowcatalyst.platform.shared.apicommon.StatusChangeResponse;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.AuthContext;
import io.flowcatalyst.platform.shared.auth.Checks;
import io.flowcatalyst.platform.shared.encryption.Encryption;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import io.flowcatalyst.http.Exchange;
import io.flowcatalyst.http.Group;
import io.flowcatalyst.http.Routes;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/// The `/api/portal-apps` surface (spec `portal-apps.md` §4.4). Read gate =
/// `Checks.requirePortalUserView`, manage = `Checks.requirePortalUserManage`
/// (same permissions as `/api/portal-users`, §4). `POST` runs §3.4 in one
/// transaction; `DELETE` runs §3.6.
///
/// | Method | Path | Status |
/// |---|---|---|
/// | GET | `/api/portal-apps` | 200 [PortalAppListResponse] |
/// | POST | `/api/portal-apps` | 201 [CreatePortalAppResponse] |
/// | PUT | `/api/portal-apps/{id}` | 200 [PortalAppResponse] |
/// | DELETE | `/api/portal-apps/{id}` | 200 [StatusChangeResponse] |
/// | POST | `/api/portal-apps/{id}/assign-unassigned` | 200 [AssignUnassignedResponse] |
public final class PortalAppApi {

    private PortalAppApi() {
    }

    public record State(PortalAppRepository repo, OAuthClientRepository oauthClients, ClientRepository clients,
                        PortalIdentityRepository portalIdentities, UnitOfWork uow, Optional<Encryption> encryption) {
        public State {
            Objects.requireNonNull(repo, "repo");
            Objects.requireNonNull(oauthClients, "oauthClients");
            Objects.requireNonNull(clients, "clients");
            Objects.requireNonNull(portalIdentities, "portalIdentities");
            Objects.requireNonNull(uow, "uow");
            Objects.requireNonNull(encryption, "encryption");
        }
    }

    public static void register(Routes routes, State s) {
        Routes write = routes.in(Group.API_WRITE);
        routes.get("/api/portal-apps", Auth.scoped(ctx -> list(ctx, s)));
        write.post("/api/portal-apps", Auth.scoped(ctx -> create(ctx, s)));
        write.put("/api/portal-apps/{id}", Auth.scoped(ctx -> update(ctx, s)));
        write.delete("/api/portal-apps/{id}", Auth.scoped(ctx -> delete(ctx, s)));
        write.post("/api/portal-apps/{id}/assign-unassigned", Auth.scoped(ctx -> assignUnassigned(ctx, s)));
    }

    // ── Handlers ───────────────────────────────────────────────────────────

    /// `clientId` omitted → anchors get every client's apps, others 400
    /// `CLIENT_ID_REQUIRED` (spec §4.4 — the one route where a blank
    /// `clientId` is not unconditionally an error, unlike every other §4
    /// route: the exception is checked against the caller's anchor tier,
    /// which is why this handler does not use the common blank-clientId
    /// guard the write handlers below share).
    private static void list(Exchange ctx, State s) {
        AuthContext ac = Auth.current();
        String clientId = ctx.queryParam("clientId");
        boolean blank = clientId == null || clientId.isBlank();
        if (blank && ac != null && !ac.isAnchor()) {
            throw UseCaseException.validation("CLIENT_ID_REQUIRED", "clientId query param is required");
        }
        Checks.requirePortalUserView(ac, blank ? null : clientId);
        List<PortalApp> apps = blank ? s.repo().findAll() : s.repo().findByClient(clientId);
        // unassignedUsers is present only when clientId is given (spec §4.4).
        Long unassignedUsers = blank ? null : s.portalIdentities().countUnassigned(clientId);
        ctx.json(new PortalAppListResponse(toResponses(s, apps), unassignedUsers));
    }

    /// `POST /api/portal-apps/{id}/assign-unassigned` — `assignUnassignedPortalUsers`
    /// (spec §3.2a, §4.4): runs `AssignUnassignedToApp` in one transaction.
    private static void assignUnassigned(Exchange ctx, State s) {
        var body = ctx.bodyAsClass(AssignUnassignedBody.class);
        requireClientId(body.clientId());
        Checks.requirePortalUserManage(Auth.current(), body.clientId());

        var result = AssignUnassignedToApp.of(s.portalIdentities(), s.repo())
                .run(s.uow(), new AssignUnassignedToAppCommand(body.clientId(), ctx.pathParam("id")), Auth.executionContext());
        ctx.json(new AssignUnassignedResponse(result.appCode(), (long) result.identityIds().size()));
    }

    private static void create(Exchange ctx, State s) {
        var req = ctx.bodyAsClass(CreatePortalAppRequest.class);
        requireClientId(req.clientId());
        Checks.requirePortalUserManage(Auth.current(), req.clientId());

        var result = CreatePortalAppWithOAuthClient.of(s.repo(), s.clients(), s.oauthClients(), s.encryption())
                .run(s.uow(), req.toCommand(), Auth.executionContext());
        PortalApp app = s.repo().findById(result.appId())
                .orElseThrow(() -> HttpError.internal("REPO", "portal app created but row not found", null));
        ctx.status(201).json(new CreatePortalAppResponse(response(s, app), result.oauthClientId(),
                result.oauthClientRowId(), result.clientType().name(), result.clientSecret()));
    }

    private static void update(Exchange ctx, State s) {
        var req = ctx.bodyAsClass(UpdatePortalAppRequest.class);
        requireClientId(req.clientId());
        Checks.requirePortalUserManage(Auth.current(), req.clientId());

        var event = UpdatePortalApp.of(s.repo()).run(s.uow(), req.toCommand(ctx.pathParam("id")), Auth.executionContext());
        PortalApp app = s.repo().findById(event.portalAppId())
                .orElseThrow(() -> HttpError.internal("REPO", "portal app updated but row not found", null));
        ctx.json(response(s, app));
    }

    private static void delete(Exchange ctx, State s) {
        String clientId = ctx.queryParam("clientId");
        requireClientId(clientId);
        Checks.requirePortalUserManage(Auth.current(), clientId);

        var result = DeletePortalApp.of(s.repo(), s.oauthClients())
                .run(s.uow(), new DeletePortalAppCommand(clientId, ctx.pathParam("id")), Auth.executionContext());
        ctx.json(new StatusChangeResponse("Portal app deleted" + deletedSuffix(result.deletedOAuthClientIds().size())));
    }

    /// Spec §4.4: `" with its OAuth client"` (1) / `" with its OAuth clients"`
    /// (>1). The spec is silent on zero (an app whose OAuth client was
    /// deleted independently beforehand) — neither wording fits an app with
    /// none, so this resolves it as no suffix at all.
    private static String deletedSuffix(int deletedCount) {
        return switch (deletedCount) {
            case 0 -> "";
            case 1 -> " with its OAuth client";
            default -> " with its OAuth clients";
        };
    }

    private static void requireClientId(String clientId) {
        if (clientId == null || clientId.isBlank()) {
            throw UseCaseException.validation("CLIENT_ID_REQUIRED", "clientId is required");
        }
    }

    // ── Read-side helpers ──────────────────────────────────────────────────

    private static PortalAppResponse response(State s, PortalApp app) {
        return toResponses(s, List.of(app)).getFirst();
    }

    /// Batches `userCount` and the linked-OAuth-client list across every app
    /// on the page — one query each, never per-app (spec §4.4).
    private static List<PortalAppResponse> toResponses(State s, List<PortalApp> apps) {
        if (apps.isEmpty()) {
            return List.of();
        }
        List<String> ids = apps.stream().map(PortalApp::id).toList();
        Map<String, Integer> counts = s.repo().userCounts(ids);
        Map<String, List<OAuthClientRepository.LinkedRef>> linked = s.oauthClients().linkedTo(ids).stream()
                .collect(Collectors.groupingBy(OAuthClientRepository.LinkedRef::portalAppId, LinkedHashMap::new, Collectors.toList()));
        return apps.stream().map(app -> {
            List<PortalAppResponse.LinkedOAuthClient> oc = linked.getOrDefault(app.id(), List.of()).stream()
                    .map(r -> new PortalAppResponse.LinkedOAuthClient(r.id(), r.clientId(), r.clientName()))
                    .toList();
            return new PortalAppResponse(app.id(), app.clientId(), app.code(), app.name(), app.description(), app.active(),
                    oc, counts.getOrDefault(app.id(), 0), app.createdAt(), app.updatedAt());
        }).toList();
    }

    // ── Wire DTOs (lockfile shapes) ──────────────────────────────────────────

    /// Body of `POST /api/portal-apps` (spec §4.4). `clientType`:
    /// `"CONFIDENTIAL"` \| `"PUBLIC"`, omitted ⇒ `CONFIDENTIAL`.
    public record CreatePortalAppRequest(
            String clientId, String code, String name, String description,
            List<String> redirectUris, String clientType) {
        public CreatePortalAppWithOAuthClientCommand toCommand() {
            return new CreatePortalAppWithOAuthClientCommand(clientId, code, name, description, redirectUris, clientType);
        }
    }

    /// Body of `PUT /api/portal-apps/{id}` (spec §4.4). `null` = untouched;
    /// `code` is not settable — it never changes after creation.
    public record UpdatePortalAppRequest(String clientId, String name, String description, Boolean active) {
        public UpdatePortalAppCommand toCommand(String id) {
            return new UpdatePortalAppCommand(clientId, id, name, description, active);
        }
    }

    /// `oauthClients` always an array, ordered by name; `userCount` = grant
    /// rows for the app (spec §4.4).
    public record PortalAppResponse(
            String id, String clientId, String code, String name, String description, boolean active,
            List<LinkedOAuthClient> oauthClients, int userCount, Instant createdAt, Instant updatedAt) {
        public PortalAppResponse {
            oauthClients = oauthClients == null ? List.of() : List.copyOf(oauthClients);
        }

        public record LinkedOAuthClient(String id, String clientId, String clientName) {
        }
    }

    /// `unassignedUsers` (count of the client's identities with no grant) is
    /// present only when `clientId` is given — `null` here is omitted from
    /// the wire (`Json.MAPPER`'s `NON_ABSENT`, spec §4.4).
    public record PortalAppListResponse(List<PortalAppResponse> portalApps, Long unassignedUsers) {
        public PortalAppListResponse {
            portalApps = portalApps == null ? List.of() : List.copyOf(portalApps);
        }
    }

    /// `clientSecret` present only for a `CONFIDENTIAL` client, once (spec §4.4).
    public record CreatePortalAppResponse(
            PortalAppResponse portalApp, String oauthClientId, String oauthClientRowId, String clientType, String clientSecret) {
    }

    /// Body of `POST /api/portal-apps/{id}/assign-unassigned` (spec §4.4).
    public record AssignUnassignedBody(String clientId) {
    }

    public record AssignUnassignedResponse(String portalAppCode, long assigned) {
    }
}
