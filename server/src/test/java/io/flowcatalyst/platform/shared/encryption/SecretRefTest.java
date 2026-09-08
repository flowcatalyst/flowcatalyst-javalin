package io.flowcatalyst.platform.shared.encryption;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import io.flowcatalyst.platform.shared.encryption.SecretRef.Encrypted;
import io.flowcatalyst.platform.shared.encryption.SecretRef.External;
import io.flowcatalyst.platform.shared.encryption.SecretRef.Hashed;
import io.flowcatalyst.platform.shared.encryption.SecretRef.Literal;
import io.flowcatalyst.platform.shared.encryption.SecretRef.None;
import io.flowcatalyst.platform.shared.encryption.SecretRef.Plain;

/// `docs/spec/encryption.md` §3 — the stored-string grammar.
class SecretRefTest {

    static final String B64 = Base64.getEncoder().encodeToString(new byte[40]);

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\n\t"})
    void blankIsNone(String value) {
        assertThat(SecretRef.parse(value)).isSameAs(None.INSTANCE);
        assertThat(None.INSTANCE.stored()).isEmpty();
    }

    @Test
    void encryptedPrefixDecodesThePayloadAndReEncodesCanonically() {
        var ref = SecretRef.parse("  encrypted:" + B64 + " ");
        assertThat(ref).isEqualTo(new Encrypted(new byte[40]));
        assertThat(((Encrypted) ref).stored()).isEqualTo("encrypted:" + B64);
    }

