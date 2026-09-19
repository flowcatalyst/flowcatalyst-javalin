package io.flowcatalyst.function;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/// The outcome of one [Function#handle]: the HTTP response the host sends
/// back to whoever called it (`docs/spec/function-invocation.md` §7 — every
/// invocation is an HTTP request, so every outcome is an HTTP response,
/// full stop). The factories below spell out the dispatch contract so a
/// function author never has to memorise a status code or a header name —
/// **except that the contract itself is not uniform**: the same [Result]
/// this method returns is interpreted by two different platform components
/// depending on how the call arrived, and they do not honour the same
/// things. Read every row below before relying on [#retry] or [#fail]
/// doing more than the table says.
///
/// | Helper | Response this builds | Dispatch-job delivery (subscription / direct dispatch job) — `ProcessingApi`/`SubscriberDelivery` | Scheduled-job delivery — `JobDispatcher` |
/// |---|---|---|---|
/// | [#ack()] | `200`, empty body | any 2xx with no `{"ack":false}` body ⇒ `Delivered`, job marked `COMPLETED` (`SubscriberDelivery.java:159-165`, `classify`) | any 2xx ⇒ `markDelivered` (`JobDispatcher.java:150-163`) |
/// | [#retry(Duration)] | `429` + `Retry-After: <ceil-seconds>` | `429` ⇒ `Deferred`; rescheduled to `now + Retry-After` seconds (parsed as a non-negative integer, default 30s if absent/unparseable — `SubscriberDelivery.java:166-168,204-209`, `retryAfterSeconds`); **spends no retry budget** | **not honoured at all.** `JobDispatcher.deliver` only branches on `status >= 200 && status < 300` (`JobDispatcher.java:150-167`); a `429` falls into the same `fail(...)` path as any other non-2xx and consumes one of the instance's `deliveryMaxAttempts` — the requested delay is silently discarded. Say this to the caller: `retry()`'s duration is **advisory only** for a scheduled-job invocation. |
/// | [#fail(String)] | `500` + `{"error": "<reason>"}` | classified `Failed`/`HTTP_ERROR` — **identically to every other non-2xx, non-429 status** (`SubscriberDelivery.java:169-171`). `ProcessingApi.advance` spends one unit of retry budget and reschedules on the fixed backoff ladder until `attemptNumber >= job.maxRetries()`, only then `markFailed` (`ProcessingApi.java:320-341`). **The status code chosen here does not request immediate termination** — that is the platform's own attempt-count decision, never the subscriber's (`dispatch-seam.md` §5, open question 1: "every non-2xx, non-429 status … consumes retry budget identically"). | same uniformity: any non-2xx status fails the delivery; terminal only once `attemptsAfter >= deliveryMaxAttempts` (`JobDispatcher.java:164-193`, `fail`) — again no status-code distinction. |
///
/// In short: **there is no way for a function to force an immediate,
/// non-retryable failure**, and **no way to ask a scheduled-job delivery to
/// wait before its next attempt** — both processors decide retry-vs-terminal
/// and retry timing from their own state, not from anything in this
/// response. [#fail]'s status and [#retry]'s duration are diagnostic /
/// advisory respectively wherever the platform does not read them.
///
/// Built only through the static factories below (or [#http] /[#json] for a
/// direct HTTP answer), which enforce the invariants each response depends
/// on. `headers` is deep-copied and unmodifiable; `body` is cloned in the
/// constructor and again by [#body()] — mutating the array passed in, or
/// the one read out, never changes this record.
///
/// @param status  the HTTP status code; 100-599
/// @param headers response headers, in insertion order
/// @param body    the response body
public record Result(int status, Map<String, List<String>> headers, byte[] body) {

    private static final int DEFAULT_FAIL_STATUS = 500;

    public Result {
        if (status < 100 || status > 599) {
            throw new IllegalArgumentException("status must be 100-599, was " + status);
        }
        headers = Copies.multiMap(headers);
        body = Copies.bytes(body);
    }

    /// The invocation succeeded; nothing further to say. See the class doc
    /// table's `ack()` row.
    public static Result ack() {
        return new Result(200, Map.of(), new byte[0]);
    }

    /// The invocation should be redelivered after `after`, rounded **up**
    /// to a whole second (the wire contract this maps to, `Retry-After`, is
    /// integer seconds — rounding down would ask for less delay than the
    /// caller requested). See the class doc table's `retry()` row for which
    /// delivery paths actually honour this.
    ///
    /// @throws IllegalArgumentException if `after` is `null` or negative
    public static Result retry(Duration after) {
        if (after == null || after.isNegative()) {
            throw new IllegalArgumentException("after must be a non-null, non-negative Duration");
        }
        long seconds = ceilSeconds(after);
        return new Result(429, Map.of("Retry-After", List.of(Long.toString(seconds))), new byte[0]);
    }

    /// The invocation failed. `reason` becomes the audit/metrics detail,
    /// carried in a small hand-escaped `{"error": "<reason>"}` body — see
    /// the class doc table's `fail()` row for why this cannot force an
    /// immediate, non-retryable failure.
    ///
    /// @throws IllegalArgumentException if `reason` is blank or `null`
    public static Result fail(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
        String json = "{\"error\":\"" + JsonEscape.escape(reason) + "\"}";
        return new Result(DEFAULT_FAIL_STATUS, Map.of("Content-Type", List.of("application/json")),
                json.getBytes(StandardCharsets.UTF_8));
    }

    /// A direct HTTP answer, verbatim.
    ///
    /// @throws IllegalArgumentException if `status` is outside 100-599
    public static Result http(int status, Map<String, List<String>> headers, byte[] body) {
        return new Result(status, headers, body);
    }

    /// A direct HTTP answer whose body is `json` verbatim, with
    /// `Content-Type: application/json`. `json` is not validated or
    /// parsed — the caller built it, or already has it as text.
    ///
    /// @throws IllegalArgumentException if `status` is outside 100-599
    public static Result json(int status, String json) {
        Objects.requireNonNull(json, "json");
        return new Result(status, Map.of("Content-Type", List.of("application/json")),
                json.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public byte[] body() {
        return Copies.bytes(body);
    }

    /// Ceiling division to whole seconds — `Duration.ZERO` stays `0`
    /// (immediate retry is a legal request), any positive sub-second
    /// remainder rounds up to `1`, and the result is clamped to
    /// `Integer.MAX_VALUE` so an enormous duration cannot overflow the
    /// `Retry-After` header into a negative number.
    private static long ceilSeconds(Duration after) {
        long seconds = after.getSeconds();
        if (after.getNano() > 0) {
            seconds++;
        }
        return Math.min(seconds, Integer.MAX_VALUE);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Result other)) return false;
        return status == other.status && headers.equals(other.headers) && Arrays.equals(body, other.body);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(status, headers);
        return 31 * result + Arrays.hashCode(body);
    }

    @Override
    public String toString() {
        return "Result[status=" + status + ", headers=" + headers
                + ", body.length=" + (body == null ? 0 : body.length) + "]";
    }
}
