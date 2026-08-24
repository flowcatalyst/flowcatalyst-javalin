package io.flowcatalyst.router.wire;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;

/// Signs a delivery the way every FlowCatalyst receiver verifies it.
///
/// **Contract [C]** (`docs/spec/router.md` §6.3), pinned by the golden vector
/// in [WebhookSignerTest]: `signature = hex(HMAC-SHA256(secret, timestamp ‖
/// body))`, lower-case hex, over the *exact* bytes sent.
///
/// The timestamp is `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'` in UTC — exactly three
/// fractional digits and a literal `Z`, always 24 characters. That is
/// narrower than RFC 3339 and narrower than the platform's own six-digit
/// [io.flowcatalyst.platform.shared.json.Json] format, so this formatter is
/// deliberately local: the signed bytes must not move if the platform's
/// mapper ever changes precision.
public final class WebhookSigner {

    /// `2026-01-01T00:00:00.000Z` — 24 characters, always.
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private static final String HMAC_SHA256 = "HmacSHA256";

    private WebhookSigner() {
    }

    /// Formats `at` as the signed/transmitted timestamp.
    public static String timestamp(Instant at) {
        return TIMESTAMP.format(at);
    }

    /// The signature for `body` at `timestamp`, as lower-case hex.
    ///
    /// `timestamp` is the string that goes on the wire — the caller passes
    /// the same value to both, so the signed bytes and the transmitted header
    /// cannot disagree.
    public static String sign(String secret, String timestamp, byte[] body) {
        try {
            var mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            mac.update(timestamp.getBytes(StandardCharsets.UTF_8));
            mac.update(body);
            return HexFormat.of().formatHex(mac.doFinal());
        } catch (GeneralSecurityException e) {
            // HmacSHA256 is mandated by the JDK and the key is never empty
            // here, so this is a broken runtime, not a delivery outcome.
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }
}
