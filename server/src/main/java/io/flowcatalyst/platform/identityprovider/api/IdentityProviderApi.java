package io.flowcatalyst.platform.identityprovider.api;

import io.flowcatalyst.platform.emaildomainmapping.EmailDomainMappingRepository;
import io.flowcatalyst.platform.identityprovider.IdentityProvider;
import io.flowcatalyst.platform.identityprovider.IdentityProviderRepository;
import io.flowcatalyst.platform.identityprovider.operations.CreateCommand;
import io.flowcatalyst.platform.identityprovider.operations.CreateIdentityProvider;
import io.flowcatalyst.platform.identityprovider.operations.DeleteCommand;
import io.flowcatalyst.platform.identityprovider.operations.DeleteIdentityProvider;
import io.flowcatalyst.platform.identityprovider.operations.UpdateCommand;
import io.flowcatalyst.platform.identityprovider.operations.UpdateIdentityProvider;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.Checks;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import io.flowcatalyst.http.Exchange;
import io.flowcatalyst.http.Group;
import io.flowcatalyst.http.Routes;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import static io.flowcatalyst.platform.shared.auth.Permission.IDP_CREATE;
import static io.flowcatalyst.platform.shared.auth.Permission.IDP_DELETE;
import static io.flowcatalyst.platform.shared.auth.Permission.IDP_UPDATE;
import static io.flowcatalyst.platform.shared.auth.Permission.IDP_VIEW;

/// The `/api/identity-providers` surface (spec §3). Identity providers are
/// anchor-only: every handler opens with `requireAnchor`, followed by the
/// permission gate (`docs/spec/reach-only-routes.md`). A write handler
/// does exactly: gate → command from DTO → run → response; the only
/// wire-boundary mapping beyond field names is the client secret's at-rest
/// conversion ([ClientSecretEncryption], spec §5), done before the command
/// exists so the audit log never sees a plaintext secret. Reads go straight
/// to the repository. Every handler runs inside [Auth#scoped] so the
/// operations can read [Auth#current()].
///
/// | Method | Path | Status |
/// |---|---|---|
/// | GET | `/api/identity-providers` | 200 [IdentityProviderListResponse] |
/// | POST | `/api/identity-providers` | 201 [IdentityProviderResponse] |
/// | GET | `/api/identity-providers/{id}` | 200 [IdentityProviderResponse] |
/// | PUT | `/api/identity-providers/{id}` | 200 [IdentityProviderResponse] |
/// | DELETE | `/api/identity-providers/{id}` | 204 |
public final class IdentityProviderApi {

    private IdentityProviderApi() {
    }

    /// The handlers' dependencies. `mappings` feeds the domain orchestration
    /// (spec §4); `secrets` is the client-secret at-rest policy (spec §5);
    /// `onChange` runs with the provider id after every successful update
    /// or delete — the OIDC bridge drops its cached client for that
    /// provider (auth-identity ruling Q1). Same-node only; the cache's TTL
    /// covers the other nodes.
    public record State(IdentityProviderRepository repo, EmailDomainMappingRepository mappings, UnitOfWork uow,
                        ClientSecretEncryption secrets, Consumer<String> onChange) {

        public State(IdentityProviderRepository repo, EmailDomainMappingRepository mappings, UnitOfWork uow,
                     ClientSecretEncryption secrets) {
            this(repo, mappings, uow, secrets, _ -> { });
        }
        public State {
            Objects.requireNonNull(repo, "repo");
            Objects.requireNonNull(mappings, "mappings");
            Objects.requireNonNull(uow, "uow");
            Objects.requireNonNull(secrets, "secrets");
            Objects.requireNonNull(onChange, "onChange");
        }
    }

    /// Mounts the endpoints; paths, methods and status codes are the lockfile's.
    public static void register(Routes routes, State s) {
        Routes write = routes.in(Group.API_WRITE);
        routes.get("/api/identity-providers", Auth.scoped(ctx -> list(ctx, s)));
        write.post("/api/identity-providers", Auth.scoped(ctx -> create(ctx, s)));
        routes.get("/api/identity-providers/{id}", Auth.scoped(ctx -> getById(ctx, s)));
        write.put("/api/identity-providers/{id}", Auth.scoped(ctx -> update(ctx, s)));
        write.delete("/api/identity-providers/{id}", Auth.scoped(ctx -> delete(ctx, s)));
    }

    // ── Reads ──────────────────────────────────────────────────────────────

    private static void list(Exchange ctx, State s) {
        Checks.requireAnchor(Auth.current());
        Checks.require(Auth.current(), IDP_VIEW);
        ctx.json(IdentityProviderListResponse.from(s.repo().findAll()));
    }

    private static void getById(Exchange ctx, State s) {
        Checks.requireAnchor(Auth.current());
        Checks.require(Auth.current(), IDP_VIEW);
        ctx.json(IdentityProviderResponse.from(load(s, ctx.pathParam("id"))));
    }

    // ── Writes ─────────────────────────────────────────────────────────────

    /// 201 with the full provider (re-read after commit): the SPA's create toast reads `name` (spec §3).
    private static void create(Exchange ctx, State s) {
        Checks.requireAnchor(Auth.current());
        Checks.require(Auth.current(), IDP_CREATE);
        var cmd = ctx.bodyAsClass(CreateIdentityProviderRequest.class).toCommand(s.secrets());
        var result = CreateIdentityProvider.of(s.repo(), s.mappings()).run(s.uow(), cmd, Auth.executionContext());
        ctx.status(201).json(IdentityProviderResponse.from(load(s, result.identityProviderId())));
    }

