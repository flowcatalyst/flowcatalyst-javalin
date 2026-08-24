package io.flowcatalyst.router.policy;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/// One [CircuitBreaker] per delivery endpoint, created on first use.
///
/// **Keyed by the full target URL**, query string included, so
/// `…/hook?tenant=a` and `…/hook?tenant=b` trip independently
/// (`docs/spec/router.md` §13 Q12, unruled — behaviour kept). That is right
/// when the query selects a distinct downstream and wrong when it is
/// incidental, which is what the question is about; keying by origin instead
/// would be a behaviour change, not a refactor.
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
        return breakers.computeIfAbsent(targetUrl, ignored -> new CircuitBreaker(config, clock));
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
        var breaker = breakers.get(targetUrl);
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
