package io.flowcatalyst.platform.auth.ratelimit;

import io.flowcatalyst.server.EnvReader;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/// A per-instance, in-memory keyed token bucket that sits in front of the
/// distributed [RateLimit.Store] as defence in depth (Go
/// `ratelimit.Governor`): a flood from one IP or client id is shed locally,
/// before the network round-trip. Not cluster-wide — each replica keeps its
/// own buckets — so it complements the store, never replaces it.
///
/// Mechanics: a bucket holds up to `burst` tokens and refills at
/// `perMinute / 60` per second; a check takes one token when available,
/// else reports whole seconds (≥ 1) until one is. Idle buckets are pruned
/// every five minutes after ten idle minutes.
public final class Governor {

    /// @param perMinute sustained rate
    /// @param burst     instantaneous allowance
    public record Config(int perMinute, int burst) {
        public Config {
            perMinute = Math.max(perMinute, 1);
            burst = Math.max(burst, 1);
        }

        /// `FC_OAUTH_TOKEN_IP_RATE_PER_MIN` (120) / `FC_OAUTH_TOKEN_IP_BURST` (60).
        public static Config oauthTokenIp(EnvReader e) {
            return new Config(e.integer("FC_OAUTH_TOKEN_IP_RATE_PER_MIN", 120), e.integer("FC_OAUTH_TOKEN_IP_BURST", 60));
        }

        /// `FC_OAUTH_TOKEN_CLIENT_RATE_PER_MIN` (60) / `FC_OAUTH_TOKEN_CLIENT_BURST` (30).
        public static Config oauthTokenClient(EnvReader e) {
            return new Config(e.integer("FC_OAUTH_TOKEN_CLIENT_RATE_PER_MIN", 60), e.integer("FC_OAUTH_TOKEN_CLIENT_BURST", 30));
        }

        /// `FC_OIDC_RATE_PER_MIN` (60) / `FC_OIDC_BURST` (30) — the bridge routes.
        public static Config oidcBridge(EnvReader e) {
            return new Config(e.integer("FC_OIDC_RATE_PER_MIN", 60), e.integer("FC_OIDC_BURST", 30));
        }
    }

    /// @param ok             admitted now
    /// @param retryAfterSecs whole seconds to wait when not admitted (≥ 1)
    public record Check(boolean ok, long retryAfterSecs) {
    }

    static final Duration PRUNE_INTERVAL = Duration.ofMinutes(5);
    static final Duration IDLE_TTL = Duration.ofMinutes(10);

    private static final class Entry {
        double tokens;
        Instant last;
        Instant lastSeen;

        Entry(double tokens, Instant at) {
            this.tokens = tokens;
            this.last = at;
            this.lastSeen = at;
        }
    }

    private final double refillPerSecond;
    private final int burst;
    private final Clock clock;
    private final Map<String, Entry> buckets = new ConcurrentHashMap<>();
    private volatile Instant lastPrune;

    public Governor(Config config) {
        this(config, Clock.systemUTC());
    }

    public Governor(Config config, Clock clock) {
        Objects.requireNonNull(config, "config");
        this.refillPerSecond = config.perMinute() / 60.0;
        this.burst = config.burst();
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /// Consumes one token for `key`.
    public Check check(String key) {
        Instant now = clock.instant();
        maybePrune(now);
        Entry e = buckets.computeIfAbsent(key, _ -> new Entry(burst, now));
        synchronized (e) {
            double elapsed = Math.max(Duration.between(e.last, now).toNanos() / 1_000_000_000.0, 0);
            e.tokens = Math.min(burst, e.tokens + elapsed * refillPerSecond);
            e.last = now;
            e.lastSeen = now;
            if (e.tokens >= 1.0) {
                e.tokens -= 1.0;
                return new Check(true, 0);
            }
            double deficit = 1.0 - e.tokens;
            long secs = (long) Math.ceil(deficit / refillPerSecond);
            return new Check(false, Math.max(1, secs));
        }
    }

    /// Drops buckets idle longer than `idleFor`; returns how many.
    public int prune(Duration idleFor) {
        Instant cutoff = clock.instant().minus(idleFor);
        int[] n = {0};
        buckets.entrySet().removeIf(en -> {
            synchronized (en.getValue()) {
                if (en.getValue().lastSeen.isBefore(cutoff)) {
                    n[0]++;
                    return true;
                }
                return false;
            }
        });
        return n[0];
    }

    /// How many keys are tracked (tests).
    int size() {
        return buckets.size();
    }

    private void maybePrune(Instant now) {
        Instant last = lastPrune;
        if (last != null && Duration.between(last, now).compareTo(PRUNE_INTERVAL) < 0) {
            return;
        }
        lastPrune = now;
        prune(IDLE_TTL);
    }
}
