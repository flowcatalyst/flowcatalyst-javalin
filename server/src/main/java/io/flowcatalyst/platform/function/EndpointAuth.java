package io.flowcatalyst.platform.function;

import io.flowcatalyst.sdk.usecase.UseCaseException;

import java.util.Locale;

/// How **the host** authenticates a call before it reaches the function
/// (spec `function-invocation.md` §3). Replaces package A's `AuthMode`
/// (`bearer`/`none`) now that a function is always invoked over HTTP and the
/// platform itself delivers webhooks:
///
/// - [#WEBHOOK] — the platform's signature (`X-FlowCatalyst-Signature` /
///   `-Timestamp`) under the function's application's signing secret. What
///   subscriptions, direct dispatch jobs and scheduled jobs use.
/// - [#PLATFORM] — a platform bearer token, verified locally against the
///   platform's JWKS; the principal is passed to the function.
/// - [#NONE] — the host checks nothing; the function authenticates itself.
///
/// **There is no default.** Every endpoint must name its `auth` — an absent
/// key is `ENDPOINT_AUTH_REQUIRED` ([Manifest]'s job, not this reader's: a
/// missing value and an unrecognised one are different mistakes, spec §3).
/// Written lower-case wherever the manifest's own JSON shape appears.
public enum EndpointAuth {
    WEBHOOK, PLATFORM, NONE;

    /// Stored reader — exact constant name.
    ///
    /// @throws IllegalArgumentException `raw` is not `WEBHOOK`, `PLATFORM` or `NONE`
    public static EndpointAuth parse(String raw) {
        return switch (raw) {
            case "WEBHOOK" -> WEBHOOK;
            case "PLATFORM" -> PLATFORM;
            case "NONE" -> NONE;
            case null, default -> throw new IllegalArgumentException("unrecognised endpoint auth: " + raw);
        };
    }

    /// Wire reader — case-insensitive (spec §3, `ENDPOINT_INVALID`). Throws
    /// on a genuinely unrecognised value; an *absent* `auth` is
    /// [Manifest]'s `ENDPOINT_AUTH_REQUIRED`, never this method's concern —
    /// callers only reach this once a value is known to be present.
    ///
    /// @throws UseCaseException validation `ENDPOINT_INVALID`
    public static EndpointAuth parseStrict(String raw) {
        String lower = raw == null ? "" : raw.toLowerCase(Locale.ROOT);
        return switch (lower) {
            case "webhook" -> WEBHOOK;
            case "platform" -> PLATFORM;
            case "none" -> NONE;
            default -> throw UseCaseException.validation("ENDPOINT_INVALID",
                    "auth must be webhook, platform or none");
        };
    }

    /// The lower-case spelling used in the manifest's own JSON shape.
    public String wireValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
