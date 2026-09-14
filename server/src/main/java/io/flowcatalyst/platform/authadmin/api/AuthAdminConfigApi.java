package io.flowcatalyst.platform.authadmin.api;

import io.flowcatalyst.platform.authadmin.AnchorDomain;
import io.flowcatalyst.platform.authadmin.AnchorDomainRepository;
import io.flowcatalyst.platform.authadmin.ClientAuthConfig;
import io.flowcatalyst.platform.authadmin.ClientAuthConfigRepository;
import io.flowcatalyst.platform.authadmin.IdpRoleMapping;
import io.flowcatalyst.platform.authadmin.IdpRoleMappingRepository;
import io.flowcatalyst.platform.authadmin.operations.CreateAnchorDomainCommand;
import io.flowcatalyst.platform.authadmin.operations.CreateAnchorDomain;
import io.flowcatalyst.platform.authadmin.operations.CreateAuthConfig;
import io.flowcatalyst.platform.authadmin.operations.CreateAuthConfigCommand;
import io.flowcatalyst.platform.authadmin.operations.CreateIdpRoleMapping;
import io.flowcatalyst.platform.authadmin.operations.CreateIdpRoleMappingCommand;
import io.flowcatalyst.platform.authadmin.operations.DeleteAnchorDomain;
import io.flowcatalyst.platform.authadmin.operations.DeleteAnchorDomainCommand;
import io.flowcatalyst.platform.authadmin.operations.DeleteAuthConfig;
import io.flowcatalyst.platform.authadmin.operations.DeleteAuthConfigCommand;
import io.flowcatalyst.platform.authadmin.operations.DeleteIdpRoleMapping;
import io.flowcatalyst.platform.authadmin.operations.DeleteIdpRoleMappingCommand;
import io.flowcatalyst.platform.authadmin.operations.UpdateAnchorDomain;
import io.flowcatalyst.platform.authadmin.operations.UpdateAnchorDomainCommand;
import io.flowcatalyst.platform.authadmin.operations.UpdateAuthConfig;
import io.flowcatalyst.platform.authadmin.operations.UpdateAuthConfigCommand;
import io.flowcatalyst.platform.shared.apicommon.CreatedResponse;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.Checks;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import io.flowcatalyst.http.Exchange;
import io.flowcatalyst.http.Group;
import io.flowcatalyst.http.Routes;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import static io.flowcatalyst.platform.shared.auth.Permission.ANCHOR_DOMAIN_CREATE;
import static io.flowcatalyst.platform.shared.auth.Permission.ANCHOR_DOMAIN_DELETE;
import static io.flowcatalyst.platform.shared.auth.Permission.ANCHOR_DOMAIN_UPDATE;
import static io.flowcatalyst.platform.shared.auth.Permission.ANCHOR_DOMAIN_VIEW;
import static io.flowcatalyst.platform.shared.auth.Permission.CLIENT_AUTH_CONFIG_CREATE;
import static io.flowcatalyst.platform.shared.auth.Permission.CLIENT_AUTH_CONFIG_DELETE;
import static io.flowcatalyst.platform.shared.auth.Permission.CLIENT_AUTH_CONFIG_UPDATE;
import static io.flowcatalyst.platform.shared.auth.Permission.CLIENT_AUTH_CONFIG_VIEW;
import static io.flowcatalyst.platform.shared.auth.Permission.IDP_UPDATE;
import static io.flowcatalyst.platform.shared.auth.Permission.IDP_VIEW;

/// The `/api/anchor-domains`, `/api/auth-configs` and `/api/idp-role-mappings`
/// surfaces (spec §3). All eleven routes are anchor-only, reads included
/// (spec §1) — every handler opens with `requireAnchor`, followed by the
/// permission gate (`docs/spec/reach-only-routes.md`). A write handler
/// does exactly: gate → command from DTO → `Operation.run` → response. Reads
/// go straight to the repository. Every handler runs inside [Auth#scoped]
/// so the operations can read [Auth#current()].
///
/// | Method | Path | Status |
/// |---|---|---|
/// | GET | `/api/anchor-domains` | 200 [AnchorDomainListResponse] |
/// | POST | `/api/anchor-domains` | 201 [CreatedResponse] |
/// | PUT | `/api/anchor-domains/{id}` | 204 |
/// | DELETE | `/api/anchor-domains/{id}` | 204 |
/// | GET | `/api/auth-configs` | 200 [AuthConfigListResponse] |
/// | POST | `/api/auth-configs` | 201 [CreatedResponse] |
/// | PUT | `/api/auth-configs/{id}` | 204 |
/// | DELETE | `/api/auth-configs/{id}` | 204 |
/// | GET | `/api/idp-role-mappings` | 200 [IdpRoleMappingListResponse] |
/// | POST | `/api/idp-role-mappings` | 201 [CreatedResponse] |
/// | DELETE | `/api/idp-role-mappings/{id}` | 204 |
public final class AuthAdminConfigApi {

