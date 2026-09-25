package io.flowcatalyst.router.api.auth;

import io.flowcatalyst.http.Routes;
import io.flowcatalyst.platform.shared.auth.jwks.BearerAuthenticator;
import io.flowcatalyst.platform.shared.auth.jwks.JwksKeySource;
import io.flowcatalyst.router.api.dashboard.DashboardSignIn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/// Chooses the router API's guard (`docs/spec/router-api-auth.md` rules 1 and 5):
///
/// - **dev mode** keeps §9.7 exactly, via [BasicAuthFilter]: Basic when a user is
///   configured, open otherwise;
/// - **everywhere else** a platform bearer token is required, via
///   [PlatformTokenFilter]. `AUTH_MODE`, `FC_ROUTER_AUTH_USER` and
///   `FC_ROUTER_AUTH_PASS` are ignored, with a WARN naming each one that is
///   set. The router still starts, so a deployment that still carries
///   `AUTH_MODE=NONE` keeps moving messages while its API closes.
public final class RouterAuth {

    private static final Logger LOG = LoggerFactory.getLogger(RouterAuth.class);

    private RouterAuth() {
    }

    /// What the choice depends on, read from the environment by the caller.
    ///
    /// @param devMode     `FLOWCATALYST_DEV_MODE`
    /// @param authMode    raw `AUTH_MODE`
    /// @param user        `FC_ROUTER_AUTH_USER` (as resolved by `Env`)
    /// @param password    `FC_ROUTER_AUTH_PASS`
    /// @param prefix      the router's mount prefix
    /// @param platformUrl where to verify tokens: `FC_ROUTER_PLATFORM_URL`, or the
    ///                    in-process platform's loopback address; blank when neither
    /// @param dashboardClientId `FC_ROUTER_DASHBOARD_CLIENT_ID`: the public OAuth client the
    ///                    dashboard signs in through (rule 6); blank leaves sign-in off
    public record Settings(boolean devMode, String authMode, String user, String password, String prefix,
                           String platformUrl, String dashboardClientId) {
    }

    /// The guard that was installed, for the caller's log and for tests.
    public sealed interface Installed permits Installed.Basic, Installed.PlatformTokens {
        /// Dev mode: §9.7, `enabled` false when open.
        record Basic(boolean enabled) implements Installed {
        }

        /// Platform bearer tokens; `verifying` false when there is no platform
        /// URL, so every protected route answers 401.
        record PlatformTokens(boolean verifying, List<String> ignoredSettings) implements Installed {
        }
    }

    /// Installs the guard and the dashboard's sign-in helpers.
    public static Installed install(Routes routes, Settings s) {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        if (s.devMode()) {
            var basic = new BasicAuthFilter(s.authMode(), s.user(), s.password(), s.prefix());
            BasicAuthFilter.register(routes, basic);
            // Dev mode signs in with Basic (or not at all): the PKCE helpers answer "off".
            DashboardSignIn.register(routes, s.prefix(), new DashboardSignIn(Optional.empty(), "", "", http));
            return new Installed.Basic(basic.enabled());
        }
        List<String> ignored = new ArrayList<>();
        if (notBlank(s.authMode())) ignored.add("AUTH_MODE");
        if (notBlank(s.user())) ignored.add("FC_ROUTER_AUTH_USER");
        if (notBlank(s.password())) ignored.add("FC_ROUTER_AUTH_PASS");
        for (String name : ignored) {
            LOG.atWarn().setMessage("router API auth: this setting applies in dev mode only and is ignored; "
                            + "the router API requires a platform bearer token")
                    .addKeyValue("setting", name)
                    .log();
        }
        Optional<JwksKeySource> keys = Optional.empty();
        if (notBlank(s.platformUrl())) {
            keys = Optional.of(new JwksKeySource(http, s.platformUrl().strip()));
        } else {
            LOG.atWarn().setMessage("router API auth: no platform to verify tokens against "
                            + "(FC_ROUTER_PLATFORM_URL unset and no platform in this process); "
                            + "every router API call except health, metrics and the dashboard page will answer 401")
                    .log();
        }
        Optional<BearerAuthenticator> authenticator = keys.map(BearerAuthenticator::new);
        PlatformTokenFilter.register(routes, new PlatformTokenFilter(authenticator, s.prefix()));
        // One discovery shared with the filter: the dashboard's authorize URL is in the same
        // document the issuer comes from.
        DashboardSignIn.register(routes, s.prefix(), new DashboardSignIn(keys, s.platformUrl(), s.dashboardClientId(), http));
        return new Installed.PlatformTokens(authenticator.isPresent(), List.copyOf(ignored));
    }

    private static boolean notBlank(String v) {
        return v != null && !v.isBlank();
    }
}
