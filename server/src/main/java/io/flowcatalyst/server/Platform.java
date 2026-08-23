package io.flowcatalyst.server;

import io.flowcatalyst.platform.client.ClientRepository;
import io.flowcatalyst.platform.client.api.ClientApi;
import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.platform.application.ClientConfigRepository;
import io.flowcatalyst.platform.application.api.ApplicationApi;
import io.flowcatalyst.platform.audit.AuditLogRepository;
import io.flowcatalyst.platform.audit.api.AuditLogApi;
import io.flowcatalyst.platform.eventtype.EventTypeRepository;
import io.flowcatalyst.platform.cors.CorsOriginRepository;
import io.flowcatalyst.platform.cors.api.CorsOriginApi;
import io.flowcatalyst.platform.connection.ConnectionRepository;
import io.flowcatalyst.platform.connection.api.ConnectionApi;
import io.flowcatalyst.platform.dispatchpool.DispatchPoolRepository;
import io.flowcatalyst.platform.dispatchpool.api.DispatchPoolApi;
import io.flowcatalyst.platform.eventtype.api.EventTypeApi;
import io.flowcatalyst.platform.role.PermissionRepository;
import io.flowcatalyst.platform.role.RoleRepository;
import io.flowcatalyst.platform.role.api.RoleApi;
import io.flowcatalyst.platform.process.ProcessRepository;
import io.flowcatalyst.platform.process.api.ProcessApi;
import io.flowcatalyst.platform.shared.auth.Authenticator;
import io.flowcatalyst.platform.subscription.SubscriptionRepository;
import io.flowcatalyst.platform.subscription.api.SubscriptionApi;
import io.flowcatalyst.platform.platformconfig.ConfigAccessRepository;
import io.flowcatalyst.platform.platformconfig.PlatformConfigRepository;
import io.flowcatalyst.platform.platformconfig.api.PlatformConfigApi;
import io.flowcatalyst.platform.shared.auth.ClaimsResolver;
import io.flowcatalyst.platform.shared.auth.CorrelationId;
import io.flowcatalyst.platform.shared.auth.JwtVerifier;
import io.flowcatalyst.platform.shared.auth.SigningKeys;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.shared.openapi.Lockfile;
import io.flowcatalyst.platform.shared.openapi.SpecRoutes;
import io.flowcatalyst.platform.shared.platformsink.PlatformSink;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.router.JavalinDefaultRoutingApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.util.Objects;

/// `WirePlatform`: instantiates every subdomain's repository + operations +
/// HTTP routes against the pool and registers them. The resulting routes are
/// the platform's complete public API surface.
///
/// Mirrors the Go phase split:
///
///   - `repos`    — one repository per subdomain (built here as aggregates land)
///   - `services` — auth provider, OAuth token service, webauthn, email/2FA, login
///   - public routes — everything OUTSIDE the auth middleware (login, public API,
///     password reset, `/oauth/authorize`, `/api/dispatch/process`)
///   - platform API — the authenticated surface: every `XxxApi.register(routes, state)`
///   - spec routes — unauthenticated OpenAPI/Swagger
///
/// Adding a subdomain is the same four-line ritual as in Go: build the repo,
/// build the use cases, build the api `State`, register it.
public final class Platform {

    private static final Logger LOG = LoggerFactory.getLogger(Platform.class);

    private final Env env;
    private final DataSource pool;
    private final SigningKeys signingKeys;
    private final UnitOfWork uow;

    public Platform(Env env, DataSource pool, SigningKeys signingKeys) {
        this.env = Objects.requireNonNull(env, "env");
        this.pool = Objects.requireNonNull(pool, "pool");
        this.signingKeys = Objects.requireNonNull(signingKeys, "signingKeys");
        this.uow = new UnitOfWork(pool, new PlatformSink(Json.MAPPER));
    }

    public UnitOfWork unitOfWork() {
        return uow;
    }

