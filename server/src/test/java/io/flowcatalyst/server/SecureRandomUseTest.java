package io.flowcatalyst.server;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// Pins CONVENTIONS §8's "one helper each": every opaque random token or secret
/// comes from `platform.shared.SecureTokens`, so no call site can drift to
/// `java.util.Random` or the padded encoder. A new `new SecureRandom()` in main
/// code is either a token (use `SecureTokens.urlSafe`) or raw randomness with a
/// reason — then it joins the list below with that reason.
///
/// Mutant (by hand): add `new SecureRandom()` to any other main file — the test
/// names it.
class SecureRandomUseTest {

    private static final List<Path> ROOTS =
            List.of(Path.of("src/main/java"), Path.of("../fcdev/src/main/java"));

    /// Files that need raw randomness rather than a URL-safe token, each with why.
    private static final List<String> ALLOWED = List.of(
            // the helper itself
            "io/flowcatalyst/platform/shared/SecureTokens.java",
            // a TOTP secret is raw bytes, base32-encoded for authenticator apps
            "io/flowcatalyst/platform/auth/mfa/Totp.java",
            // recovery codes and email PINs are drawn from a human-typable alphabet
            "io/flowcatalyst/platform/auth/mfa/RecoveryCodes.java",
            // AES-GCM IVs
            "io/flowcatalyst/platform/shared/encryption/Encryption.java",
            // Argon2 salts
            "io/flowcatalyst/platform/shared/auth/PasswordHash.java",
            // the legacy alphabet-shaped webhook token (a second, wire-visible format)
            "io/flowcatalyst/platform/serviceaccount/operations/WebhookSecrets.java",
            // fcdev's generated app key: 32 raw bytes in STANDARD base64, the app-key format Encryption reads
            "io/flowcatalyst/fcdev/DevBootstrap.java");

    @Test
    void randomTokensComeFromSecureTokens() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path root : ROOTS) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> files = Files.walk(root)) {
                files.filter(p -> p.toString().endsWith(".java")).sorted().forEach(p -> {
                    String rel = root.relativize(p).toString().replace('\\', '/');
                    if (ALLOWED.contains(rel)) return;
                    String content;
                    try {
                        content = Files.readString(p);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    if (content.contains("new SecureRandom(")) {
                        offenders.add(rel);
                    }
                });
            }
        }
        assertThat(offenders)
                .as("make tokens with SecureTokens.urlSafe(bytes); raw randomness needs an ALLOWED entry with a reason")
                .isEmpty();
    }
}
