package io.flowcatalyst.router.manager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Collection;
import java.util.concurrent.StructuredTaskScope;

/// Runs independent shutdown and startup steps at the same time, bounded.
///
/// The point is **isolation as much as speed**. Closing eight consumers in
/// turn means one unreachable broker spends the whole shutdown budget while
/// seven healthy queues wait behind it. Run together, a hang costs the
/// timeout once rather than once per item, and everything that *can* finish
/// does.
///
/// Uses [StructuredTaskScope] with an explicit completion policy
/// (CONVENTIONS §8), which gives three things a bare executor does not: the
/// forked tasks are guaranteed not to outlive the call, the deadline belongs
/// to the scope rather than being re-derived per await, and cancellation
/// propagates into the tasks instead of merely abandoning them.
final class Concurrently {

    private static final Logger log = LoggerFactory.getLogger(Concurrently.class);

    private Concurrently() {
    }

    /// Runs `action` for every item concurrently and waits up to `timeout`.
    ///
    /// The policy is **await-all**: a task that fails does not cancel its
    /// siblings, because during shutdown there is nothing useful to do with
    /// the failure and abandoning the remaining steps would leak exactly what
    /// the shutdown exists to release. Failures are logged individually.
    ///
    /// @return whether every task finished inside the timeout
    static <T> boolean forEach(Collection<T> items, java.util.function.Consumer<T> action,
                               Duration timeout, String what) {
        if (items.isEmpty()) {
            return true;
        }
        try (var scope = StructuredTaskScope.open(
                StructuredTaskScope.Joiner.<Void>awaitAll(),
                config -> config.withTimeout(timeout))) {
            for (var item : items) {
                scope.fork(() -> {
                    try {
                        action.accept(item);
                    } catch (RuntimeException e) {
                        log.warn("{} failed for {}", what, item, e);
                    }
                    return null;
                });
            }
            scope.join();
            return true;
        } catch (StructuredTaskScope.TimeoutException e) {
            // The scope cancels whatever is still running as it closes, so
            // nothing is left behind — we simply stop waiting for it.
            log.warn("{} did not finish within {}; continuing without it", what, timeout);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