    private AuthAdminConfigApi() {
    }

    /// The handlers' dependencies.
    public record State(AnchorDomainRepository anchorDomainRepo, ClientAuthConfigRepository authConfigRepo,
                        IdpRoleMappingRepository idpRoleMappingRepo, UnitOfWork uow,
                        io.flowcatalyst.platform.identityprovider.api.ClientSecretEncryption secrets) {
        public State {
            Objects.requireNonNull(anchorDomainRepo, "anchorDomainRepo");
            Objects.requireNonNull(authConfigRepo, "authConfigRepo");
            Objects.requireNonNull(idpRoleMappingRepo, "idpRoleMappingRepo");
            Objects.requireNonNull(uow, "uow");
        }
    }

    /// Mounts the endpoints; paths, methods and status codes are the lockfile's.
    public static void register(Routes routes, State s) {
        Routes write = routes.in(Group.API_WRITE);
        routes.get("/api/anchor-domains", Auth.scoped(ctx -> listAnchorDomains(ctx, s)));
        write.post("/api/anchor-domains", Auth.scoped(ctx -> createAnchorDomain(ctx, s)));
        write.put("/api/anchor-domains/{id}", Auth.scoped(ctx -> updateAnchorDomain(ctx, s)));
        write.delete("/api/anchor-domains/{id}", Auth.scoped(ctx -> deleteAnchorDomain(ctx, s)));

        routes.get("/api/auth-configs", Auth.scoped(ctx -> listAuthConfigs(ctx, s)));
        write.post("/api/auth-configs", Auth.scoped(ctx -> createAuthConfig(ctx, s)));
        write.put("/api/auth-configs/{id}", Auth.scoped(ctx -> updateAuthConfig(ctx, s)));
        write.delete("/api/auth-configs/{id}", Auth.scoped(ctx -> deleteAuthConfig(ctx, s)));

        routes.get("/api/idp-role-mappings", Auth.scoped(ctx -> listIdpRoleMappings(ctx, s)));
        write.post("/api/idp-role-mappings", Auth.scoped(ctx -> createIdpRoleMapping(ctx, s)));
        write.delete("/api/idp-role-mappings/{id}", Auth.scoped(ctx -> deleteIdpRoleMapping(ctx, s)));
    }

    // ── Anchor domains ───────────────────────────────────────────────────────

    private static void listAnchorDomains(Exchange ctx, State s) {
        Checks.requireAnchor(Auth.current());
        Checks.require(Auth.current(), ANCHOR_DOMAIN_VIEW);
        ctx.json(new AnchorDomainListResponse(s.anchorDomainRepo().findAll().stream().map(AnchorDomainResponse::from).toList()));
    }

    private static void createAnchorDomain(Exchange ctx, State s) {
        Checks.requireAnchor(Auth.current());
        Checks.require(Auth.current(), ANCHOR_DOMAIN_CREATE);
        var cmd = ctx.bodyAsClass(CreateAnchorDomainRequest.class).toCommand();
        var event = CreateAnchorDomain.of(s.anchorDomainRepo()).run(s.uow(), cmd, Auth.executionContext());
        ctx.status(201).json(new CreatedResponse(event.anchorDomainId()));
    }

    private static void updateAnchorDomain(Exchange ctx, State s) {
        Checks.requireAnchor(Auth.current());
        Checks.require(Auth.current(), ANCHOR_DOMAIN_UPDATE);
        var cmd = ctx.bodyAsClass(UpdateAnchorDomainRequest.class).toCommand(ctx.pathParam("id"));
        UpdateAnchorDomain.of(s.anchorDomainRepo()).run(s.uow(), cmd, Auth.executionContext());
        ctx.status(204);
    }

    private static void deleteAnchorDomain(Exchange ctx, State s) {
        Checks.requireAnchor(Auth.current());
        Checks.require(Auth.current(), ANCHOR_DOMAIN_DELETE);
        DeleteAnchorDomain.of(s.anchorDomainRepo()).run(s.uow(), new DeleteAnchorDomainCommand(ctx.pathParam("id")), Auth.executionContext());
        ctx.status(204);
    }

