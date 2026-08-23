package io.flowcatalyst.platform.emaildomainmapping.api;

import io.flowcatalyst.platform.emaildomainmapping.EmailDomainMapping;
import io.flowcatalyst.platform.emaildomainmapping.EmailDomainMappingRepository;
import io.flowcatalyst.platform.emaildomainmapping.MfaMethod;
import io.flowcatalyst.platform.emaildomainmapping.operations.CreateCommand;
import io.flowcatalyst.platform.emaildomainmapping.operations.CreateEmailDomainMapping;
import io.flowcatalyst.platform.emaildomainmapping.operations.DeleteCommand;
import io.flowcatalyst.platform.emaildomainmapping.operations.DeleteEmailDomainMapping;
import io.flowcatalyst.platform.emaildomainmapping.operations.MoveEmailDomainMappingProvider;
import io.flowcatalyst.platform.emaildomainmapping.operations.MoveProviderCommand;
import io.flowcatalyst.platform.emaildomainmapping.operations.MoveProviderResult;
import io.flowcatalyst.platform.emaildomainmapping.operations.UpdateCommand;
import io.flowcatalyst.platform.emaildomainmapping.operations.UpdateEmailDomainMapping;
import io.flowcatalyst.platform.shared.apicommon.CreatedResponse;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.Checks;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import io.javalin.http.Context;
import io.javalin.router.JavalinDefaultRoutingApi;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/// The `/api/email-domain-mappings` surface (spec §3). Mappings are
/// anchor-only: every handler opens with `requireAnchor` except the
/// `lookup` read, which has no gate (spec §3, open question 1). A write
/// handler does exactly: gate → command from DTO → `Operation.run` →
/// response. Reads go straight to the repository and enrich the response
/// with the identity provider's display name. Every handler runs inside
/// [Auth#scoped] so the operations can read [Auth#current()].
///
/// | Method | Path | Status |
/// |---|---|---|
/// | GET | `/api/email-domain-mappings` | 200 [MappingListResponse] |
/// | POST | `/api/email-domain-mappings` | 201 [CreatedResponse] |
/// | GET | `/api/email-domain-mappings/lookup?domain=` | 200 [MappingResponse] or [LookupNotFoundResponse] |
/// | GET | `/api/email-domain-mappings/by-domain/{domain}` | 200 [MappingResponse] |
/// | GET | `/api/email-domain-mappings/{id}` | 200 [MappingResponse] |
/// | PUT | `/api/email-domain-mappings/{id}` | 204 |
/// | POST | `/api/email-domain-mappings/{id}/move-provider` | 200 [MoveProviderResponse] |
/// | DELETE | `/api/email-domain-mappings/{id}` | 204 |
public final class EmailDomainMappingApi {

    private EmailDomainMappingApi() {
    }

    /// The handlers' dependencies.
    public record State(EmailDomainMappingRepository repo, UnitOfWork uow) {
        public State {
            Objects.requireNonNull(repo, "repo");
            Objects.requireNonNull(uow, "uow");
        }
    }

    /// Mounts the endpoints; paths, methods and status codes are the
    /// lockfile's. Literal segments are registered before the `{id}` routes
    /// so they take precedence (spec §3).
    public static void register(JavalinDefaultRoutingApi routes, State s) {
        routes.get("/api/email-domain-mappings", Auth.scoped(ctx -> list(ctx, s)));
        routes.post("/api/email-domain-mappings", Auth.scoped(ctx -> create(ctx, s)));
        routes.get("/api/email-domain-mappings/lookup", Auth.scoped(ctx -> lookup(ctx, s)));
        routes.get("/api/email-domain-mappings/by-domain/{domain}", Auth.scoped(ctx -> getByDomain(ctx, s)));
        routes.get("/api/email-domain-mappings/{id}", Auth.scoped(ctx -> getById(ctx, s)));
        routes.put("/api/email-domain-mappings/{id}", Auth.scoped(ctx -> update(ctx, s)));
        routes.post("/api/email-domain-mappings/{id}/move-provider", Auth.scoped(ctx -> moveProvider(ctx, s)));
        routes.delete("/api/email-domain-mappings/{id}", Auth.scoped(ctx -> delete(ctx, s)));
    }

