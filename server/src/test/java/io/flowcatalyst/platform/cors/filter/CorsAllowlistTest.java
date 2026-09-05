package io.flowcatalyst.platform.cors.filter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/// Pure unit tests for [CorsAllowlist] against a stub [CorsAllowlist.OriginSource]
/// and a mutable [Clock] — no database, no filter, no HTTP.
class CorsAllowlistTest {

    /// A clock whose `instant()` is whatever was last set — lets a test move
    /// time forward without `Thread.sleep`, which a TTL test would otherwise need.
    private static final class MutableClock extends Clock {
        private final AtomicLong nanos = new AtomicLong();

        void set(Instant instant) {
            nanos.set(instant.toEpochMilli() * 1_000_000L);
        }

        void advance(Duration by) {
            nanos.addAndGet(by.toNanos());
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(nanos.get() / 1_000_000L);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            throw new UnsupportedOperationException();
        }
    }

    /// A source whose answer and call count a test controls directly.
    private static final class StubSource implements CorsAllowlist.OriginSource {
        private volatile List<String> origins;
        private volatile RuntimeException failure;
        private final AtomicInteger calls = new AtomicInteger();

        StubSource(List<String> origins) {
            this.origins = origins;
        }

        void set(List<String> origins) {
            this.origins = origins;
            this.failure = null;
        }

        void fail(RuntimeException e) {
            this.failure = e;
        }

        int callCount() {
            return calls.get();
        }

        @Override
        public List<String> allowedOrigins() {
            calls.incrementAndGet();
            if (failure != null) throw failure;
            return origins;
        }
    }

    private static MutableClock clockAt(String isoInstant) {
        var c = new MutableClock();
        c.set(Instant.parse(isoInstant));
        return c;
    }

    // ── Exact matching ───────────────────────────────────────────────────────

    @Test
    void exactEntryMatchesOnlyTheIdenticalOrigin() {
        var allowlist = new CorsAllowlist(new StubSource(List.of("https://app.example.com")),
                Duration.ofMinutes(5), clockAt("2026-01-01T00:00:00Z"));

        assertThat(allowlist.matches("https://app.example.com")).isTrue();
        assertThat(allowlist.matches("https://other.example.com")).isFalse();
        assertThat(allowlist.matches("http://app.example.com")).as("scheme must match too").isFalse();
        assertThat(allowlist.matches(null)).isFalse();
    }

    // ── Wildcard matching — the pinned table (matcher rules) ───────────────────

    @ParameterizedTest(name = "[{0}] entry={1} origin={2} expected={3}")
    @CsvSource({
            "subdomain matches,          https://*.example.com,        https://sub.example.com,       true",
            "multi-label subdomain matches, https://*.example.com,     https://a.b.example.com,       true",
            "apex does NOT match (zero labels), https://*.example.com, https://example.com,           false",
            "different suffix does not match, https://*.example.com,   https://sub.example.org,       false",
            "trailing wildcard matches,  https://example.*,            https://example.com,           true",
            "trailing wildcard multi-label, https://example.*,         https://example.co.uk,         true",
            "bare star matches any host, https://*,                    https://anything.test,         true",
            "mid-host wildcard matches,  https://ex*ample.com,         https://exFOOample.com,        true",
            "mid-host wildcard rejects mismatched literal, https://ex*ample.com, https://exFOOampleX.com, false",
            "mid-host wildcard requires at least one char (not a zero-width glob), https://ex*ample.com, https://example.com, false",
            "port is a literal not wildcarded, https://*.example.com:8443, https://sub.example.com:8443, true",
            "wrong port is rejected,     https://*.example.com:8443,   https://sub.example.com:9999,  false",
    })
    void wildcardMatcherRules(String rule, String entry, String origin, boolean expected) {
        var allowlist = new CorsAllowlist(new StubSource(List.of(entry)), Duration.ofMinutes(5), clockAt("2026-01-01T00:00:00Z"));
        assertThat(allowlist.matches(origin)).as(rule).isEqualTo(expected);
    }

    // ── Construction-time failure: fail closed and empty ───────────────────────

    @Test
    void constructionFailureStartsClosedRatherThanThrowing() {
        var failing = new StubSource(List.of());
        failing.fail(new RuntimeException("db is down"));

        // Must not throw out of the constructor.
        var allowlist = new CorsAllowlist(failing, Duration.ofMinutes(5), clockAt("2026-01-01T00:00:00Z"));

        assertThat(allowlist.matches("https://app.example.com")).as("closed: nothing is allowed").isFalse();
    }

    // ── Reload-failure: keep the previous (working) snapshot ───────────────────

    @Test
    void reloadFailureKeepsThePreviousWorkingSnapshotRatherThanGoingClosed() {
        var source = new StubSource(List.of("https://app.example.com"));
        var clock = clockAt("2026-01-01T00:00:00Z");
        var allowlist = new CorsAllowlist(source, Duration.ofSeconds(10), clock);

        assertThat(allowlist.matches("https://app.example.com")).as("loaded from the initial snapshot").isTrue();

        // Advance past the TTL and make the underlying read start failing.
        clock.advance(Duration.ofSeconds(11));
        source.fail(new RuntimeException("transient db blip"));

        assertThat(allowlist.matches("https://app.example.com"))
                .as("a reload failure must not make a previously-allowed origin start failing")
                .isTrue();
    }

    // ── TTL: reload only once stale ──────────────────────────────────────────

    @Test
    void reloadsOnlyAfterTheSnapshotIsOlderThanTheTtl() {
        var source = new StubSource(List.of("https://app.example.com"));
        var clock = clockAt("2026-01-01T00:00:00Z");
        var allowlist = new CorsAllowlist(source, Duration.ofSeconds(10), clock);
        assertThat(source.callCount()).as("one read at construction").isEqualTo(1);

        // Still fresh: no further reads, and a since-added origin is not yet visible.
        clock.advance(Duration.ofSeconds(5));
        source.set(List.of("https://app.example.com", "https://new.example.com"));
        assertThat(allowlist.matches("https://new.example.com")).as("cache not yet stale").isFalse();
        assertThat(source.callCount()).as("no reload while fresh").isEqualTo(1);

        // Past the TTL: the next call must reload and pick up the change.
        clock.advance(Duration.ofSeconds(6));
        assertThat(allowlist.matches("https://new.example.com")).as("reloaded after the TTL elapsed").isTrue();
        assertThat(source.callCount()).isEqualTo(2);
    }

    // ── invalidate(): forces an immediate reload regardless of the TTL ─────────

    @Test
    void invalidateForcesTheNextCallToReloadEvenWellInsideTheTtl() {
        var source = new StubSource(List.of("https://app.example.com"));
        var clock = clockAt("2026-01-01T00:00:00Z");
        var allowlist = new CorsAllowlist(source, Duration.ofHours(1), clock);

        source.set(List.of("https://app.example.com", "https://new.example.com"));
        assertThat(allowlist.matches("https://new.example.com")).as("still cached, well within the hour TTL").isFalse();

        allowlist.invalidate();

        assertThat(allowlist.matches("https://new.example.com")).as("invalidate reloads immediately").isTrue();
        assertThat(source.callCount()).isEqualTo(2);
    }
}
