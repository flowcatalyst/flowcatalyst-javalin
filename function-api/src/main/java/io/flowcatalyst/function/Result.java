package io.flowcatalyst.function;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/// The outcome of one [Function#handle]: a sealed taxonomy so a function
/// author (and the host's dispatcher) switches exhaustively rather than
/// branching on a status code. Built only through the static factories
/// below, which enforce the invariants each case depends on.
public sealed interface Result permits Ack, Retry, Fail, HttpResponse {

    /// The invocation succeeded; nothing further to say.
    static Result ack() {
        return Ack.INSTANCE;
    }

    /// The invocation should be redelivered after `after`.
    ///
    /// @throws IllegalArgumentException if `after` is `null` or negative
    static Result retry(Duration after) {
        return new Retry(after);
    }

    /// The invocation failed and should not be retried by the host on this
    /// account — `reason` becomes the audit/metrics detail.
    ///
    /// @throws IllegalArgumentException if `reason` is blank or `null`
    static Result fail(String reason) {
        return new Fail(reason);
    }

    /// The invocation was an [HttpInvocation] answered directly.
    ///
    /// @throws IllegalArgumentException if `status` is outside 100-599
    static Result http(int status, Map<String, List<String>> headers, byte[] body) {
        return new HttpResponse(status, headers, body);
    }
}
