package io.flowcatalyst.platform.serviceaccount.operations;

import java.security.SecureRandom;
import java.util.Base64;

/// Mints plaintext webhook credentials (spec §4.1, §4.6). Byte-shape
/// compatible with Go's generators so a minted value looks the same to an
/// operator reading either platform's logs, though the wire contract only
/// requires "opaque random token" / "32 bytes of key material" — nothing
/// downstream parses the shape.
///
///   - auth token: `"fc_"` + 32 random lowercase-alphanumeric characters.
///   - signing secret: 32 random bytes, URL-safe base64, no padding.
///
/// Minting only — no stash. The plaintext this returns is handed straight to
/// a caller-owned sink (spec §5): the operation writes to it after
/// authorisation and after minting, never before.
///
/// Public: `application.operations.ProvisionServiceAccount` (spec
/// `application.md` §10) mints the same shape of webhook credentials for a
/// provisioned service account's row, from outside this package. This is
/// the one visibility widening this unit made outside its listed files —
/// see the port brief's report for why.
public final class WebhookSecrets {

    private static final String TOKEN_ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz";
    private static final int TOKEN_RANDOM_CHARS = 32;
    private static final int SIGNING_SECRET_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    private WebhookSecrets() {
    }

    public static String generateAuthToken() {
        var sb = new StringBuilder(3 + TOKEN_RANDOM_CHARS);
        sb.append("fc_");
        for (int i = 0; i < TOKEN_RANDOM_CHARS; i++) {
            sb.append(TOKEN_ALPHABET.charAt(RANDOM.nextInt(TOKEN_ALPHABET.length())));
        }
        return sb.toString();
    }

    public static String generateSigningSecret() {
        byte[] bytes = new byte[SIGNING_SECRET_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