    /// Registers the whole platform surface on `routes`.
    public void register(JavalinDefaultRoutingApi routes) {
        // ── cross-cutting ────────────────────────────────────────────────
        CorrelationId.install(routes);
        HttpError.install(routes);
        routes.before(authenticated(buildAuthenticator()));

        // ── public routes (outside the bearer middleware) ────────────────
        // TODO(port): login endpoint public routes (/auth/login, /auth/logout), publicapi
        //   (/api/public/*), password reset (/auth/password-reset/*), /oauth/authorize,
        //   POST /api/dispatch/process (HMAC job-token auth).

        // ── authenticated platform API ───────────────────────────────────
        // TODO(port): the remaining aggregate registrations from wire_routes.go, in order:
        //   client, role, application, principal, portalusers, resetapproval, serviceaccount,
        //   auth (OAuth clients), oauth token/introspect/revoke/userinfo/discovery, OIDC bridge
        //   + portal auth, cors, connection, subscription, dispatchpool, [eventtype: done],
        //   sdksync, event, audit, docs, dispatchjob, identityprovider, emaildomain,
        //   loginattempt, platformconfig, process, scheduledjob, webauthn; then bff/*, me,
        //   clientselection, sdk batch endpoints.
        var eventTypeRepo = new EventTypeRepository(pool);
        EventTypeApi.register(routes, new EventTypeApi.State(eventTypeRepo, uow));
        var connectionRepo = new ConnectionRepository(pool);
        ConnectionApi.register(routes, new ConnectionApi.State(connectionRepo, uow));
        var dispatchPoolRepo = new DispatchPoolRepository(pool);
        DispatchPoolApi.register(routes, new DispatchPoolApi.State(dispatchPoolRepo, uow));
        var roleRepo = new RoleRepository(pool);
        RoleApi.register(routes, new RoleApi.State(roleRepo, new PermissionRepository(pool), uow));
        var applicationRepo = new ApplicationRepository(pool);
        ApplicationApi.register(routes, new ApplicationApi.State(applicationRepo, new ClientConfigRepository(pool), roleRepo, uow));
        var clientRepo = new ClientRepository(pool);
        ClientApi.register(routes, new ClientApi.State(clientRepo, new ApplicationRepository(pool), new ClientConfigRepository(pool), uow));
        var subscriptionRepo = new SubscriptionRepository(pool);
        SubscriptionApi.register(routes, new SubscriptionApi.State(subscriptionRepo, uow));
        var platformConfigRepo = new PlatformConfigRepository(pool);
        PlatformConfigApi.register(routes, new PlatformConfigApi.State(platformConfigRepo, new ConfigAccessRepository(pool), uow));
        var processRepo = new ProcessRepository(pool);
        ProcessApi.register(routes, new ProcessApi.State(processRepo, uow));
        var corsOriginRepo = new CorsOriginRepository(pool);
        CorsOriginApi.register(routes, new CorsOriginApi.State(corsOriginRepo, uow));
        AuditLogApi.register(routes, new AuditLogApi.State(new AuditLogRepository(pool)));

        // ── spec + docs (unauthenticated) ────────────────────────────────
        new SpecRoutes(Lockfile.load(Json.MAPPER)).register(routes);

        LOG.info("platform API wired");
    }

    /// The bearer/cookie authenticator built from the signing keys: RS256,
    /// current + previous public key, issuer == audience == `FC_JWT_ISSUER`.
    private Authenticator buildAuthenticator() {
        var verificationKeys = signingKeys.rotation().verificationKeys().stream()
                .map(SigningKeys.PublicKeyEntry::publicKey)
                .toList();
        var verifier = new JwtVerifier(new JwtVerifier.Config(env.jwtIssuer(), JwtVerifier.RsaKeys.of(verificationKeys)));
        // TODO(port): DB-backed ClaimsResolver (authProvider.ResolveClaims) for the fc_session cookie path
        //   and role → permission flattening. Until then cookie sessions resolve to unauthenticated.
        return new Authenticator(verifier, ClaimsResolver.none(), Authenticator.Config.of(env.authAllowTestHeaders()));
    }

    /// Go applies the Authenticator to the chi Group that holds every
    /// platform route, and mounts the public surface on the parent router.
    /// Javalin has no groups, so the same partition is expressed as a path
    /// predicate: the middleware runs for the platform prefixes and skips the
    /// routes Go registers outside the group.
    static Handler authenticated(Authenticator authenticator) {
        return ctx -> {
            if (isPlatformPath(ctx) && !isPublicPath(ctx)) {
                authenticator.handle(ctx);
            }
        };
    }

    static boolean isPlatformPath(Context ctx) {
        String p = ctx.path();
        return p.startsWith("/api/") || p.startsWith("/auth/") || p.startsWith("/oauth/")
                || p.startsWith("/bff/") || p.startsWith("/portal/") || p.startsWith("/.well-known/");
    }

    /// `registerPublicRoutes` + `registerSpecRoutes` in Go.
    static boolean isPublicPath(Context ctx) {
        String p = ctx.path();
        return p.equals("/auth/login") || p.equals("/auth/logout")
                || p.startsWith("/auth/password-reset/")
                || p.startsWith("/api/public/")
                || p.equals("/oauth/authorize")
                || p.equals("/api/dispatch/process")
                || p.equals("/api/openapi.json") || p.equals("/api/openapi.yaml");
    }
}
