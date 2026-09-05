package io.flowcatalyst.platform.auth.ratelimit;

import io.flowcatalyst.server.EnvReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;

/// Backend selection (`docs/spec/auth-core.md` §11 "Rate-limit Build";
/// Go `ratelimit.Build`): `FC_RATE_LIMIT_DISABLE=1` ⇒ the no-op store;
/// else Postgres. The choice is logged at startup.
///
/// Go additionally uses Redis when `FC_REDIS_URL` is set and reachable. The
/// Java port does not yet: a set `FC_REDIS_URL` is logged and the Postgres
/// store is used — the same behaviour Go has when Redis is unreachable.
/// The Redis store is a backlog item (jedis is already a dependency).
public final class RateLimitStores {

    private static final Logger LOG = LoggerFactory.getLogger(RateLimitStores.class);

    private RateLimitStores() {
    }

    public static RateLimit.Store build(EnvReader env, DataSource pool) {
        if ("1".equals(env.get("FC_RATE_LIMIT_DISABLE"))) {
            LOG.info("distributed rate-limit store: DISABLED (FC_RATE_LIMIT_DISABLE=1)");
            return new RateLimit.NoopStore();
        }
        String redis = env.get("FC_REDIS_URL");
        if (redis != null && !redis.isBlank()) {
            LOG.warn("FC_REDIS_URL is set but the Java port has no Redis rate-limit store yet; using the Postgres store");
        } else {
            LOG.info("FC_REDIS_URL not set; using Postgres rate-limit store");
        }
        return new PostgresRateLimitStore(pool);
    }
}
