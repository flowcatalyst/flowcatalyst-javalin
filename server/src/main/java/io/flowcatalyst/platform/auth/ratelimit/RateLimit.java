package io.flowcatalyst.platform.auth.ratelimit;

import io.flowcatalyst.server.EnvReader;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/// The distributed rate-limit vocabulary (`docs/spec/auth-core.md` §3.8,
/// §7, §11; Go `shared/ratelimit`): buckets, policies, the store contract
/// and the fail-open enforcement helper.
public final class RateLimit {

    private RateLimit() {
    }

    /// A limiter scope. The name appears verbatim in rows, so it is a storage
    /// contract. The three caller-less buckets keep their names by ruling
    /// C-Q18; wiring policies and callers for them is a backlog item.
    public enum Bucket {
        OAUTH_TOKEN_IP("oauth_token_ip"),
        OAUTH_TOKEN_CLIENT("oauth_token_client"),
        OAUTH_AUTHORIZE_IP("oauth_authorize_ip"),
        OAUTH_AUTHORIZE_CLIENT("oauth_authorize_client"),
        OAUTH_INTROSPECT_IP("oauth_introspect_ip"),
        OAUTH_REVOKE_IP("oauth_revoke_ip"),
        PASSWORD_RESET_IP("password_reset_ip"),
        PASSWORD_RESET_EMAIL("password_reset_email"),
        CHECK_DOMAIN_IP("check_domain_ip"),
        PORTAL_LOGIN("portal_login");

        private final String key;

        Bucket(String key) {
            this.key = key;
        }

        /// The stored name.
        public String key() {
            return key;
        }
    }

    /// "At most `limit` events in any `window`" for one (bucket, key).
    public record Policy(Duration window, int limit) {
        public Policy {
            Objects.requireNonNull(window, "window");
            if (window.isZero() || window.isNegative()) throw new IllegalArgumentException("window must be positive");
            if (limit < 0) throw new IllegalArgumentException("limit must not be negative");
        }
    }

    /// The per-bucket defaults, deliberately generous — the long-tail
    /// cluster-wide ceiling (`FC_RL_*`).
    public record Policies(Policy oauthTokenIp, Policy oauthTokenClient, Policy oauthAuthorizeIp,
                           Policy oauthAuthorizeClient, Policy passwordResetIp, Policy passwordResetEmail,
                           Policy portalLogin) {

        public static Policies fromEnv(EnvReader e) {
            return new Policies(
                    new Policy(Duration.ofMinutes(1), e.integer("FC_RL_OAUTH_TOKEN_IP_PER_MIN", 600)),
                    new Policy(Duration.ofMinutes(1), e.integer("FC_RL_OAUTH_TOKEN_CLIENT_PER_MIN", 300)),
                    new Policy(Duration.ofMinutes(1), e.integer("FC_RL_OAUTH_AUTHORIZE_IP_PER_MIN", 600)),
                    new Policy(Duration.ofMinutes(1), e.integer("FC_RL_OAUTH_AUTHORIZE_CLIENT_PER_MIN", 300)),
                    new Policy(Duration.ofHours(1), e.integer("FC_RL_PASSWORD_RESET_IP_PER_HOUR", 20)),
                    new Policy(Duration.ofHours(1), e.integer("FC_RL_PASSWORD_RESET_EMAIL_PER_HOUR", 5)),
                    new Policy(Duration.ofMinutes(15), e.integer("FC_RL_PORTAL_LOGIN_PER_15MIN", 10)));
        }

        /// The longest window across every policy — how far back the prune
        /// keeps history. Every policy is listed: a window missing here is one
        /// the prune could delete rows still inside, silently weakening it.
        public Duration maxWindow() {
            Duration max = Duration.ofHours(1);
            for (Policy p : List.of(oauthTokenIp, oauthTokenClient, oauthAuthorizeIp, oauthAuthorizeClient,
                    passwordResetIp, passwordResetEmail, portalLogin)) {
                if (p.window().compareTo(max) > 0) {
                    max = p.window();
                }
            }
            return max;
        }
    }

    /// The outcome of one check. `retryAfterSecs` is a worst-case estimate
    /// (≤ window) of when room frees up.
    public record Decision(boolean allowed, long retryAfterSecs) {
        public static final Decision ALLOWED = new Decision(true, 0);

        public static Decision denied(long retryAfterSecs) {
            return new Decision(false, Math.max(1, retryAfterSecs));
        }
    }

    /// The backend contract. `checkAndRecord` is one atomic call: counting
    /// and recording split across two round-trips leaves a bypass race
    /// under burst load.
    public interface Store {
        Decision checkAndRecord(Bucket bucket, String key, Policy policy);

        /// Drops events older than `olderThan`; best-effort housekeeping.
        int prune(Duration olderThan);
    }

    /// Always allows — `FC_RATE_LIMIT_DISABLE=1`.
    public static final class NoopStore implements Store {
        @Override
        public Decision checkAndRecord(Bucket bucket, String key, Policy policy) {
            return Decision.ALLOWED;
        }

        @Override
        public int prune(Duration olderThan) {
            return 0;
        }
    }

    /// Over the limit: the suggested wait.
    public record Rejection(long retryAfterSecs) {
    }

    /// Runs the check for one (bucket, key). **Fails open** on a backend
    /// error or a null store (ruling C-Q23: a degraded limiter must never
    /// take auth down — unlike the login backoff, which fails closed).
    public static Rejection enforce(Store store, Bucket bucket, String key, Policy policy) {
        if (store == null) {
            return null;
        }
        Decision d;
        try {
            d = store.checkAndRecord(bucket, key, policy);
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(RateLimit.class)
                    .warn("distributed rate-limit backend error; failing open bucket={}", bucket.key(), e);
            return null;
        }
        return d.allowed() ? null : new Rejection(d.retryAfterSecs());
    }
}