    // ── Reads ──────────────────────────────────────────────────────────────

    private static void list(Context ctx, State s) {
        Checks.requireAnchor(Auth.current());
        ctx.json(MappingListResponse.from(s.repo().findAll(), s.repo()));
    }

    /// No gate (spec §3): answers `{"found": false}` instead of 404 when unmapped.
    private static void lookup(Context ctx, State s) {
        String domain = requireDomain(ctx.queryParam("domain"), "domain query param is required");
        Optional<EmailDomainMapping> m = s.repo().findByEmailDomain(domain);
        if (m.isEmpty()) {
            ctx.json(LookupNotFoundResponse.INSTANCE);
            return;
        }
        ctx.json(MappingResponse.from(m.get(), idpName(s, m.get())));
    }

    private static void getByDomain(Context ctx, State s) {
        Checks.requireAnchor(Auth.current());
        String domain = requireDomain(ctx.pathParam("domain"), "domain path param is required");
        EmailDomainMapping m = byDomain(s, domain);
        ctx.json(MappingResponse.from(m, idpName(s, m)));
    }

    private static void getById(Context ctx, State s) {
        Checks.requireAnchor(Auth.current());
        EmailDomainMapping m = byId(s, ctx.pathParam("id"));
        ctx.json(MappingResponse.from(m, idpName(s, m)));
    }

    // ── Writes ─────────────────────────────────────────────────────────────

    private static void create(Context ctx, State s) {
        Checks.requireAnchor(Auth.current());
        var cmd = ctx.bodyAsClass(CreateMappingRequest.class).toCommand();
        var event = CreateEmailDomainMapping.of(s.repo()).run(s.uow(), cmd, Auth.executionContext());
        ctx.status(201).json(new CreatedResponse(event.mappingId()));
    }

    private static void update(Context ctx, State s) {
        Checks.requireAnchor(Auth.current());
        var cmd = ctx.bodyAsClass(UpdateMappingRequest.class).toCommand(ctx.pathParam("id"));
        UpdateEmailDomainMapping.of(s.repo()).run(s.uow(), cmd, Auth.executionContext());
        ctx.status(204);
    }

    private static void moveProvider(Context ctx, State s) {
        Checks.requireAnchor(Auth.current());
        var cmd = ctx.bodyAsClass(MoveProviderRequest.class).toCommand(ctx.pathParam("id"));
        var result = MoveEmailDomainMappingProvider.of(s.repo()).run(s.uow(), cmd, Auth.executionContext());
        ctx.json(MoveProviderResponse.from(result));
    }

    private static void delete(Context ctx, State s) {
        Checks.requireAnchor(Auth.current());
        DeleteEmailDomainMapping.of(s.repo()).run(s.uow(), new DeleteCommand(ctx.pathParam("id")), Auth.executionContext());
        ctx.status(204);
    }

    // ── Read-side helpers ──────────────────────────────────────────────────

    private static EmailDomainMapping byId(State s, String id) {
        return s.repo().findById(id).orElseThrow(() -> HttpError.notFound("EmailDomainMapping", id));
    }

    /// Matched as given — not normalised (spec §3, open question 5).
    private static EmailDomainMapping byDomain(State s, String domain) {
        return s.repo().findByEmailDomain(domain).orElseThrow(() -> HttpError.notFound("EmailDomainMapping", domain));
    }

    private static String requireDomain(String domain, String message) {
        if (domain == null || domain.isEmpty()) throw HttpError.badRequest("DOMAIN_REQUIRED", message);
        return domain;
    }

    /// The mapping's provider display name, or `null` when the provider row does not exist.
    private static String idpName(State s, EmailDomainMapping m) {
        return s.repo().identityProvider(m.identityProviderId()).map(EmailDomainMappingRepository.IdentityProviderRef::name).orElse(null);
    }

    // ── Wire DTOs (lockfile components) ────────────────────────────────────

