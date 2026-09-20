package io.flowcatalyst.fnhost.context;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/// The current invocation's deadline (spec `function-context.md` §2:
/// "timeout = `min(HttpCall.timeout, time left before the invocation
/// deadline)`"), reached by [AllowlistHttpCaller#send] without making it
/// per-invocation state on the shared [HostFunctionContext] — a
/// [ScopedValue], bound around the worker thread's call into the function
/// ([io.flowcatalyst.fnhost.http.InvocationRunner]), never a mutable field a
/// second concurrent call on the same loaded version could race on
/// (`CONVENTIONS.md` §5: "`ScopedValue` for request context").
public final class InvocationDeadline {

    /// Bound for the duration of one invocation's [Function#handle] call.
    /// Unbound outside an invocation (e.g. during `Function#init`) — [#remaining]
    /// then falls back to [#NO_DEADLINE_BUDGET].
    public static final ScopedValue<Instant> CURRENT = ScopedValue.newInstance();

    /// What [#remaining] returns when no deadline is bound — generous, since
    /// there is no invocation timeout to race against (`init()`, or a test
    /// calling [io.flowcatalyst.function.HttpCaller] directly).
    public static final Duration NO_DEADLINE_BUDGET = Duration.ofSeconds(30);

    private InvocationDeadline() {
    }

    /// Time left before the bound deadline, floored at zero — never negative,
    /// so a caller that computes `min(callTimeout, remaining())` never gets a
    /// negative/zero-crossing duration out of a deadline that has already
    /// passed (an expired deadline reads as "no time left", not "no limit").
    public static Duration remaining(Clock clock) {
        if (!CURRENT.isBound()) {
            return NO_DEADLINE_BUDGET;
        }
        Duration left = Duration.between(clock.instant(), CURRENT.get());
        return left.isNegative() ? Duration.ZERO : left;
    }
}
