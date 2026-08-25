package io.flowcatalyst.router.standby;

import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.params.SetParams;

import java.time.Duration;
import java.util.List;

/// A [LockStore] over Redis (`docs/spec/router.md` §10.1).
///
/// The two compare-and-act operations are **Lua scripts, not read-then-write
/// pairs**. A refresh implemented as `GET` then `EXPIRE` can renew a lock
/// that changed hands between the two calls, which is exactly how two
/// instances end up both believing they are leader; a release implemented the
/// same way can delete a lock that has already moved on. Redis runs a script
/// atomically, so the comparison and the action cannot be separated.
public final class RedisLockStore implements LockStore {

    /// Extend only if the value is still ours.
    private static final String REFRESH_IF_MINE = """
            if redis.call('get', KEYS[1]) == ARGV[1] then
                return redis.call('expire', KEYS[1], ARGV[2])
            else
                return 0
            end
            """;

    /// Delete only if the value is still ours.
    private static final String RELEASE_IF_MINE = """
            if redis.call('get', KEYS[1]) == ARGV[1] then
                return redis.call('del', KEYS[1])
            else
                return 0
            end
            """;

    private final UnifiedJedis jedis;

    /// Takes a [UnifiedJedis] — `JedisPooled` in production — rather than the
    /// deprecated `JedisPool`. It pools internally, so there is no resource
    /// to borrow and return per call, and no chance of leaking one.
    public RedisLockStore(UnifiedJedis jedis) {
        this.jedis = jedis;
    }

    @Override
    public boolean acquire(String key, String value, Duration ttl) {
        // NX makes this the atomic "take it only if free"; without it two
        // instances starting together would both succeed.
        var result = jedis.set(key, value, SetParams.setParams().nx().ex(ttl.toSeconds()));
        return "OK".equals(result);
    }

    @Override
    public boolean refresh(String key, String value, Duration ttl) {
        var result = jedis.eval(REFRESH_IF_MINE, List.of(key), List.of(value, String.valueOf(ttl.toSeconds())));
        return result instanceof Long extended && extended == 1L;
    }

    @Override
    public void release(String key, String value) {
        jedis.eval(RELEASE_IF_MINE, List.of(key), List.of(value));
    }

    @Override
    public void ping() {
        jedis.ping();
    }
}
