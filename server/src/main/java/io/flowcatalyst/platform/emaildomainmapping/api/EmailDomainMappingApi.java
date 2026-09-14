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
import io.flowcatalyst.http.Exchange;
import io.flowcatalyst.http.Group;
import io.flowcatalyst.http.Routes;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static io.flowcatalyst.platform.shared.auth.Permission.EMAIL_DOMAIN_MAPPING_CREATE;
import static io.flowcatalyst.platform.shared.auth.Permission.EMAIL_DOMAIN_MAPPING_DELETE;
import static io.flowcatalyst.platform.shared.auth.Permission.EMAIL_DOMAIN_MAPPING_UPDATE;
import static io.flowcatalyst.platform.shared.auth.Permission.EMAIL_DOMAIN_MAPPING_VIEW;

/// The `/api/email-domain-mappings` surface (spec §3). Mappings are
/// anchor-only: every handler opens with `requireAnchor`, followed by the
/// permission gate (`docs/spec/reach-only-routes.md`), except the
/// `lookup` read, which has no gate at all (spec §3, open question 1 — it is
/// consulted during login, before the caller can hold any permission, so it
/// stays ungated rather than gaining a check it could never pass). A write
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
    public static void register(Routes routes, State s) {
        Routes write = routes.in(Group.API_WRITE);
        routes.get("/api/email-domain-mappings", Auth.scoped(ctx -> list(ctx, s)));
        write.post("/api/email-domain-mappings", Auth.scoped(ctx -> create(ctx, s)));
        routes.get("/api/email-domain-mappings/lookup", Auth.scoped(ctx -> lookup(ctx, s)));
        routes.get("/api/email-domain-mappings/by-domain/{domain}", Auth.scoped(ctx -> getByDomain(ctx, s)));
        routes.get("/api/email-domain-mappings/{id}", Auth.scoped(ctx -> getById(ctx, s)));
        write.put("/api/email-domain-mappings/{id}", Auth.scoped(ctx -> update(ctx, s)));
        write.post("/api/email-domain-mappings/{id}/move-provider", Auth.scoped(ctx -> moveProvider(ctx, s)));
        write.delete("/api/email-domain-mappings/{id}", Auth.scoped(ctx -> delete(ctx, s)));
    }

    // ── Reads ──────────────────────────────────────────────────────────────

    private static void list(Exchange ctx, State s) {
        Checks.requireAnchor(Auth.current());
        Checks.require(Auth.current(), EMAIL_DOMAIN_MAPPING_VIEW);
        List<EmailDomainMapping> mappings = s.repo().findAll();
        ctx.json(MappingListResponse.from(mappings, idpNames(s, mappings)));
    }

    /// No gate (spec §3): answers `{"found": false}` instead of 404 when unmapped.
    private static void lookup(Exchange ctx, State s) {
        String domain = requireDomain(ctx.queryParam("domain"), "domain query param is required");
        Optional<EmailDomainMapping> m = s.repo().findByEmailDomain(domain);
        if (m.isEmpty()) {
            ctx.json(LookupNotFoundResponse.INSTANCE);
            return;
        }
        ctx.json(MappingResponse.from(m.get(), idpName(s, m.get())));
    }

    private static void getByDomain(Exchange ctx, State s) {
        Checks.requireAnchor(Auth.current());
        Checks.require(Auth.current(), EMAIL_DOMAIN_MAPPING_VIEW);
        EmailDomainMapping m = byDomain(s, ctx.pathParam("domain"));
        ctx.json(MappingResponse.from(m, idpName(s, m)));
    }

    private static void getById(Exchange ctx, State s) {
        Checks.requireAnchor(Auth.current());
        Checks.require(Auth.current(), EMAIL_DOMAIN_MAPPING_VIEW);
        EmailDomainMapping m = byId(s, ctx.pathParam("id"));
        ctx.json(MappingResponse.from(m, idpName(s, m)));
    }

    // ── Writes ─────────────────────────────────────────────────────────────

    private static void create(Exchange ctx, State s) {
        Checks.requireAnchor(Auth.current());
        Checks.require(Auth.current(), EMAIL_DOMAIN_MAPPING_CREATE);
        var cmd = ctx.bodyAsClass(CreateMappingRequest.class).toCommand();
        var event = CreateEmailDomainMapping.of(s.repo()).run(s.uow(), cmd, Auth.executionContext());
        ctx.status(201).json(new CreatedResponse(event.mappingId()));
    }

    private static void update(Exchange ctx, State s) {
        Checks.requireAnchor(Auth.current());
        Checks.require(Auth.current(), EMAIL_DOMAIN_MAPPING_UPDATE);
        var cmd = ctx.bodyAsClass(UpdateMappingRequest.class).toCommand(ctx.pathParam("id"));
        UpdateEmailDomainMapping.of(s.repo()).run(s.uow(), cmd, Auth.executionContext());
        ctx.status(204);
    }

    private static void moveProvider(Exchange ctx, State s) {
        Checks.requireAnchor(Auth.current());
        Checks.require(Auth.current(), EMAIL_DOMAIN_MAPPING_UPDATE);
        var cmd = ctx.bodyAsClass(MoveProviderRequest.class).toCommand(ctx.pathParam("id"));
        var result = MoveEmailDomainMappingProvider.of(s.repo()).run(s.uow(), cmd, Auth.executionContext());
        ctx.json(MoveProviderResponse.from(result));
    }

    private static void delete(Exchange ctx, State s) {
        Checks.requireAnchor(Auth.current());
        Checks.require(Auth.current(), EMAIL_DOMAIN_MAPPING_DELETE);
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

    /// The lookup's `domain` query parameter, which must be present and non-empty (spec §3).
    private static String requireDomain(String domain, String message) {
        if (domain == null || domain.isEmpty()) throw HttpError.badRequest("DOMAIN_REQUIRED", message);
        return domain;
    }

    /// The mapping's provider display name, or `null` when the provider row does not exist.
    private static String idpName(State s, EmailDomainMapping m) {
        return s.repo().identityProvider(m.identityProviderId()).map(EmailDomainMappingRepository.IdentityProviderRef::name).orElse(null);
    }

    /// Every distinct provider's display name in one query (ids without a row are absent).
    private static Map<String, String> idpNames(State s, List<EmailDomainMapping> mappings) {
        return s.repo().identityProviderNames(mappings.stream().map(EmailDomainMapping::identityProviderId).distinct().toList());
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

        /// `identityProviderNames` is display name by provider id; a missing key omits the name.
        public static MappingListResponse from(List<EmailDomainMapping> mappings, Map<String, String> identityProviderNames) {
            var items = mappings.stream().map(m -> MappingResponse.from(m, identityProviderNames.get(m.identityProviderId()))).toList();
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