    // ── Auth configs ─────────────────────────────────────────────────────────

    private static void listAuthConfigs(Exchange ctx, State s) {
        Checks.requireAnchor(Auth.current());
        Checks.require(Auth.current(), CLIENT_AUTH_CONFIG_VIEW);
        ctx.json(new AuthConfigListResponse(s.authConfigRepo().findAll().stream().map(AuthConfigResponse::from).toList()));
    }

    private static void createAuthConfig(Exchange ctx, State s) {
        Checks.requireAnchor(Auth.current());
        Checks.require(Auth.current(), CLIENT_AUTH_CONFIG_CREATE);
        var cmd = ctx.bodyAsClass(CreateAuthConfigRequest.class).toCommand(s.secrets());
        var event = CreateAuthConfig.of(s.authConfigRepo()).run(s.uow(), cmd, Auth.executionContext());
        ctx.status(201).json(new CreatedResponse(event.authConfigId()));
    }

    private static void updateAuthConfig(Exchange ctx, State s) {
        Checks.requireAnchor(Auth.current());
        Checks.require(Auth.current(), CLIENT_AUTH_CONFIG_UPDATE);
        var cmd = ctx.bodyAsClass(UpdateAuthConfigRequest.class).toCommand(ctx.pathParam("id"), s.secrets());
        UpdateAuthConfig.of(s.authConfigRepo()).run(s.uow(), cmd, Auth.executionContext());
        ctx.status(204);
    }

    private static void deleteAuthConfig(Exchange ctx, State s) {
        Checks.requireAnchor(Auth.current());
        Checks.require(Auth.current(), CLIENT_AUTH_CONFIG_DELETE);
        DeleteAuthConfig.of(s.authConfigRepo()).run(s.uow(), new DeleteAuthConfigCommand(ctx.pathParam("id")), Auth.executionContext());
        ctx.status(204);
    }

    // ── IdP role mappings ────────────────────────────────────────────────────

    private static void listIdpRoleMappings(Exchange ctx, State s) {
        Checks.requireAnchor(Auth.current());
        Checks.require(Auth.current(), IDP_VIEW);
        ctx.json(new IdpRoleMappingListResponse(s.idpRoleMappingRepo().findAll().stream().map(IdpRoleMappingResponse::from).toList()));
    }

    private static void createIdpRoleMapping(Exchange ctx, State s) {
        Checks.requireAnchor(Auth.current());
        Checks.require(Auth.current(), IDP_UPDATE);
        var cmd = ctx.bodyAsClass(CreateIdpRoleMappingRequest.class).toCommand();
        var event = CreateIdpRoleMapping.of(s.idpRoleMappingRepo()).run(s.uow(), cmd, Auth.executionContext());
        ctx.status(201).json(new CreatedResponse(event.mappingId()));
    }

    private static void deleteIdpRoleMapping(Exchange ctx, State s) {
        Checks.requireAnchor(Auth.current());
        Checks.require(Auth.current(), IDP_UPDATE);
        DeleteIdpRoleMapping.of(s.idpRoleMappingRepo()).run(s.uow(), new DeleteIdpRoleMappingCommand(ctx.pathParam("id")), Auth.executionContext());
        ctx.status(204);
    }

    // ── Wire DTOs (lockfile components) ─────────────────────────────────────

    /// Body of `POST /api/anchor-domains`.
    public record CreateAnchorDomainRequest(String domain) {
        public CreateAnchorDomainCommand toCommand() {
            return new CreateAnchorDomainCommand(domain);
        }
    }

    /// Body of `PUT /api/anchor-domains/{id}`.
    public record UpdateAnchorDomainRequest(String domain) {
        public UpdateAnchorDomainCommand toCommand(String id) {
            return new UpdateAnchorDomainCommand(id, domain);
        }
    }

    /// The wire shape of one anchor domain.
    public record AnchorDomainResponse(String id, String domain, Instant createdAt, Instant updatedAt) {
        public static AnchorDomainResponse from(AnchorDomain a) {
            return new AnchorDomainResponse(a.id(), a.domain(), a.createdAt(), a.updatedAt());
        }
    }

