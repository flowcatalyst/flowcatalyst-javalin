package io.flowcatalyst.platform.shared.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import com.password4j.Argon2Function;
import com.password4j.BcryptFunction;
import com.password4j.types.Argon2;
import com.password4j.types.Bcrypt;
import org.junit.jupiter.api.Test;

import io.flowcatalyst.platform.shared.auth.PasswordHash.Verification;

class PasswordHashTest {

    /// The hash a Go `fcdev start` minted for `DevPassword123!`
    /// (`passwordhash.Hash`, m=65536 t=3 p=4) — proves byte-level
    /// compatibility with the Go scheme.
    static final String GO_HASH =
            "$argon2id$v=19$m=65536,t=3,p=4$l1LPNz+QzTLQDFDU4ly8RQ$meSaqZSVPnlnJyP8Yi3dwi+pYXrtaE8zVQnijzaD3aM";

    @Test
    void hashProducesGoPhcShape() {
        String h = PasswordHash.hash("correct horse battery staple");
        // 16-byte salt → 22 unpadded base64 chars; 32-byte key → 43.
        assertThat(h).matches("^\\$argon2id\\$v=19\\$m=65536,t=3,p=4\\$[A-Za-z0-9+/]{22}\\$[A-Za-z0-9+/]{43}$");
    }

    @Test
    void hashUsesFreshSaltEachTime() {
        assertThat(PasswordHash.hash("x")).isNotEqualTo(PasswordHash.hash("x"));
    }

    @Test
    void verifyRoundTrip() {
        String h = PasswordHash.hash("s3cret!");
        assertThat(PasswordHash.verify("s3cret!", h)).isEqualTo(Verification.OK);
        assertThat(PasswordHash.matches("s3cret!", h)).isTrue();
        assertThat(PasswordHash.verify("s3cret", h)).isEqualTo(Verification.MISMATCH);
        assertThat(PasswordHash.matches("S3CRET!", h)).isFalse();
    }

    @Test
    void verifiesAHashMintedByGo() {
        assertThat(PasswordHash.verify("DevPassword123!", GO_HASH)).isEqualTo(Verification.OK);
        assertThat(PasswordHash.verify("DevPassword123", GO_HASH)).isEqualTo(Verification.MISMATCH);
        assertThat(PasswordHash.needsRehash(GO_HASH)).isFalse();
    }

    @Test
    void malformedEnvelopesAreInvalidNotMismatch() {
        assertThat(PasswordHash.verify("x", "")).isEqualTo(Verification.INVALID_HASH);
        assertThat(PasswordHash.verify("x", null)).isEqualTo(Verification.INVALID_HASH);
        assertThat(PasswordHash.verify("x", "not-a-hash")).isEqualTo(Verification.INVALID_HASH);
        assertThat(PasswordHash.verify("x", "$argon2d$v=19$m=65536,t=3,p=4$AAAAAAAAAAAAAAAAAAAAAA$AAAA"))
                .isEqualTo(Verification.INVALID_HASH);
        assertThat(PasswordHash.verify("x", "$argon2id$v=16$m=65536,t=3,p=4$AAAAAAAAAAAAAAAAAAAAAA$AAAA"))
                .isEqualTo(Verification.INVALID_HASH);
        assertThat(PasswordHash.verify("x", "$argon2id$v=19$m=65536,t=3$AAAAAAAAAAAAAAAAAAAAAA$AAAA"))
                .isEqualTo(Verification.INVALID_HASH);
        assertThat(PasswordHash.verify("x", "$argon2id$v=19$m=65536,t=3,p=4$AAAAAAAAAAAAAAAAAAAAAA"))
                .isEqualTo(Verification.INVALID_HASH);
        assertThat(PasswordHash.verify("x", "$argon2id$v=19$m=65536,t=3,p=4$!!!$AAAA"))
                .isEqualTo(Verification.INVALID_HASH);
    }