    /// 200 with the full provider (not 204): the SPA's detail page replaces its model with the body (spec §3).
    private static void update(Exchange ctx, State s) {
        Checks.requireAnchor(Auth.current());
        Checks.require(Auth.current(), IDP_UPDATE);
        var cmd = ctx.bodyAsClass(UpdateIdentityProviderRequest.class).toCommand(ctx.pathParam("id"), s.secrets());
        UpdateIdentityProvider.of(s.repo(), s.mappings()).run(s.uow(), cmd, Auth.executionContext());
        s.onChange().accept(ctx.pathParam("id"));
        ctx.json(IdentityProviderResponse.from(load(s, cmd.id())));
    }

    private static void delete(Exchange ctx, State s) {
        Checks.requireAnchor(Auth.current());
        Checks.require(Auth.current(), IDP_DELETE);
        DeleteIdentityProvider.of(s.repo()).run(s.uow(), new DeleteCommand(ctx.pathParam("id")), Auth.executionContext());
        s.onChange().accept(ctx.pathParam("id"));
        ctx.status(204);
    }

    // ── Read-side helpers ──────────────────────────────────────────────────

    private static IdentityProvider load(State s, String id) {
        return s.repo().findById(id).orElseThrow(() -> HttpError.notFound("IdentityProvider", id));
    }

    // ── Wire DTOs (lockfile components) ────────────────────────────────────

    /// Body of `POST /api/identity-providers`.
    public record CreateIdentityProviderRequest(
            String code,
            String name,
            String type,
            String oidcIssuerUrl,
            String oidcClientId,
            String oidcClientSecretRef,
            boolean oidcMultiTenant,
            String oidcIssuerPattern,
            List<String> allowedEmailDomains,
            String mappingScope,
            String primaryClientId,
            boolean syncRolesFromIdp,
            List<String> allowedRoleIds,
            List<String> allowedTenantIds) {

        /// The command, with the secret already in its at-rest form (spec §5).
        public CreateCommand toCommand(ClientSecretEncryption secrets) {
            return new CreateCommand(code, name, type, oidcIssuerUrl, oidcClientId, secrets.atRest(oidcClientSecretRef),
                    oidcMultiTenant, oidcIssuerPattern, allowedEmailDomains, mappingScope, primaryClientId, syncRolesFromIdp,
                    allowedRoleIds, allowedTenantIds);
        }
    }

    /// Body of `PUT /api/identity-providers/{id}`; every field absent = untouched.
    public record UpdateIdentityProviderRequest(
            String name,
            String oidcIssuerUrl,
            String oidcClientId,
            String oidcClientSecretRef,
            Boolean oidcMultiTenant,
            String oidcIssuerPattern,
            List<String> allowedEmailDomains,
            String mappingScope,
            String primaryClientId,
            Boolean syncRolesFromIdp,
            List<String> allowedRoleIds,
            List<String> allowedTenantIds) {

        /// The command, with the secret already in its at-rest form (spec §5).
        public UpdateCommand toCommand(String id, ClientSecretEncryption secrets) {
            return new UpdateCommand(id, name, oidcIssuerUrl, oidcClientId, secrets.atRest(oidcClientSecretRef),
                    oidcMultiTenant, oidcIssuerPattern, allowedEmailDomains, mappingScope, primaryClientId, syncRolesFromIdp,
                    allowedRoleIds, allowedTenantIds);
        }
    }

    /// The wire shape of one provider: the secret ref itself is never
    /// serialised, only `hasClientSecret`; optional strings are omitted when
    /// `null`; the two lists are always present.
    public record IdentityProviderResponse(
            String id,
            String code,
            String name,
            String type,
            String oidcIssuerUrl,
            String oidcClientId,
            boolean hasClientSecret,
            boolean oidcMultiTenant,
            String oidcIssuerPattern,
            List<String> allowedEmailDomains,
            boolean syncRolesFromIdp,
            List<String> allowedRoleIds,
            List<String> allowedTenantIds,
            Instant createdAt,
            Instant updatedAt) {

        public static IdentityProviderResponse from(IdentityProvider ip) {
            return new IdentityProviderResponse(ip.id(), ip.code(), ip.name(), ip.type().name(), ip.oidcIssuerUrl(),
                    ip.oidcClientId(), ip.hasClientSecret(), ip.oidcMultiTenant(), ip.oidcIssuerPattern(),
                    ip.allowedEmailDomains(), ip.syncRolesFromIdp(), ip.allowedRoleIds(), ip.allowedTenantIds(),
                    ip.createdAt(), ip.updatedAt());
        }
    }

    /// `{"identityProviders": [...], "total": n}` — `total` is the list size (no pagination).
    public record IdentityProviderListResponse(List<IdentityProviderResponse> identityProviders, int total) {
        public IdentityProviderListResponse {
            identityProviders = identityProviders == null ? List.of() : List.copyOf(identityProviders);
        }

        public static IdentityProviderListResponse from(List<IdentityProvider> providers) {
            var items = providers.stream().map(IdentityProviderResponse::from).toList();
            return new IdentityProviderListResponse(items, items.size());
        }
    }
}
