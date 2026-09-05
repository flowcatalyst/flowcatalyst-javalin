package io.flowcatalyst.server;

import io.flowcatalyst.platform.client.ClientRepository;
import io.flowcatalyst.platform.client.api.ClientApi;
import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.platform.application.ClientConfigRepository;
import io.flowcatalyst.platform.application.api.ApplicationApi;
import io.flowcatalyst.platform.audit.AuditLogRepository;
import io.flowcatalyst.platform.audit.api.AuditLogApi;
import io.flowcatalyst.platform.auth.claims.DbClaimsResolver;
import io.flowcatalyst.platform.auth.token.TokenIssuer;
import io.flowcatalyst.platform.auth.login.SessionCookie;
import io.flowcatalyst.platform.auth.login.MfaChallenge;
import io.flowcatalyst.platform.auth.login.LoginApi;
import io.flowcatalyst.platform.auth.login.BackoffPolicy;
import io.flowcatalyst.platform.auth.login.BackoffCheck;
import io.flowcatalyst.platform.bff.DashboardRepository;
import io.flowcatalyst.platform.bff.api.DashboardBff;
import io.flowcatalyst.platform.bff.api.DeveloperBff;
import io.flowcatalyst.platform.bff.api.EventTypesBff;
import io.flowcatalyst.platform.bff.api.FilterOptionsBff;
import io.flowcatalyst.platform.bff.api.MeApi;
import io.flowcatalyst.platform.bff.api.RolesBff;
import io.flowcatalyst.platform.bff.api.ScheduledJobsBff;
import io.flowcatalyst.platform.event.EventRepository;
import io.flowcatalyst.platform.event.api.EventApi;
import io.flowcatalyst.platform.ingest.api.IngestApi;
import io.flowcatalyst.platform.eventtype.EventTypeRepository;
import io.flowcatalyst.platform.cors.CorsOriginRepository;
import io.flowcatalyst.platform.cors.api.CorsOriginApi;
import io.flowcatalyst.platform.cors.filter.CorsAllowlist;
import io.flowcatalyst.platform.cors.filter.CorsFilter;
import io.flowcatalyst.platform.connection.ConnectionRepository;
import io.flowcatalyst.platform.connection.api.ConnectionApi;
import io.flowcatalyst.platform.dispatchjob.DispatchJobReaper;
import io.flowcatalyst.platform.dispatchjob.DispatchJobRepository;
import io.flowcatalyst.platform.dispatchjob.api.DispatchJobApi;
import io.flowcatalyst.platform.dispatchjob.processing.ProcessingApi;
import io.flowcatalyst.platform.dispatchjob.processing.SubscriberDelivery;
import io.flowcatalyst.platform.dispatchjob.settled.HmacTokenVerifier;
import io.flowcatalyst.platform.dispatchjob.settled.SettledApi;
import io.flowcatalyst.platform.dispatchpool.DispatchPoolRepository;
import io.flowcatalyst.platform.dispatchpool.api.DispatchPoolApi;
import io.flowcatalyst.platform.docs.AppDocRepository;
import io.flowcatalyst.platform.docs.PublishedDocs;
import io.flowcatalyst.platform.docs.api.DocsApi;
import io.flowcatalyst.platform.eventtype.api.EventTypeApi;
import io.flowcatalyst.platform.emaildomainmapping.EmailDomainMappingRepository;
import io.flowcatalyst.platform.emaildomainmapping.api.EmailDomainMappingApi;
import io.flowcatalyst.platform.authadmin.AnchorDomainRepository;
import io.flowcatalyst.platform.authadmin.ClientAuthConfigRepository;
import io.flowcatalyst.platform.authadmin.IdpRoleMappingRepository;
import io.flowcatalyst.platform.authadmin.api.AuthAdminConfigApi;
import io.flowcatalyst.platform.identityprovider.IdentityProviderRepository;
import io.flowcatalyst.platform.identityprovider.api.ClientSecretEncryption;
import io.flowcatalyst.platform.identityprovider.api.IdentityProviderApi;
import io.flowcatalyst.platform.oauthclient.OAuthClientRepository;
import io.flowcatalyst.platform.oauthclient.api.OAuthClientApi;
import io.flowcatalyst.platform.loginattempt.LoginAttemptRepository;
import io.flowcatalyst.platform.loginattempt.api.LoginAttemptApi;
import io.flowcatalyst.platform.role.PermissionRepository;
import io.flowcatalyst.platform.role.RoleRepository;
import io.flowcatalyst.platform.role.api.RoleApi;
import io.flowcatalyst.platform.principal.AnchorDomains;
import io.flowcatalyst.platform.principal.ClientAccessGrantRepository;
import io.flowcatalyst.platform.principal.InviteEmailer;
import io.flowcatalyst.platform.principal.MfaService;
import io.flowcatalyst.platform.principal.Notifier;
import io.flowcatalyst.platform.principal.PasswordResetEmailer;
import io.flowcatalyst.platform.principal.PrincipalRepository;
import io.flowcatalyst.platform.principal.api.PrincipalApi;
import io.flowcatalyst.platform.principal.operations.DeveloperSecrets;
import io.flowcatalyst.platform.openapispecs.OpenApiSpecRepository;
import io.flowcatalyst.platform.process.ProcessRepository;
import io.flowcatalyst.platform.process.api.ProcessApi;
import io.flowcatalyst.platform.publicapi.Branding;
import io.flowcatalyst.platform.publicapi.api.PublicApi;
import io.flowcatalyst.platform.shared.auth.Authenticator;
import io.flowcatalyst.platform.scheduledjob.ScheduledJobInstanceRepository;
import io.flowcatalyst.platform.scheduledjob.ScheduledJobRepository;
import io.flowcatalyst.platform.sdksync.api.SdkSyncApi;
import io.flowcatalyst.platform.scheduledjob.api.ScheduledJobApi;
import io.flowcatalyst.platform.serviceaccount.ServiceAccountRepository;
import io.flowcatalyst.platform.serviceaccount.api.ServiceAccountApi;
import io.flowcatalyst.platform.serviceaccount.operations.RsaServiceAccountTokenMinter;
import io.flowcatalyst.platform.serviceaccount.operations.ServiceAccountTokenMinter;
import io.flowcatalyst.platform.subscription.SubscriptionRepository;
import io.flowcatalyst.platform.subscription.api.SubscriptionApi;
import io.flowcatalyst.platform.platformconfig.ConfigAccessRepository;
import io.flowcatalyst.platform.platformconfig.PlatformConfigRepository;
import io.flowcatalyst.platform.platformconfig.api.PlatformConfigApi;
import io.flowcatalyst.platform.shared.auth.ClaimsResolver;
import io.flowcatalyst.platform.shared.auth.CorrelationId;
import io.flowcatalyst.platform.shared.auth.JwtVerifier;
import io.flowcatalyst.platform.shared.auth.SigningKeys;
import io.flowcatalyst.platform.shared.encryption.Encryption;
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
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

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

    /// Registers the whole platform surface on `routes`. Returns the started
    /// dispatch-job reaper (dispatch-seam spec §7) so the caller can stop it —
    /// `register` is the only place one gets constructed, so it is the only
    /// place that can hand the handle back.
    public DispatchJobReaper register(JavalinDefaultRoutingApi routes) {
        // ── cross-cutting ────────────────────────────────────────────────
        CorrelationId.install(routes);
        HttpError.install(routes);
        // Built here, ahead of the authenticator, because the CORS filter (spec §9) must
        // answer a preflight before the bearer check ever runs; CorsOriginApi.register
        // below reuses the SAME repository instance and wires `onChange` to invalidate it.
        var corsOriginRepo = new CorsOriginRepository(pool);
        var corsAllowlist = new CorsAllowlist(corsOriginRepo::allowedOrigins, Duration.ofMillis(env.corsCacheTtlMs()), Clock.systemUTC());
        routes.before(cors(new CorsFilter(corsAllowlist)));
        routes.before(authenticated(buildAuthenticator()));

        // ── public routes (outside the bearer middleware) ────────────────
        // The session surface (auth-core §6.1): check-domain, login and logout
        // are public; /auth/me and /auth/login-history run inside the
        // authenticator (isPublicPath decides). /auth/refresh, change-password
        // and the 2FA routes land with the grant store and the MFA unit.
        var loginPrincipalRepo = new PrincipalRepository(pool);
        var loginAttemptRepo = new LoginAttemptRepository(pool);
        var tokenIssuer = new TokenIssuer(signingKeys, TokenIssuer.Config.of(env.jwtIssuer()));
        var backoff = new BackoffCheck(loginAttemptRepo, BackoffPolicy.fromEnv(EnvReader.system()));
        LoginApi.register(routes, new LoginApi.State(loginPrincipalRepo, new EmailDomainMappingRepository(pool),
                new IdentityProviderRepository(pool), loginAttemptRepo, backoff, tokenIssuer,
                new DbClaimsResolver(loginPrincipalRepo, new RoleRepository(pool)), MfaChallenge.none(),
                new SessionCookie(!env.authAllowTestHeaders()), pool, Clock.systemUTC()));
        // TODO(port): password reset (/auth/password-reset/*), /oauth/authorize.
        //   POST /api/dispatch/process (HMAC job-token auth) is registered below, alongside /api/dispatch/settled.

        // ── authenticated platform API ───────────────────────────────────
        // TODO(port): the registrations from wire_routes.go still missing are all Phase 3
        //   (docs/port-plan.md, gated on docs/auth-rulings.md):
        //   oauth token/introspect/revoke/userinfo/discovery, OIDC bridge + portal auth,
        //   portalusers, resetapproval, webauthn, clientselection; plus the CORS filter
        //   (Phase 4). Everything else below is registered in Go's order.
        var eventTypeRepo = new EventTypeRepository(pool);
        EventTypeApi.register(routes, new EventTypeApi.State(eventTypeRepo, uow));
        var connectionRepo = new ConnectionRepository(pool);
        ConnectionApi.register(routes, new ConnectionApi.State(connectionRepo, uow));
        var dispatchPoolRepo = new DispatchPoolRepository(pool);
        DispatchPoolApi.register(routes, new DispatchPoolApi.State(dispatchPoolRepo, uow));
        var roleRepo = new RoleRepository(pool);
        var permissionRepo = new PermissionRepository(pool);
        RoleApi.register(routes, new RoleApi.State(roleRepo, permissionRepo, uow));
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
        CorsOriginApi.register(routes, new CorsOriginApi.State(corsOriginRepo, uow, corsAllowlist::invalidate));
        AuditLogApi.register(routes, new AuditLogApi.State(new AuditLogRepository(pool)));

        var emailDomainMappingRepo = new EmailDomainMappingRepository(pool);
        EmailDomainMappingApi.register(routes, new EmailDomainMappingApi.State(emailDomainMappingRepo, uow));
        var anchorDomainRepo = new AnchorDomainRepository(pool);
        var authConfigRepo = new ClientAuthConfigRepository(pool);
        var idpRoleMappingRepo = new IdpRoleMappingRepository(pool);
        AuthAdminConfigApi.register(routes, new AuthAdminConfigApi.State(anchorDomainRepo, authConfigRepo, idpRoleMappingRepo, uow));
        var identityProviderRepo = new IdentityProviderRepository(pool);
        // Built from `env`, not the process environment: fcdev loads its environment (and the app key it
        // generates) from a map, so reading System.getenv() here would silently disable encryption there.
        IdentityProviderApi.register(routes, new IdentityProviderApi.State(identityProviderRepo, emailDomainMappingRepo, uow,
                ClientSecretEncryption.of(Encryption.fromKeys(env.appKey(), env.appKeyPrevious()))));
        LoginAttemptApi.register(routes, new LoginAttemptApi.State(new LoginAttemptRepository(pool)));
        var dispatchJobRepo = new DispatchJobRepository(pool);
        DispatchJobApi.register(routes, new DispatchJobApi.State(dispatchJobRepo, uow));
        // The reaper (dispatch-seam spec §7) is not leader-gated — every sweep is one
        // idempotent, status-guarded UPDATE — so it starts unconditionally here, unlike the
        // leader-gated loops Server starts. Returned below so Server.Running#stop() can
        // close it.
        var dispatchJobReaper = new DispatchJobReaper(dispatchJobRepo).start();
        // /api/dispatch/settled and /api/dispatch/process (dispatch-seam spec §5, §6, §11):
        // public routes, registered below via Platform.isPublicPath; fail-closed on a missing
        // FLOWCATALYST_APP_KEY, matching Go's scheduler + processing/settled mount ("refuses to
        // start without it"). Both self-verify the same scheduler-signed per-job HMAC bearer, so
        // they share one HmacTokenVerifier instance.
        if (env.appKey() != null && !env.appKey().isBlank()) {
            var dispatchAuthVerifier = HmacTokenVerifier.fromAppKey(env.appKey());
            SettledApi.register(routes, new SettledApi.State(dispatchJobRepo, dispatchAuthVerifier));
            // DeliveryCredentials.none() (dispatch-seam spec §5, §15): the platform has no
            // serviceaccount aggregate yet to resolve job -> subscription -> application ->
            // service-account webhook credentials from, so every delivery goes out bare until
            // that aggregate lands.
            ProcessingApi.register(routes, new ProcessingApi.State(dispatchJobRepo, dispatchAuthVerifier,
                    new SubscriberDelivery(SubscriberDelivery.defaultClient())));
        } else {
            LOG.warn("FLOWCATALYST_APP_KEY not configured; /api/dispatch/settled and /api/dispatch/process are not mounted");
        }
        var appDocRepo = new AppDocRepository(pool);
        DocsApi.register(routes, new DocsApi.State(appDocRepo, applicationRepo, PublishedDocs.load()));
        var eventRepo = new EventRepository(pool);
        var eventApiState = new EventApi.State(eventRepo);
        EventApi.register(routes, eventApiState);
        // SDK ingest (docs/spec/sdk-ingest.md): infra batch inserts, no unit of work — the POSTs
        // alongside the GET-only EventApi/DispatchJobApi/AuditLogApi read surfaces above.
        var ingestState = IngestApi.State.of(eventRepo, dispatchJobRepo, new AuditLogRepository(pool), clientRepo, applicationRepo);
        IngestApi.register(routes, ingestState);
        var principalRepo = new PrincipalRepository(pool);
        // Emailers, notifier and MFA are stubs until their subsystems land (docs/spec/principal.md §10);
        // the developer client-secret is encrypted under the app key from `env`, like the IdP secrets above.
        PrincipalApi.register(routes, new PrincipalApi.State(principalRepo, new ClientAccessGrantRepository(pool), roleRepo,
                applicationRepo, new ClientConfigRepository(pool), clientRepo, emailDomainMappingRepo, identityProviderRepo,
                AnchorDomains.inDatabase(pool), PasswordResetEmailer.notConfigured(), InviteEmailer.logging(), Notifier.logging(),
                MfaService.notConfigured(),
                Encryption.fromKeys(env.appKey(), env.appKeyPrevious()).map(DeveloperSecrets::withEncryption).orElseGet(DeveloperSecrets::unconfigured),
                uow));

        // serviceaccount (docs/spec/serviceaccount.md): webhook credentials are encrypted at rest
        // under the same app key as the developer secrets above. The token mint signs RS256 under
        // the platform signing key, exactly as the platform's own token service will, so a minted
        // bearer is accepted by this server's authenticator (issuer == audience, as buildAuthenticator).
        var serviceAccountRepo = new ServiceAccountRepository(pool, Encryption.fromKeys(env.appKey(), env.appKeyPrevious()));
        ServiceAccountTokenMinter serviceAccountTokenMinter =
                new RsaServiceAccountTokenMinter(signingKeys, env.jwtIssuer(), env.jwtIssuer());
        Function<List<String>, List<String>> flattenServiceAccountPermissions = roleNames -> roleNames.stream()
                .flatMap(name -> roleRepo.findByName(name).stream())
                .flatMap(role -> role.permissions().stream())
                .distinct()
                .sorted()
                .toList();
        ServiceAccountApi.register(routes, new ServiceAccountApi.State(serviceAccountRepo, principalRepo, uow,
                serviceAccountTokenMinter, flattenServiceAccountPermissions));

        // oauthclient (docs/spec/auth-core.md §3.6, §6.3; A-22 secret-rotation grace):
        // client secrets are encrypted at rest under the same app key as the developer
        // and service-account secrets above. Registered in Go's wire_routes.go order —
        // right after serviceaccount, ahead of the /oauth/* token routes that do not
        // exist yet (Phase 3, still TODO above).
        var oauthClientRepo = new OAuthClientRepository(pool, applicationRepo);
        OAuthClientApi.register(routes, new OAuthClientApi.State(oauthClientRepo, uow,
                Encryption.fromKeys(env.appKey(), env.appKeyPrevious())));

        var scheduledJobRepo = new ScheduledJobRepository(pool);
        ScheduledJobApi.register(routes, new ScheduledJobApi.State(scheduledJobRepo, new ScheduledJobInstanceRepository(pool), uow));
        // The SDK self-registration surface (docs/spec/sdksync.md). Registered
        // AFTER the aggregates because it assembles THEIR commands from the
        // SAME repository instances — a second set would read a different
        // connection's view of rows the aggregate had just written.
        // This is also where `openapispecs` reaches the router: the unit was
        // complete but unregistered, and `/openapi/sync` is its only route.
        var openApiSpecRepo = new OpenApiSpecRepository(pool);
        SdkSyncApi.register(routes, new SdkSyncApi.State(applicationRepo, eventTypeRepo, roleRepo, subscriptionRepo,
                connectionRepo, processRepo, dispatchPoolRepo, scheduledJobRepo, openApiSpecRepo,
                appDocRepo, principalRepo, uow));

        // public, pre-login reads (spec docs/spec/publicapi.md): outside the authenticator via isPublicPath, outside the lockfile
        PublicApi.register(routes, new PublicApi.State(new Branding(platformConfigRepo)));

        // ── spec + docs (unauthenticated) ────────────────────────────────
        var lockfile = Lockfile.load(Json.MAPPER);
        new SpecRoutes(lockfile).register(routes);

        // ── SPA's own BFF routes + /api/me (docs/spec/bff.md) ─────────────
        // Cookie- or bearer-authenticated, same Authenticator as /api (both
        // match `Platform.isPlatformPath`); `LockfileCoverageTest` excludes
        // `/bff/*` and `/api/me*` by design (bff spec §1). The aggregate
        // mounts reuse the SAME handlers/state as their `/api` registrations
        // above under a second base path (Go `registerBFF`/`registerAt`).
        DashboardBff.register(routes, new DashboardBff.State(new DashboardRepository(pool)));
        FilterOptionsBff.register(routes, new FilterOptionsBff.State(clientRepo, eventTypeRepo));
        DeveloperBff.register(routes, new DeveloperBff.State(applicationRepo, openApiSpecRepo, eventTypeRepo, uow,
                lockfile::json));
        EventTypesBff.register(routes, new EventTypesBff.State(eventTypeRepo, uow));
        RolesBff.register(routes, new RolesBff.State(roleRepo, permissionRepo, applicationRepo, uow));
        ScheduledJobsBff.register(routes, new ScheduledJobsBff.State(scheduledJobRepo,
                new ScheduledJobInstanceRepository(pool), clientRepo, applicationRepo));
        MeApi.register(routes, new MeApi.State(principalRepo, applicationRepo, clientRepo, new ClientConfigRepository(pool)));
        EventApi.registerAt(routes, "/bff/events", eventApiState);
        IngestApi.registerEventsBatchAt(routes, "/bff/events/batch", ingestState);
        DispatchJobApi.registerAt(routes, "/bff/dispatch-jobs", new DispatchJobApi.State(dispatchJobRepo, uow));
        ProcessApi.registerAt(routes, "/bff/processes", new ProcessApi.State(processRepo, uow));

        LOG.info("platform API wired");
        return dispatchJobReaper;
    }

    /// The bearer/cookie authenticator built from the signing keys: RS256,
    /// current + previous public key, issuer == audience == `FC_JWT_ISSUER`.
    private Authenticator buildAuthenticator() {
        var verificationKeys = signingKeys.rotation().verificationKeys().stream()
                .map(SigningKeys.PublicKeyEntry::publicKey)
                .toList();
        var verifier = new JwtVerifier(new JwtVerifier.Config(env.jwtIssuer(), JwtVerifier.RsaKeys.of(verificationKeys)));
        // The store-backed resolver: a cookie session is re-resolved from the
        // principal and role stores on every request, and a bearer that
        // carries roles but no scope has its permissions flattened from them
        // (auth-core §3.5, Go provider.ResolveClaims / FlattenPermissions).
        var resolver = new DbClaimsResolver(new PrincipalRepository(pool), new RoleRepository(pool));
        return new Authenticator(verifier, resolver, Authenticator.Config.of(env.authAllowTestHeaders()));
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

    /// Scopes the CORS filter (spec §9) to the platform surface, the same
    /// way [#authenticated] scopes the authenticator — the SPA's own static
    /// assets are same-origin and need no CORS headers.
    static Handler cors(CorsFilter filter) {
        return ctx -> {
            if (isPlatformPath(ctx)) {
                filter.handle(ctx);
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
        return p.equals("/auth/login") || p.equals("/auth/logout") || p.equals("/auth/check-domain")
                || p.startsWith("/auth/password-reset/")
                || p.startsWith("/api/public/") || p.equals("/api/config/platform")
                || p.equals("/oauth/authorize")
                || p.equals("/api/dispatch/process")
                || p.equals("/api/dispatch/settled")
                || p.equals("/api/openapi.json") || p.equals("/api/openapi.yaml");
    }
}
