package io.flowcatalyst.router.policy;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/// One [CircuitBreaker] per delivery endpoint, created on first use.
///
/// **Keyed by origin + path** — `scheme://host[:port]/path` — with the query
/// string and fragment stripped (**R-12**, ruled 2026-09-02). A query string
/// is per-message data, not a distinct downstream: keying on it would
/// otherwise fragment the failure signal so a genuinely dead endpoint never
/// trips, since each differently-parameterised message trips its own,
/// separately-counted breaker instead of the one shared breaker for the
/// endpoint they all hit.
///
/// Unbounded growth is the risk that follows from an unbounded key space, so
/// [#evictIdle] retires breakers nothing has touched. A retired breaker is
/// re-created closed on its next call, which is correct: an endpoint nobody
/// has spoken to for an hour has no recent evidence either way.
public final class BreakerRegistry {

    private final Map<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();
    private final CircuitBreaker.Config config;
    private final Clock clock;

    public BreakerRegistry(CircuitBreaker.Config config, Clock clock) {
        this.config = config;
        this.clock = clock;
    }

    /// The breaker for `targetUrl`, created closed if this is the first call.
    public CircuitBreaker get(String targetUrl) {
        return breakers.computeIfAbsent(keyFor(targetUrl), ignored -> new CircuitBreaker(config, clock));
    }

    /// The R-12 breaker key for `targetUrl`: `scheme://host[:port]/path`,
    /// query string and fragment stripped. A URL that will not parse keys by
    /// its raw string instead — an unparseable target still needs a stable
    /// key, and it is exactly the case [HttpMediator] handles elsewhere
    /// without ever reaching the breaker at all, so no live target URL
    /// actually takes this branch.
    public static String keyFor(String targetUrl) {
        try {
            var uri = new URI(targetUrl);
            var scheme = uri.getScheme();
            var host = uri.getHost();
            if (scheme == null || host == null) {
                return targetUrl;
            }
            var port = uri.getPort();
            var path = uri.getRawPath();
            return scheme + "://" + host + (port == -1 ? "" : ":" + port) + (path == null ? "" : path);
        } catch (URISyntaxException e) {
            return targetUrl;
        }
    }

    /// Retires breakers idle for longer than `maxIdle`. Returns how many went.
    ///
    /// The idleness test runs inside the map's own atomic update, so a
    /// breaker created concurrently is never dropped. A breaker that becomes
    /// active in the same instant may still be retired — its state resets to
    /// closed, which loses recent failure history for that endpoint. Go
    /// narrows the same race with a re-check and does not close it either;
    /// the cost is bounded and self-correcting, since the next few failures
    /// re-open it.
    public int evictIdle(Duration maxIdle) {
        if (maxIdle.isNegative() || maxIdle.isZero()) {
            return 0;
        }
        var cutoff = clock.instant().minus(maxIdle);
        int before = breakers.size();
        breakers.keySet().forEach(url ->
                breakers.computeIfPresent(url, (ignored, breaker) ->
                        breaker.lastActivity().isBefore(cutoff) ? null : breaker));
        return before - breakers.size();
    }

    /// Clears one breaker's state. False when nothing is registered for the
    /// URL — so an operator typing a wrong URL is told, rather than silently
    /// succeeding.
    public boolean reset(String targetUrl) {
        var breaker = breakers.get(keyFor(targetUrl));
        if (breaker == null) {
            return false;
        }
        breaker.reset();
        return true;
    }

    /// Clears every breaker, returning how many. Entries are kept, not
    /// removed: their stats stay visible on the monitoring surface.
    public int resetAll() {
        breakers.values().forEach(CircuitBreaker::reset);
        return breakers.size();
    }

    /// Stats for every registered endpoint, for the monitoring API.
    public Map<String, CircuitBreaker.Stats> snapshot() {
        return breakers.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> e.getValue().stats()));
    }

    public int size() {
        return breakers.size();
    }
}
