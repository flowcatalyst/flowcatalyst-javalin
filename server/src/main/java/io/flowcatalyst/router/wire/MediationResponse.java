package io.flowcatalyst.router.wire;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.flowcatalyst.platform.shared.json.Json;

/// The optional JSON body a target may return on a 2xx to steer the router.
///
/// **The evaluation order is the contract** (`docs/spec/router.md` §6.6a),
/// not an implementation detail, which is why resolving it lives here beside
/// the shape rather than in the mediator.
///
/// ```
/// { "ack": bool?, "delaySeconds": uint32?, "flushGroup": bool? }
/// ```
///
/// `delaySeconds` is overloaded: a *retry floor* when deferring, a
/// *suppression window* when flushing, ignored otherwise. Same field, three
/// meanings, distinguished only by its siblings.
@JsonIgnoreProperties(ignoreUnknown = true)
public record MediationResponse(Boolean ack, Integer delaySeconds, Boolean flushGroup) {

    /// Resolves a 2xx response into an outcome.
    ///
    /// The body is consulted only when it is non-empty and parses as JSON;
    /// anything else is a plain success, because a target that returns HTML,
    /// an empty body or malformed JSON alongside a 2xx has still accepted the
    /// message. Then, first match wins:
    ///
    ///   1. `ack: false` → [MediationOutcome.Deferred]
    ///   2. `flushGroup: true` → [MediationOutcome.Success] with the flush set
    ///   3. otherwise → plain [MediationOutcome.Success]
    ///
    /// So **`ack:false` beats `flushGroup`**: a target that wants the message
    /// back cannot also discard its group. A body carrying both defers, and
    /// the flush is dropped silently — matching Go, which does not log it.
    public static MediationOutcome resolve(int statusCode, byte[] body) {
        if (body == null || body.length == 0) {
            return MediationOutcome.Success.of(statusCode);
        }
        MediationResponse parsed;
        try {
            parsed = Json.MAPPER.readValue(body, MediationResponse.class);
        } catch (Exception e) {
            // Not JSON, or not this shape. The target still answered 2xx.
            return MediationOutcome.Success.of(statusCode);
        }
        if (parsed == null) {
            return MediationOutcome.Success.of(statusCode);
        }
        if (Boolean.FALSE.equals(parsed.ack)) {
            return new MediationOutcome.Deferred(
                    statusCode, parsed.delayOrZero(), "Target returned ack=false");
        }
        if (Boolean.TRUE.equals(parsed.flushGroup)) {
            return MediationOutcome.Success.flushing(statusCode, parsed.delayOrZero());
        }
        return MediationOutcome.Success.of(statusCode);
    }

    /// The requested delay, or 0. Negative is treated as absent: the wire
    /// type is unsigned on the Go side, so a negative can only be a caller
    /// error, and letting it through would invert a backoff floor.
    public int delayOrZero() {
        return delaySeconds == null || delaySeconds < 0 ? 0 : delaySeconds;
    }
}