    @Test
    void encryptedPrefixWithNonBase64PayloadIsRejectedNotMisfiled() {
        assertThatThrownBy(() -> SecretRef.parse("encrypted:not base64!"))
                .isInstanceOf(IllegalArgumentException.class);
        // Go-strict: length must be a multiple of four, padding required.
        assertThatThrownBy(() -> SecretRef.parse("encrypted:QUJ"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not base64");
        assertThat(SecretRef.parse("encrypted:QUJDRA==")).isInstanceOf(Encrypted.class);
        assertThatThrownBy(() -> SecretRef.parse("encrypted:QUJDRA=")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void encryptedIsValueBasedOnBytesAndDefensivelyCopied() {
        var bytes = new byte[]{1, 2, 3};
        var ref = new Encrypted(bytes);
        bytes[0] = 9;
        assertThat(ref.envelope()).containsExactly(1, 2, 3);
        assertThat(ref).isEqualTo(new Encrypted(new byte[]{1, 2, 3})).hasSameHashCodeAs(new Encrypted(new byte[]{1, 2, 3}));
        assertThat(ref.toString()).doesNotContain("1, 2, 3");
    }

    /// A `HmacSHA256` MAC is always 32 bytes — the payload of every `hashed:v1:` ref.
    static final String MAC_B64 = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void hashedPrefixDecodesTheMacAndReEncodesCanonically() {
        var ref = SecretRef.parse("  hashed:v1:" + MAC_B64 + " ");
        assertThat(ref).isEqualTo(new Hashed(new byte[32]));
        assertThat(((Hashed) ref).stored()).isEqualTo("hashed:v1:" + MAC_B64);
    }

    @Test
    void hashedPrefixWithNonBase64OrWrongLengthPayloadIsRejected() {
        assertThatThrownBy(() -> SecretRef.parse("hashed:v1:not base64!"))
                .isInstanceOf(IllegalArgumentException.class);
        // 31 bytes: one short of a HmacSHA256 MAC.
        String short31 = Base64.getEncoder().encodeToString(new byte[31]);
        assertThatThrownBy(() -> SecretRef.parse("hashed:v1:" + short31))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("32 bytes");
        // 33 bytes: one too many.
        String long33 = Base64.getEncoder().encodeToString(new byte[33]);
        assertThatThrownBy(() -> SecretRef.parse("hashed:v1:" + long33))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("32 bytes");
    }

    @Test
    void hashedIsValueBasedOnBytesAndDefensivelyCopied() {
        var bytes = new byte[32];
        bytes[0] = 1;
        var ref = new Hashed(bytes);
        bytes[0] = 9;
        assertThat(ref.mac()[0]).as("the record clones its input").isEqualTo((byte) 1);
        var expected = new byte[32];
        expected[0] = 1;
        assertThat(ref).isEqualTo(new Hashed(expected)).hasSameHashCodeAs(new Hashed(expected));
        assertThat(ref.toString()).as("never prints the MAC").doesNotContain("1, 0, 0");
    }

    /// A `hashed:v1:` ref is not decryptable — [SecretRef] classifies it, but
    /// only [Encryption#decrypt] and [Encryption#verifySecret] give it meaning;
    /// this class's job ends at "well-formed" vs "not".
    @Test
    void hashedIsAnAtRestShapeLikeEncrypted() {
        assertThat(SecretRef.parse("hashed:v1:" + MAC_B64)).isInstanceOf(SecretRef.AtRest.class);
    }

    @ParameterizedTest
    @CsvSource({
            "aws-sm://name,              aws-sm, name",
            "aws-ps:///path/to/param,    aws-ps, /path/to/param",
            "gcp-sm://s,                 gcp-sm, s",
            "vault://path#k,             vault,  path#k",
            "env://VAR,                  env,    VAR",
            "'  env://VAR  ',            env,    VAR",
    })
    void knownSchemesAreExternalAndStoredVerbatim(String stored, String scheme, String ref) {
        assertThat(SecretRef.parse(stored)).isEqualTo(new External(scheme, ref));
        assertThat(new External(scheme, ref).stored()).isEqualTo(stored.strip());
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://example.com/secret", "s3://bucket/key", "://x", "aws-sm:/one-slash"})
    void unknownSchemesArePlaintext(String value) {
        assertThat(SecretRef.parse(value)).isEqualTo(new Plain(value));
    }

    @Test
    void externalRejectsUnknownScheme() {
        assertThatThrownBy(() -> new External("s3", "x")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void literalKeepsItsValueAndPrefix() {
        assertThat(SecretRef.parse("literal:hello world")).isEqualTo(new Literal("hello world"));
        assertThat(new Literal("hello world").stored()).isEqualTo("literal:hello world");
        assertThat(SecretRef.parse("literal:")).isEqualTo(new Literal(""));
    }

    @Test
    void encryptDirectiveIsStrippedIntoPlain() {
        assertThat(SecretRef.parse("encrypt:mysecret")).isEqualTo(new Plain("mysecret"));
        assertThat(SecretRef.parse("encrypt:")).isEqualTo(new Plain(""));
        // The directive is stripped once; an inner prefix is part of the secret.
        assertThat(SecretRef.parse("encrypt:encrypt:x")).isEqualTo(new Plain("encrypt:x"));
    }

    @Test
    void bareStringsArePlainEvenWhenTheyLookLikeBase64() {
        assertThat(SecretRef.parse("oIC8Q~263EHzIfRlOtV8MQTZnLdHdrb4I~~Jydv2")).isEqualTo(new Plain("oIC8Q~263EHzIfRlOtV8MQTZnLdHdrb4I~~Jydv2"));
        assertThat(SecretRef.parse(B64)).isEqualTo(new Plain(B64));
        assertThat(SecretRef.parse("  spaced  ")).isEqualTo(new Plain("spaced"));
    }

    @Test
    void plainAndLiteralDoNotPrintTheirSecret() {
        assertThat(new Plain("hunter2").toString()).doesNotContain("hunter2");
        assertThat(new Literal("hunter2").toString()).doesNotContain("hunter2");
    }

    @Test
    void nullIsACallerBug() {
        assertThatThrownBy(() -> SecretRef.parse(null)).isInstanceOf(NullPointerException.class);
    }
}
