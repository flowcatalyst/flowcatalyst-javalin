package io.flowcatalyst.fnhost.http;

import io.flowcatalyst.router.wire.WebhookSigner;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/// Verifies a `webhook`-endpoint call (spec `function-host-listener.md` §3):
/// `X-FlowCatalyst-Signature` equals `hex(HMAC-SHA256(secret, timestamp ‖
/// body))`, computed the same way the server's own
/// [io.flowcatalyst.router.wire.WebhookSigner] signs a delivery — the one
/// place function-host is allowed to reach for it (the pom rule: `server` is
/// already a dependency; the client `flowcatalyst-sdk` module, which carries
/// the equivalent `WebhookSignature` verifier, is not, and this slice adds no
/// new dependency to get it). The exact tolerance numbers below (300 s / 60 s)
/// and the `hex(HMAC-SHA256(...))` shape are pinned in
/// `WebhookVerifierTest` against the SAME fixed vector
/// `sdk/src/test/java/io/flowcatalyst/sdk/webhook/WebhookSignatureTest#knownVectorMatchesOtherSdks`
/// commits — duplicated there rather than imported, so host and SDK are
/// proven to agree without the module depending on the SDK.
///
/// Adds what the raw HMAC rule does not know about: trying the entry's
/// CURRENT secret first, then its PREVIOUS one (spec §3's rotation window,
/// `Reconciler#previousWebhookSecret`), and the `NO_SIGNING_SECRET`
/// fail-closed case when the entry has no secret at all.
public final class WebhookVerifier {

    /// No older than this (spec §3) — the SDK's `WebhookSignature.DEFAULT_TOLERANCE_SECONDS`.
    public static final int MAX_AGE_SECONDS = 300;

    /// No more than this far in the future (spec §3) — the SDK's `WebhookSignature.FUTURE_GRACE_SECONDS`.
    public static final int FUTURE_GRACE_SECONDS = 60;

    private WebhookVerifier() {
    }

    public sealed interface Outcome permits Verified, Rejected {
    }

    public record Verified() implements Outcome {
        public static final Verified INSTANCE = new Verified();
    }

    /// `reason` is one of `MISSING_SIGNATURE`, `MISSING_TIMESTAMP`,
    /// `INVALID_TIMESTAMP`, `TIMESTAMP_EXPIRED`, `TIMESTAMP_IN_FUTURE`,
    /// `INVALID_SIGNATURE`, or `NO_SIGNING_SECRET` when the entry carries no
    /// secret at all.
    public record Rejected(String reason) implements Outcome {
    }

    /// @param currentSecret  the entry's current secret, `null` when none is recorded
    /// @param previousSecret the entry's still-accepted previous secret, `null` when
    ///                       there is none (no rotation in the window, or none ever recorded)
    public static Outcome verify(byte[] body, String signatureHeader, String timestampHeader,
                                  String currentSecret, String previousSecret) {
        if (currentSecret == null) {
            return new Rejected("NO_SIGNING_SECRET");
        }
        if (signatureHeader == null || signatureHeader.isEmpty()) {
            return new Rejected("MISSING_SIGNATURE");
        }
        if (timestampHeader == null || timestampHeader.isEmpty()) {
            return new Rejected("MISSING_TIMESTAMP");
        }
        Long epochSeconds = parseTimestamp(timestampHeader);
        if (epochSeconds == null) {
            return new Rejected("INVALID_TIMESTAMP");
        }
        long nowSeconds = Instant.now().getEpochSecond();
        if (epochSeconds < nowSeconds - MAX_AGE_SECONDS) {
            return new Rejected("TIMESTAMP_EXPIRED");
        }
        if (epochSeconds > nowSeconds + FUTURE_GRACE_SECONDS) {
            return new Rejected("TIMESTAMP_IN_FUTURE");
        }
        if (matches(body, signatureHeader, timestampHeader, currentSecret)) {
            return Verified.INSTANCE;
        }
        if (previousSecret != null && matches(body, signatureHeader, timestampHeader, previousSecret)) {
            return Verified.INSTANCE;
        }
        return new Rejected("INVALID_SIGNATURE");
    }

    /// Constant-time compare (mutating this to `String#equals` is NOT a
    /// required mutant here — a timing side channel is not something a unit
    /// test can observe; noted, not pinned, per the slice's report rules).
    private static boolean matches(byte[] body, String signatureHeader, String timestampHeader, String secret) {
        String expected = WebhookSigner.sign(secret, timestampHeader, body);
        byte[] a = expected.getBytes(StandardCharsets.UTF_8);
        byte[] b = signatureHeader.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8);
        return a.length == b.length && MessageDigest.isEqual(a, b);
    }

    /// The platform emits millisecond-ISO8601 UTC ([WebhookSigner#timestamp]);
    /// a bare Unix-seconds integer is accepted for backward compatibility,
    /// same as the SDK's own reader.
    private static Long parseTimestamp(String raw) {
        if (!raw.isEmpty() && raw.chars().allMatch(Character::isDigit)) {
            try {
                return Long.parseLong(raw);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        try {
            return Instant.parse(raw).getEpochSecond();
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
