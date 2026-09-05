package io.flowcatalyst.platform.dispatchjob;

import io.flowcatalyst.sdk.usecase.UseCaseException;

/// What a dispatch job carries: a business `EVENT` or a `TASK` (spec §1.1).
/// The constant name is the stored and wire string.
public enum DispatchJobKind {
    EVENT, TASK;

    /// Strict reader for stored values (dispatch-seam spec §1.1, X-06): never
    /// a silent default. Only used for the stored column — nothing parses
    /// this enum from the wire. See [DispatchJobRepository]'s row mapper,
    /// which wraps [UnrecognisedDispatchJobKindException] in
    /// [CorruptDispatchJobException] carrying the row id.
    ///
    /// @throws UnrecognisedDispatchJobKindException `s` is `null` or not `EVENT`/`TASK`
    public static DispatchJobKind parse(String s) {
        return switch (s) {
            case "EVENT" -> EVENT;
            case "TASK" -> TASK;
            case null, default -> throw new UnrecognisedDispatchJobKindException(s);
        };
    }

    /// Thrown by [#parse] for a stored value outside the recognised set —
    /// X-06: never a silent default.
    public static final class UnrecognisedDispatchJobKindException extends RuntimeException {
        public UnrecognisedDispatchJobKindException(String raw) {
            super("unrecognised dispatch job kind: " + raw);
        }
    }

    /// Strict reader for the ingest wire boundary (sdk-ingest spec §4.1,
    /// X-06): an absent/blank `kind` is the documented "unspecified" case
    /// and defaults to `EVENT`, but a non-blank, unrecognised value is a
    /// caller error — never silently coerced.
    ///
    /// @throws UseCaseException validation `INVALID_KIND`
    public static DispatchJobKind parseStrict(String s) {
        if (s == null || s.isBlank()) return EVENT;
        return switch (s) {
            case "EVENT" -> EVENT;
            case "TASK" -> TASK;
            default -> throw UseCaseException.validation("INVALID_KIND",
                    "unknown dispatch job kind \"" + s + "\"; must be EVENT or TASK");
        };
    }
}