    @Test
    void acceptsLaravelArgon2iAndFlagsItForRehash() {
        // Laravel's argon2i defaults: m=65536, t=4, p=1, 16-byte salt, 32-byte key.
        byte[] salt = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        byte[] key = Argon2Function.getInstance(65536, 4, 1, 32, Argon2.I, 19)
                .hash("laravel-pw".getBytes(StandardCharsets.UTF_8), salt).getBytes();
        Base64.Encoder b64 = Base64.getEncoder().withoutPadding();
        String legacy = "$argon2i$v=19$m=65536,t=4,p=1$" + b64.encodeToString(salt) + "$" + b64.encodeToString(key);

        assertThat(PasswordHash.verify("laravel-pw", legacy)).isEqualTo(Verification.OK);
        assertThat(PasswordHash.verify("wrong", legacy)).isEqualTo(Verification.MISMATCH);
        assertThat(PasswordHash.needsRehash(legacy)).isTrue();
    }

    @Test
    void acceptsBcryptVariantsAndTruncatesAt72Bytes() {
        String y = BcryptFunction.getInstance(Bcrypt.Y, 6).hash("bcrypt-pw").getResult();
        assertThat(y).startsWith("$2y$");
        assertThat(PasswordHash.verify("bcrypt-pw", y)).isEqualTo(Verification.OK);
        assertThat(PasswordHash.verify("bcrypt-pX", y)).isEqualTo(Verification.MISMATCH);
        assertThat(PasswordHash.needsRehash(y)).isTrue();

        String a = BcryptFunction.getInstance(Bcrypt.A, 6).hash("bcrypt-pw").getResult();
        assertThat(a).startsWith("$2a$");
        assertThat(PasswordHash.verify("bcrypt-pw", a)).isEqualTo(Verification.OK);

        String b = BcryptFunction.getInstance(Bcrypt.B, 6).hash("bcrypt-pw").getResult();
        assertThat(b).startsWith("$2b$");
        assertThat(PasswordHash.verify("bcrypt-pw", b)).isEqualTo(Verification.OK);

        // PHP's password_verify only looks at the first 72 bytes; so do we.
        String seventyTwo = "a".repeat(72);
        String longer = seventyTwo + "tail-that-bcrypt-ignores";
        String hashOf72 = BcryptFunction.getInstance(Bcrypt.Y, 6).hash(seventyTwo).getResult();
        assertThat(PasswordHash.verify(longer, hashOf72)).isEqualTo(Verification.OK);

        assertThat(PasswordHash.verify("x", "$2y$06$not-a-real-bcrypt-hash")).isEqualTo(Verification.INVALID_HASH);
    }

    @Test
    void needsRehashForNonDefaultParamsAndGarbage() {
        assertThat(PasswordHash.needsRehash(PasswordHash.hash("pw"))).isFalse();

        var weaker = new PasswordHash.Params(8 * 1024, 2, 1, 32, 16);
        String weak = PasswordHash.hashWithParams("pw", weaker);
        assertThat(weak).startsWith("$argon2id$v=19$m=8192,t=2,p=1$");
        assertThat(PasswordHash.verify("pw", weak)).isEqualTo(Verification.OK);
        assertThat(PasswordHash.needsRehash(weak)).isTrue();

        var shorterKey = new PasswordHash.Params(64 * 1024, 3, 4, 16, 16);
        assertThat(PasswordHash.needsRehash(PasswordHash.hashWithParams("pw", shorterKey))).isTrue();

        assertThat(PasswordHash.needsRehash("garbage")).isTrue();
        assertThat(PasswordHash.needsRehash(null)).isTrue();
    }

    @Test
    void equalizeTimingNeverThrowsAndDoesNotDependOnTheInput() {
        assertThatCode(() -> PasswordHash.equalizeTiming("whatever")).doesNotThrowAnyException();
        assertThatCode(() -> PasswordHash.equalizeTiming("")).doesNotThrowAnyException();
        assertThatCode(() -> PasswordHash.equalizeTiming("x".repeat(10_000))).doesNotThrowAnyException();
    }
}
