package io.flowcatalyst.platform.shared.encryption;

import java.util.Base64;
import java.util.Optional;

/// Standard-alphabet, padded base64 with Go `StdEncoding` strictness: the
/// JDK's basic decoder tolerates missing padding, which would let a
/// three-character plaintext decode "successfully" into bytes.
final class Base64Strict {

    private Base64Strict() {
    }

    static Optional<byte[]> decode(String s) {
        if (s.length() % 4 != 0) return Optional.empty();
        try {
            return Optional.of(Base64.getDecoder().decode(s));
        } catch (IllegalArgumentException _) {
            return Optional.empty();
        }
    }

    static String encode(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }
}
