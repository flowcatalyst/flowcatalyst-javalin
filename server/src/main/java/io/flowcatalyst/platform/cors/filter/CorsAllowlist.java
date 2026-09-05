package io.flowcatalyst.platform.cors.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/// The CORS filter's in-process cache of `tnt_cors_allowed_origins`
/// (`docs/spec/cors.md` §9): a bounded-TTL read of an [OriginSource] — in
/// production `CorsOriginRepository::allowedOrigins` — plus same-node
/// invalidation from `CorsOriginApi`'s `onChange` (add/delete). The filter
/// asks [#matches(String)] on every request; this class is the only thing
/// that ever calls the source for the answer.
///
/// Loading rules:
///   - a failure while building the very first snapshot (construction time)
///     **fails closed**: the allowlist starts empty, so every origin is
///     denied until a reload succeeds — never an exception out of the
///     constructor, and never "allow everything" as the failure mode;
///   - a failure while reloading a stale snapshot **keeps the previous
///     snapshot** — a transient DB blip does not make a previously-allowed
///     origin start failing;
///   - a snapshot older than `ttl` is reloaded on the next [#matches] call
///     (the TTL is the fallback refresh for the multi-node case; same-node
///     changes are picked up immediately via [#invalidate]).
///
/// Matching (spec §9, open question 3): an allowlist entry with no `*` is
/// matched by exact string equality; an entry containing `*` in its host
/// (`Origin` §4's wildcard hosts) is compiled once into a [Pattern] where
/// each `*` becomes one-or-more DNS labels (`[a-zA-Z0-9-]+(\.[a-zA-Z0-9-]+)*`
/// — at least one label, so a wildcard entry never matches its own apex) and
/// every other character is matched literally.
public final class CorsAllowlist {

    private static final Logger LOG = LoggerFactory.getLogger(CorsAllowlist.class);

    /// `scheme://host[:port]`, host possibly containing `*` — the same shape
    /// [io.flowcatalyst.platform.cors.Origin] accepts, split into the parts a
    /// wildcard matcher needs.
    private static final Pattern ENTRY = Pattern.compile("^(https?://)([a-zA-Z0-9*.-]+)(?::(\\d+))?$");

    /// One or more DNS labels — what a single `*` expands to.
    private static final String WILDCARD_LABEL = "[a-zA-Z0-9-]+(?:\\.[a-zA-Z0-9-]+)*";

    /// The one read this class needs from `CorsOriginRepository` — narrowed
    /// to a functional interface so a pure test can stub it without a
    /// database. `CorsOriginRepository::allowedOrigins` satisfies it as-is.
    @FunctionalInterface
    public interface OriginSource {
        List<String> allowedOrigins();
    }

    private final OriginSource source;
    private final Duration ttl;
    private final Clock clock;
    private final AtomicReference<Snapshot> snapshot;

    public CorsAllowlist(OriginSource source, Duration ttl, Clock clock) {
        this.source = Objects.requireNonNull(source, "source");
        this.ttl = Objects.requireNonNull(ttl, "ttl");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.snapshot = new AtomicReference<>(new Snapshot(Instant.now(clock), loadOrEmpty()));
    }

    /// `true` iff `origin` (the request's `Origin` header, verbatim) matches
    /// some allowlist entry. `null` never matches.
    public boolean matches(String origin) {
        if (origin == null) return false;
        return current().matchers().stream().anyMatch(m -> m.matches(origin));
    }

    /// Forces the next [#matches] call to reload regardless of the TTL
    /// (spec §9: the `origin-added` / `origin-deleted` signal). Reload
    /// failure still keeps whatever snapshot is current at that point.
    public void invalidate() {
        var s = snapshot.get();
        snapshot.compareAndSet(s, new Snapshot(Instant.MIN, s.matchers()));
    }

    private Snapshot current() {
        var s = snapshot.get();
        if (Instant.now(clock).isBefore(s.loadedAt().plus(ttl))) {
            return s;
        }
        try {
            var fresh = new Snapshot(Instant.now(clock), compile(source.allowedOrigins()));
            snapshot.set(fresh);
            return fresh;
        } catch (RuntimeException e) {
            LOG.warn("CORS allowlist reload failed; keeping the previous snapshot", e);
            return s;
        }
    }

    private List<Matcher> loadOrEmpty() {
        try {
            return compile(source.allowedOrigins());
        } catch (RuntimeException e) {
            LOG.warn("initial CORS allowlist load failed; starting closed (no origins allowed)", e);
            return List.of();
        }
    }

    private static List<Matcher> compile(List<String> origins) {
        var out = new ArrayList<Matcher>(origins.size());
        for (var origin : origins) {
            out.add(origin.indexOf('*') >= 0 ? wildcard(origin) : new Exact(origin));
        }
        return List.copyOf(out);
    }

    /// Builds the [Pattern] for a wildcard entry: scheme and port are quoted
    /// literals, each `*` in the host becomes [#WILDCARD_LABEL], everything
    /// else in the host is a quoted literal segment.
    private static Matcher wildcard(String origin) {
        var m = ENTRY.matcher(origin);
        if (!m.matches()) {
            // Unreachable via Origin.parse (which this entry already passed to be
            // stored), but a matcher must not throw on a row it cannot parse.
            LOG.warn("CORS allowlist entry does not match the expected shape; treating as exact: {}", origin);
            return new Exact(origin);
        }
        var sb = new StringBuilder("^").append(Pattern.quote(m.group(1)));
        var parts = m.group(2).split("\\*", -1);
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append(WILDCARD_LABEL);
            if (!parts[i].isEmpty()) sb.append(Pattern.quote(parts[i]));
        }
        if (m.group(3) != null) sb.append(':').append(Pattern.quote(m.group(3)));
        sb.append('$');
        return new Wildcard(Pattern.compile(sb.toString()));
    }

    private record Snapshot(Instant loadedAt, List<Matcher> matchers) {
    }

    private sealed interface Matcher permits Exact, Wildcard {
        boolean matches(String origin);
    }

    private record Exact(String value) implements Matcher {
        @Override
        public boolean matches(String origin) {
            return value.equals(origin);
        }
    }

    private record Wildcard(Pattern pattern) implements Matcher {
        @Override
        public boolean matches(String origin) {
            return pattern.matcher(origin).matches();
        }
    }
}
