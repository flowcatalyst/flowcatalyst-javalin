package io.flowcatalyst.platform.dispatchpool;

/// The pool's routing-eligibility state: `ACTIVE` (routable), `SUSPENDED`
/// (dispatch paused, in-flight work untouched) or `ARCHIVED` (retired). The
/// transitions between them are unconditional flips (spec §2). The constant
/// name is the stored and wire string.
public enum DispatchPoolStatus {
    ACTIVE, SUSPENDED, ARCHIVED;

    /// Strict reader for stored values (spec §1, X-06): never a silent
    /// default. See [DispatchPoolRepository]'s row mapper, which wraps
    /// [UnrecognisedDispatchPoolStatusException] in
    /// [CorruptDispatchPoolException] carrying the row id.
    ///
    /// @throws UnrecognisedDispatchPoolStatusException `s` is `null` or not
    ///                                                 one of the three states
    public static DispatchPoolStatus parse(String s) {
        return switch (s) {
            case "ACTIVE" -> ACTIVE;
            case "SUSPENDED" -> SUSPENDED;
            case "ARCHIVED" -> ARCHIVED;
            case null, default -> throw new UnrecognisedDispatchPoolStatusException(s);
        };
    }

    /// Thrown by [#parse] for a stored value outside the recognised set —
    /// X-06: never a silent default.
    public static final class UnrecognisedDispatchPoolStatusException extends RuntimeException {
        public UnrecognisedDispatchPoolStatusException(String raw) {
            super("unrecognised dispatch pool status: " + raw);
        }
    }
}
