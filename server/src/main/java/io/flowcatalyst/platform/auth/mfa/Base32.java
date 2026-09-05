package io.flowcatalyst.platform.auth.mfa;

import java.io.ByteArrayOutputStream;

/// RFC 4648 base32 (upper-case alphabet) without padding — the form
/// authenticator apps and `otpauth://` URIs exchange TOTP secrets in.
/// Decoding is lenient about case, whitespace and trailing `=` so a secret
/// pasted back from a user is accepted.
public final class Base32 {

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private Base32() {
    }

    public static String encode(byte[] data) {
        var out = new StringBuilder((data.length * 8 + 4) / 5);
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                out.append(ALPHABET.charAt((buffer >> (bits - 5)) & 0x1F));
                bits -= 5;
            }
        }
        if (bits > 0) {
            out.append(ALPHABET.charAt((buffer << (5 - bits)) & 0x1F));
        }
        return out.toString();
    }

    /// @throws IllegalArgumentException on a character outside the alphabet
    public static byte[] decode(String text) {
        var out = new ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        for (char raw : text.toCharArray()) {
            if (raw == '=' || Character.isWhitespace(raw)) {
                continue;
            }
            int v = ALPHABET.indexOf(Character.toUpperCase(raw));
            if (v < 0) {
                throw new IllegalArgumentException("not base32: '" + raw + "'");
            }
            buffer = (buffer << 5) | v;
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >> (bits - 8)) & 0xFF);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }
}
