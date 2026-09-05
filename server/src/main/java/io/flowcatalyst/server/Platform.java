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
import io.flowcatalyst.platform.auth.login.LoginApi;
import io.flowcatalyst.platform.auth.mfa.DomainPolicy;
import io.flowcatalyst.platform.auth.mfa.LoginMfaGate;
import io.flowcatalyst.platform.auth.mfa.MailSender;
import io.flowcatalyst.platform.auth.mfa.Mfa;
import io.flowcatalyst.platform.auth.mfa.MfaRepository;
import io.flowcatalyst.platform.auth.mfa.MfaToken;
import io.flowcatalyst.platform.auth.mfa.TrustedDeviceCookie;
import io.flowcatalyst.platform.auth.mfa.api.ChangePasswordApi;
import io.flowcatalyst.platform.auth.mfa.api.TwoFactorApi;
import io.flowcatalyst.platform.auth.clientselection.ClientSelectionApi;
import io.flowcatalyst.platform.auth.grant.GrantStore;
import io.flowcatalyst.platform.auth.grant.RefreshRotation;
import io.flowcatalyst.platform.auth.oidc.LoginStateRepository;
import io.flowcatalyst.platform.auth.oidc.OidcBridgeApi;
import io.flowcatalyst.platform.auth.oidc.OidcClients;
import io.flowcatalyst.platform.auth.oidc.OidcIpLimit;
import io.flowcatalyst.platform.mail.MailService;
import io.flowcatalyst.platform.notify.Notifications;
import io.flowcatalyst.platform.passkey.CeremonyRepository;
import io.flowcatalyst.platform.passkey.PasskeyRepository;
import io.flowcatalyst.platform.passkey.PasskeyService;
import io.flowcatalyst.platform.passkey.api.PasskeyApi;
import io.flowcatalyst.platform.passwordreset.ApprovalQueue;
import io.flowcatalyst.platform.passwordreset.PasswordResetApi;
import io.flowcatalyst.platform.passwordreset.PortalPasswords;
import io.flowcatalyst.platform.passwordreset.ResetLinks;
import io.flowcatalyst.platform.passwordreset.ResetTokenRepository;
import io.flowcatalyst.platform.auth.oauth.AccessTokenReader;
import io.flowcatalyst.platform.auth.oauth.AuthRefreshApi;
import io.flowcatalyst.platform.auth.oauth.OAuthAuthorizeApi;
import io.flowcatalyst.platform.auth.oauth.OAuthDiscoveryApi;
import io.flowcatalyst.platform.auth.oauth.OAuthIntrospectionApi;
import io.flowcatalyst.platform.auth.oauth.OAuthIpLimits;
import io.flowcatalyst.platform.auth.oauth.OAuthState;
import io.flowcatalyst.platform.auth.oauth.OAuthTokenApi;
import io.flowcatalyst.platform.auth.oauth.OAuthUserinfoApi;
import io.flowcatalyst.platform.auth.ratelimit.Governor;
import io.flowcatalyst.platform.auth.ratelimit.RateLimit;
import io.flowcatalyst.platform.auth.ratelimit.RateLimitStores;
import io.flowcatalyst.platform.auth.token.ClaimLabels;
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
import io.flowcatalyst.platform.portalauth.PortalLoginFlowRepository;
import io.flowcatalyst.platform.portalauth.PortalSso;
import io.flowcatalyst.platform.portalidentity.PortalIdentityAccess;
import io.flowcatalyst.platform.portalauth.api.PortalAuthApi;
import io.flowcatalyst.platform.portalidentity.PortalIdentityRepository;
import io.flowcatalyst.platform.portalidentity.PortalInviteEmailer;
import io.flowcatalyst.platform.portalidentity.api.PortalUserApi;
import io.flowcatalyst.platform.loginattempt.LoginAttemptRepository;
import io.flowcatalyst.platform.loginattempt.api.LoginAttemptApi;
import io.flowcatalyst.platform.role.PermissionRepository;
import io.flowcatalyst.platform.role.RoleRepository;
import io.flowcatalyst.platform.role.api.RoleApi;
import io.flowcatalyst.platform.principal.AnchorDomains;
import io.flowcatalyst.platform.principal.ClientAccessGrantRepository;
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
        var loginMappingRepo = new EmailDomainMappingRepository(pool);
        var tokenIssuer = new TokenIssuer(signingKeys, TokenIssuer.Config.of(env.jwtIssuer()));
        var backoff = new BackoffCheck(loginAttemptRepo, BackoffPolicy.fromEnv(EnvReader.system()));
        // The second factor (auth-identity §6): TOTP secrets under the app key, e-mail
        // PINs through the mail transport, the pending /
        // enrol token derived from the session key, the trusted-device cookie secure
        // whenever the session cookie is. The TOTP label carries the live platform name.
        var cookiesSecure = !env.authAllowTestHeaders();
        var mfaBranding = new Branding(new PlatformConfigRepository(pool));
        // Outbound mail (auth-identity §9): SMTP when FC_SMTP_HOST/SMTP_HOST is set,
        // else the logging transport. The security-notification catalogue (§10) and
        // every link mailer ride on it.
        var mail = MailService.fromEnv(EnvReader.system());
        var notices = new Notifications(mail, mfaBranding::platformName);
        var mfa = new Mfa(new MfaRepository(pool), Encryption.fromKeys(env.appKey(), env.appKeyPrevious()),
                MailSender.of(mail), mfaBranding::platformName, Mfa.Config.DEFAULT, Clock.systemUTC(), new AuditLogRepository(pool));
        var mfaTokens = new MfaToken(signingKeys.privateKey(), env.jwtIssuer());
        var trustedDeviceCookie = new TrustedDeviceCookie(cookiesSecure);
        var mfaGate = new LoginMfaGate(mfa, new DomainPolicy.Evaluator(loginMappingRepo), mfaTokens, trustedDeviceCookie);
        var loginState = new LoginApi.State(loginPrincipalRepo, loginMappingRepo,
                new IdentityProviderRepository(pool), loginAttemptRepo, backoff, tokenIssuer,
                new DbClaimsResolver(loginPrincipalRepo, new RoleRepository(pool)), mfaGate,
                new SessionCookie(cookiesSecure), pool, Clock.systemUTC());
        LoginApi.register(routes, loginState);
        // The 2FA HTTP surface (auth-identity §6.3, §6.4, §6.6, §6.7) and
        // change-password's MFA interplay (§6.8) share LoginApi's own state
        // rather than duplicating principals/attempts/backoff/clock. GrantStore
        // is built here — earlier than the OAuth provider below — so
        // change-password can revoke every refresh token on a password change.
        var grantStore = new GrantStore(pool);
        TwoFactorApi.register(routes, new TwoFactorApi.State(loginState, mfa, new DomainPolicy.Evaluator(loginMappingRepo),
                mfaTokens, trustedDeviceCookie, new AuditLogRepository(pool), notices));
        ChangePasswordApi.register(routes, new ChangePasswordApi.State(loginState, mfa, trustedDeviceCookie, grantStore,
                notices));
        // Password reset (auth-identity §8): the link minter/mailer serves the public
        // /auth/password-reset/* flow here and the principal admin routes below. The
        // portal confirm and the approval queue are seams until their units land.
        // Passkeys (auth-identity §7): the relying party from FC_WEBAUTHN_RP_ID / _ORIGINS
        // with the platform name read once at startup; registration and the credential
        // list are session-gated, authentication is public (isPublicPath) and shares the
        // login backoff budget.
        var passkeyRepo = new PasskeyRepository(pool);
        var passkeyService = new PasskeyService(PasskeyService.Config.fromEnv(EnvReader.system(), mfaBranding.platformName()), passkeyRepo);
        PasskeyApi.register(routes, new PasskeyApi.State(passkeyService, passkeyRepo, new CeremonyRepository(pool), loginPrincipalRepo,
                uow, tokenIssuer, new SessionCookie(cookiesSecure), notices, loginAttemptRepo, backoff, Clock.systemUTC()));
        // The portal identities are built later (after the OAuth-client store); the reset
        // confirm reaches them through this late-bound seam.
        var portalPasswordsHolder = new java.util.concurrent.atomic.AtomicReference<PortalPasswords>(PortalPasswords.notWired());
        PortalPasswords portalPasswords = new PortalPasswords() {
            @Override
            public java.util.Optional<Identity> find(String id) {
                return portalPasswordsHolder.get().find(id);
            }

            @Override
            public boolean setPasswordHash(String id, String hash) {
                return portalPasswordsHolder.get().setPasswordHash(id, hash);
            }
        };
        var resetTokenRepo = new ResetTokenRepository(pool);
        var resetLinks = new ResetLinks(resetTokenRepo, mail, mfaBranding::emailTheme, env.jwtIssuer(), Clock.systemUTC());
        PasswordResetApi.register(routes, new PasswordResetApi.State(resetLinks, resetTokenRepo, loginPrincipalRepo, uow, mfa,
                mfaTokens, new DomainPolicy.Evaluator(loginMappingRepo), grantStore, notices, portalPasswords,
                ApprovalQueue.none(), false, Clock.systemUTC()));
        // /oauth/authorize and /auth/refresh are registered with the provider below, after the OAuth-client store.
        //   POST /api/dispatch/process (HMAC job-token auth) is registered below, alongside /api/dispatch/settled.

        // ── authenticated platform API ───────────────────────────────────
        // Registered in Go's wire_routes.go order.
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
        // The OIDC bridge's client cache (auth-identity §4.1, ruling Q1): the provider
        // API's change hook drops the cached client for an updated or deleted provider.
        var oidcClients = OidcClients.of(identityProviderRepo, emailDomainMappingRepo,
                Encryption.fromKeys(env.appKey(), env.appKeyPrevious()));
        IdentityProviderApi.register(routes, new IdentityProviderApi.State(identityProviderRepo, emailDomainMappingRepo, uow,
                ClientSecretEncryption.of(Encryption.fromKeys(env.appKey(), env.appKeyPrevious())), oidcClients::invalidate));
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
                AnchorDomains.inDatabase(pool), resetLinks, resetLinks, notices,
                mfa,
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

        // portal identity + portal auth (auth-identity spec §3.2, §3.3, §5.1-§5.5, §5.7,
        // §11.2, §11.8): the admin API (`/api/portal-users*`, inside the authenticator)
        // and the portal plane's own public auth surface (`/portal/*`, isPublicPath below).
        // The portal SSO start/callback (§5.6) and the `/oauth/token` `ptu_` branch (§5.8)
        // are wired against these same repository instances by a later unit.
        var portalEnvReader = EnvReader.system();
        var portalIdentityRepo = new PortalIdentityRepository(pool);
        var portalAccess = new PortalIdentityAccess(portalIdentityRepo, uow);
        portalPasswordsHolder.set(portalAccess);
        PortalUserApi.register(routes, new PortalUserApi.State(portalIdentityRepo, clientRepo, oauthClientRepo,
                identityProviderRepo, uow, PortalInviteEmailer.logging()));
        var portalLoginFlowRepo = new PortalLoginFlowRepository(pool);
        PortalAuthApi.register(routes, new PortalAuthApi.State(portalLoginFlowRepo, oauthClientRepo, portalIdentityRepo,
                identityProviderRepo, grantStore, RateLimitStores.build(portalEnvReader, pool),
                RateLimit.Policies.fromEnv(portalEnvReader), PortalInviteEmailer.logging()));

        // The OAuth / OIDC provider (auth-core §6.2, §6.2a, §6.2b). /oauth/authorize
        // and /auth/refresh are public (isPublicPath); the token, introspection,
        // revocation, userinfo and discovery routes run INSIDE the authenticator, which
        // lets a request with no credentials through and 401s an explicit bad bearer
        // (ruling I-Q4). Per-IP throttles sit in front as `before` filters.
        var envReader = EnvReader.system();
        // grantStore was built above, alongside the 2FA / change-password wiring.
        var oauthState = new OAuthState(oauthClientRepo, loginPrincipalRepo, serviceAccountRepo, grantStore,
                new RefreshRotation(grantStore, Clock.systemUTC()), tokenIssuer, new AccessTokenReader(buildVerifier()),
                new DbClaimsResolver(loginPrincipalRepo, roleRepo), ClaimLabels.of(clientRepo, applicationRepo),
                Encryption.fromKeys(env.appKey(), env.appKeyPrevious()), loginAttemptRepo,
                RateLimitStores.build(envReader, pool), RateLimit.Policies.fromEnv(envReader),
                new Governor(Governor.Config.oauthTokenClient(envReader)), signingKeys, env.jwtIssuer(), Clock.systemUTC(),
                portalAccess);
        OAuthIpLimits.register(routes, oauthState, new Governor(Governor.Config.oauthTokenIp(envReader)));
        OAuthAuthorizeApi.register(routes, oauthState);
        OAuthTokenApi.register(routes, oauthState);
        OAuthIntrospectionApi.register(routes, oauthState);
        OAuthUserinfoApi.register(routes, oauthState);
        OAuthDiscoveryApi.register(routes, oauthState);
        AuthRefreshApi.register(routes, oauthState);
        // Client selection (auth-core §6.5): the tenants a user may work in and a
        // switch that mints the same full-authority token /auth/refresh would.
        ClientSelectionApi.register(routes, new ClientSelectionApi.State(loginPrincipalRepo, clientRepo,
                new ClientAccessGrantRepository(pool), tokenIssuer, new DbClaimsResolver(loginPrincipalRepo, roleRepo),
                ClaimLabels.of(clientRepo, applicationRepo)));

        // The OIDC bridge, employee plane (auth-identity §4): start, callback, session
        // end, behind the §4.11 per-IP bucket that /portal/* shares. The portal sink
        // (§5.6) is a seam until the portal unit lands.
        OidcIpLimit.register(routes, new Governor(Governor.Config.oidcBridge(envReader)));
        var loginStateRepo = new LoginStateRepository(pool);
        // The portal SSO sink (§5.6) sits behind the bridge's callback; the bridge
        // state is built with the sink, and the sink's own state points back at it
        // for the callback URL. A holder breaks the construction cycle.
        var sinkHolder = new java.util.concurrent.atomic.AtomicReference<PortalSso>();
        var bridgeState = new OidcBridgeApi.State(oidcClients, loginStateRepo, loginPrincipalRepo,
                loginMappingRepo, identityProviderRepo, idpRoleMappingRepo, roleRepo, oauthClientRepo, uow,
                tokenIssuer, new SessionCookie(cookiesSecure),
                (ctx, st, claims) -> sinkHolder.get().complete(ctx, st, claims), env.jwtIssuer(), Clock.systemUTC());
        OidcBridgeApi.register(routes, bridgeState);
        var portalSso = new PortalSso(new PortalSso.State(portalLoginFlowRepo, portalIdentityRepo, clientRepo, uow, grantStore,
                oidcClients, loginStateRepo, bridgeState, Clock.systemUTC()));
        sinkHolder.set(portalSso);
        portalSso.register(routes);

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

    /// RS256 over the current + previous public key, issuer == audience ==
    /// `FC_JWT_ISSUER`; shared by the authenticator and the provider's
    /// token reader so the two can never disagree about a token.
    private JwtVerifier buildVerifier() {
        var verificationKeys = signingKeys.rotation().verificationKeys().stream()
                .map(SigningKeys.PublicKeyEntry::publicKey)
                .toList();
        return new JwtVerifier(new JwtVerifier.Config(env.jwtIssuer(), JwtVerifier.RsaKeys.of(verificationKeys)));
    }

    /// The bearer/cookie authenticator over [#buildVerifier()].
    private Authenticator buildAuthenticator() {
        var verifier = buildVerifier();
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
                || p.equals("/auth/refresh")
                // The six token-gated 2FA routes (auth-identity §6.4, ruling I-Q20):
                // a pending or enrol MfaToken stands in for a session. Every other
                // /auth/2fa/* route and both change-password routes stay inside the
                // authenticator.
                || p.equals("/auth/2fa/verify") || p.equals("/auth/2fa/challenge/email")
                || p.equals("/auth/2fa/enroll/totp/begin") || p.equals("/auth/2fa/enroll/totp/confirm")
                || p.equals("/auth/2fa/enroll/email/begin") || p.equals("/auth/2fa/enroll/email/confirm")
                || p.startsWith("/auth/password-reset/")
                || p.equals("/auth/webauthn/authenticate/begin") || p.equals("/auth/webauthn/authenticate/complete")
                || p.startsWith("/portal/")
                || p.startsWith("/api/public/") || p.equals("/api/config/platform")
                || p.equals("/oauth/authorize")
                || p.equals("/api/dispatch/process")
                || p.equals("/api/dispatch/settled")
                || p.equals("/api/openapi.json") || p.equals("/api/openapi.yaml");
    }
}