    /// `{"items": [...]}` — no pagination on this endpoint.
    public record AnchorDomainListResponse(List<AnchorDomainResponse> items) {
        public AnchorDomainListResponse {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    /// Body of `POST /api/auth-configs`.
    public record CreateAuthConfigRequest(String emailDomain, String configType, String primaryClientId,
                                          List<String> additionalClientIds, List<String> grantedClientIds,
                                          String authProvider, String oidcIssuerUrl, String oidcClientId,
                                          boolean oidcMultiTenant, String oidcIssuerPattern, String oidcClientSecretRef) {
        /// `oidcClientSecretRef` is sealed before the command exists (the same
        /// at-rest policy as the identity-provider API, `identityprovider.md` §5):
        /// Go encrypts it in the handler too (`auth/api/api.go encryptOIDCSecretRef`);
        /// the parity harness (S1-A) found Java storing it verbatim.
        public CreateAuthConfigCommand toCommand(io.flowcatalyst.platform.identityprovider.api.ClientSecretEncryption secrets) {
            return new CreateAuthConfigCommand(emailDomain, configType, primaryClientId, additionalClientIds,
                    grantedClientIds, authProvider, oidcIssuerUrl, oidcClientId, oidcMultiTenant, oidcIssuerPattern,
                    secrets.atRest(oidcClientSecretRef));
        }
    }

    /// Body of `PUT /api/auth-configs/{id}`; absent-value semantics per spec §4.2.
    public record UpdateAuthConfigRequest(String primaryClientId, List<String> additionalClientIds,
                                          List<String> grantedClientIds, String authProvider, String oidcIssuerUrl,
                                          String oidcClientId, Boolean oidcMultiTenant, String oidcIssuerPattern,
                                          String oidcClientSecretRef) {
        public UpdateAuthConfigCommand toCommand(String id, io.flowcatalyst.platform.identityprovider.api.ClientSecretEncryption secrets) {
            return new UpdateAuthConfigCommand(id, primaryClientId, additionalClientIds, grantedClientIds, authProvider,
                    oidcIssuerUrl, oidcClientId, oidcMultiTenant, oidcIssuerPattern, secrets.atRest(oidcClientSecretRef));
        }
    }

    /// The wire shape of one auth config; `primaryClientId`, `oidcIssuerUrl`,
    /// `oidcClientId`, `oidcIssuerPattern` and `oidcClientSecretRef` are
    /// omitted when `null`; the arrays are always present.
    public record AuthConfigResponse(String id, String emailDomain, String configType, String primaryClientId,
                                     List<String> additionalClientIds, List<String> grantedClientIds, String authProvider,
                                     String oidcIssuerUrl, String oidcClientId, boolean oidcMultiTenant,
                                     String oidcIssuerPattern, String oidcClientSecretRef, Instant createdAt, Instant updatedAt) {
        public static AuthConfigResponse from(ClientAuthConfig c) {
            return new AuthConfigResponse(c.id(), c.emailDomain(), c.configType().name(), c.primaryClientId(),
                    c.additionalClientIds(), c.grantedClientIds(), c.authProvider().name(), c.oidcIssuerUrl(),
                    c.oidcClientId(), c.oidcMultiTenant(), c.oidcIssuerPattern(), c.oidcClientSecretRef(),
                    c.createdAt(), c.updatedAt());
        }
    }

    /// `{"items": [...]}` — no pagination on this endpoint.
    public record AuthConfigListResponse(List<AuthConfigResponse> items) {
        public AuthConfigListResponse {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    /// Body of `POST /api/idp-role-mappings`.
    public record CreateIdpRoleMappingRequest(String idpType, String idpRoleName, String platformRoleName) {
        public CreateIdpRoleMappingCommand toCommand() {
            return new CreateIdpRoleMappingCommand(idpType, idpRoleName, platformRoleName);
        }
    }

    /// The wire shape of one mapping; `idpType` is required on the wire, so a
    /// legacy `NULL` row reads back as `""` (spec §6 D4) rather than being omitted.
    public record IdpRoleMappingResponse(String id, String idpType, String idpRoleName, String platformRoleName,
                                         Instant createdAt, Instant updatedAt) {
        public static IdpRoleMappingResponse from(IdpRoleMapping m) {
            return new IdpRoleMappingResponse(m.id(), m.idpType() == null ? "" : m.idpType(), m.idpRoleName(),
                    m.platformRoleName(), m.createdAt(), m.updatedAt());
        }
    }

    /// `{"items": [...]}` — no pagination on this endpoint.
    public record IdpRoleMappingListResponse(List<IdpRoleMappingResponse> items) {
        public IdpRoleMappingListResponse {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }
}