    /// Body of `POST /api/email-domain-mappings`.
    public record CreateMappingRequest(String emailDomain, String identityProviderId, String scopeType,
                                       String primaryClientId, List<String> additionalClientIds, List<String> grantedClientIds,
                                       String requiredOidcTenantId, boolean require2fa, List<String> allowed2faMethods,
                                       boolean rememberDeviceEnabled, Integer rememberDeviceDays) {
        public CreateCommand toCommand() {
            return new CreateCommand(emailDomain, identityProviderId, scopeType, primaryClientId, additionalClientIds,
                    grantedClientIds, requiredOidcTenantId, require2fa, allowed2faMethods, rememberDeviceEnabled, rememberDeviceDays);
        }
    }

    /// Body of `PUT /api/email-domain-mappings/{id}`; absent-value semantics per spec §1.
    public record UpdateMappingRequest(String primaryClientId, List<String> additionalClientIds, List<String> grantedClientIds,
                                       String requiredOidcTenantId, Boolean require2fa, List<String> allowed2faMethods,
                                       Boolean rememberDeviceEnabled, Integer rememberDeviceDays) {
        public UpdateCommand toCommand(String id) {
            return new UpdateCommand(id, primaryClientId, additionalClientIds, grantedClientIds, requiredOidcTenantId,
                    require2fa, allowed2faMethods, rememberDeviceEnabled, rememberDeviceDays);
        }
    }

    /// Body of `POST /api/email-domain-mappings/{id}/move-provider`.
    public record MoveProviderRequest(String identityProviderId) {
        public MoveProviderCommand toCommand(String id) {
            return new MoveProviderCommand(id, identityProviderId);
        }
    }

    /// The wire shape of one mapping; `identityProviderName`, `primaryClientId`
    /// and `requiredOidcTenantId` are omitted when `null`; lists are always present.
    public record MappingResponse(
            String id,
            String emailDomain,
            String identityProviderId,
            String identityProviderName,
            String scopeType,
            String primaryClientId,
            List<String> additionalClientIds,
            List<String> grantedClientIds,
            String requiredOidcTenantId,
            boolean require2fa,
            List<String> allowed2faMethods,
            boolean rememberDeviceEnabled,
            int rememberDeviceDays,
            Instant createdAt,
            Instant updatedAt) {

        public static MappingResponse from(EmailDomainMapping m, String identityProviderName) {
            var tf = m.twoFactor();
            return new MappingResponse(m.id(), m.emailDomain(), m.identityProviderId(), identityProviderName,
                    m.scopeType().name(), m.primaryClientId(), m.additionalClientIds(), m.grantedClientIds(),
                    m.requiredOidcTenantId(), tf.required(), tf.allowedMethods().stream().map(MfaMethod::name).toList(),
                    tf.rememberDeviceEnabled(), tf.rememberDeviceDays(), m.createdAt(), m.updatedAt());
        }
    }

    /// `{"mappings": [...], "total": n}` — `total` is the list size (no pagination).
    public record MappingListResponse(List<MappingResponse> mappings, int total) {
        public MappingListResponse {
            mappings = mappings == null ? List.of() : List.copyOf(mappings);
        }

        /// Resolves every distinct provider name in one query.
        public static MappingListResponse from(List<EmailDomainMapping> mappings, EmailDomainMappingRepository repo) {
            Map<String, String> names = repo.identityProviderNames(
                    mappings.stream().map(EmailDomainMapping::identityProviderId).distinct().toList());
            var items = mappings.stream().map(m -> MappingResponse.from(m, names.get(m.identityProviderId()))).toList();
            return new MappingListResponse(items, items.size());
        }
    }

    /// `{"found": false}` — the lookup's answer for an unmapped domain.
    public record LookupNotFoundResponse(boolean found) {
        static final LookupNotFoundResponse INSTANCE = new LookupNotFoundResponse(false);
    }

    /// Result of a provider move.
    public record MoveProviderResponse(String mappingId, String emailDomain, String fromIdentityProviderId,
                                       String toIdentityProviderId, int usersReset) {
        public static MoveProviderResponse from(MoveProviderResult r) {
            return new MoveProviderResponse(r.mappingId(), r.emailDomain(), r.fromIdentityProviderId(), r.toIdentityProviderId(), r.usersReset());
        }
    }
}
