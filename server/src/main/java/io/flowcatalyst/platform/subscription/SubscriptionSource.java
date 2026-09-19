package io.flowcatalyst.platform.subscription;

/// Where the subscription was authored: `CODE` (legacy SDK source sync),
/// `API` (application sync), `UI` (admin create), or `FUNCTION` (created by
/// the function platform at promote, `function-invocation.md` §4.1, ruling
/// R6 — a deliberate divergence from Go: `chk_msg_subscriptions_source` is
/// widened for it in `V11`, and `SchemaFingerprintTest` /
/// `GoAdoptionTest` each carry a named, exact allowance for that one
/// constraint line). Sync updates and removes `API`/`CODE` rows only;
/// `UI` and `FUNCTION` rows are never touched by an application SDK sync
/// (spec §7, §4.1). The constant name is the stored and wire string.
public enum SubscriptionSource {
    CODE, API, UI, FUNCTION;

    /// Strict reader for stored values (spec §1, X-06): never a silent
    /// default. See [SubscriptionRepository]'s row mapper, which wraps
    /// [UnrecognisedSubscriptionSourceException] in
    /// [CorruptSubscriptionException] carrying the row id.
    ///
    /// @throws UnrecognisedSubscriptionSourceException `s` is `null` or not
    ///                                                 one of `CODE` / `API` / `UI` / `FUNCTION`
    public static SubscriptionSource parse(String s) {
        return switch (s) {
            case "CODE" -> CODE;
            case "API" -> API;
            case "UI" -> UI;
            case "FUNCTION" -> FUNCTION;
            case null, default -> throw new UnrecognisedSubscriptionSourceException(s);
        };
    }

    /// Whether sync may update or remove a row with this source (spec §7,
    /// `function-invocation.md` §4.1): `false` for `FUNCTION` — an
    /// application SDK's `removeUnlisted` subscription sync must never touch
    /// a function's own subscription.
    public boolean isSyncManaged() {
        return switch (this) {
            case CODE, API -> true;
            case UI, FUNCTION -> false;
        };
    }

    /// Thrown by [#parse] for a stored value outside the recognised set —
    /// X-06: never a silent default.
    public static final class UnrecognisedSubscriptionSourceException extends RuntimeException {
        public UnrecognisedSubscriptionSourceException(String raw) {
            super("unrecognised subscription source: " + raw);
        }
    }
}
