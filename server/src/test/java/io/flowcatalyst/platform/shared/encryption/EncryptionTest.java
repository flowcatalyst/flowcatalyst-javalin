package io.flowcatalyst.platform.shared.encryption;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.flowcatalyst.platform.shared.encryption.Decryption.External;
import io.flowcatalyst.platform.shared.encryption.Decryption.Failed;
import io.flowcatalyst.platform.shared.encryption.Decryption.Plaintext;
import io.flowcatalyst.platform.shared.encryption.Decryption.Reason;
import io.flowcatalyst.platform.shared.encryption.Encryption.KeyRotation;
import io.flowcatalyst.platform.shared.encryption.Encryption.SecretVerification;
import io.flowcatalyst.platform.shared.encryption.Encryption.SecretVerification.Matched;
import io.flowcatalyst.platform.shared.encryption.Encryption.SecretVerification.NoMatch;
import io.flowcatalyst.server.EnvReader;

/// `docs/spec/encryption.md` §1, §2, §4–§6.
class EncryptionTest {

    // ── golden vector, minted by the Go code (`encryption.New` + `crypto/cipher`) ──
    /// Bytes 0x01..0x20.
    static final String GO_KEY = "AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA=";
    static final byte[] GO_NONCE = HexFormat.of().parseHex("101112131415161718191a1b");
    static final String GO_PLAINTEXT = "flowcatalyst-golden";
    static final String GO_CT_TAG_HEX = "d487d56fe81a68c42729ecef4badb906e9ef779531b311a169eccf333551362d1e967b";
    static final String GO_V1 = "ARAREhMUFRYXGBkaG9SH1W/oGmjEJyns70utuQbp73eVMbMRoWnszzM1UTYtHpZ7";
    static final String GO_V0 = "EBESExQVFhcYGRob1IfVb+gaaMQnKezvS625Bunvd5UxsxGhaezPMzVRNi0elns=";
    /// A random Go `svc.Encrypt("hello from go")` under GO_KEY.
    static final String GO_RANDOM_V1 = "AQ6UiieEVidUUcho/ijOXwiot0z01NHb3UM5BEPCD1rQ1H706biKn1qN";
    /// Bytes 0xA0..0xBF.
    static final String OTHER_KEY = "oKGio6SlpqeoqaqrrK2ur7CxsrO0tba3uLm6u7y9vr8=";

    static final Encryption GO = Encryption.withKey(GO_KEY);

    // ── §2 byte layout ─────────────────────────────────────────────────

    @Test
    void sealWithTheGoldenNonceReproducesGoByteForByte() {
        var envelope = Encryption.seal(Encryption.parseKey(GO_KEY), GO_NONCE, GO_PLAINTEXT);
        assertThat(envelope[0]).isEqualTo((byte) 0x01);
        assertThat(envelope).hasSize(1 + 12 + GO_PLAINTEXT.length() + 16);
        assertThat(HexFormat.of().formatHex(envelope, 1, 13)).isEqualTo("101112131415161718191a1b");
        assertThat(HexFormat.of().formatHex(envelope, 13, envelope.length)).isEqualTo(GO_CT_TAG_HEX);
        assertThat(Base64.getEncoder().encodeToString(envelope)).isEqualTo(GO_V1);
    }

    @Test
    void decryptsGoMintedV1AndLegacyV0Envelopes() {
        assertThat(GO.decrypt(GO_V1)).isEqualTo(new Plaintext(GO_PLAINTEXT));
        assertThat(GO.decrypt(GO_V0)).isEqualTo(new Plaintext(GO_PLAINTEXT));
        assertThat(GO.decrypt("encrypted:" + GO_V1)).isEqualTo(new Plaintext(GO_PLAINTEXT));
        assertThat(GO.decrypt("encrypted:" + GO_V0)).isEqualTo(new Plaintext(GO_PLAINTEXT));
        assertThat(GO.decrypt(GO_RANDOM_V1)).isEqualTo(new Plaintext("hello from go"));
    }

    @Test
    void encryptWritesAV1EnvelopeWithAFreshNonce() {
        var a = GO.encrypt("same");
        var b = GO.encrypt("same");
        assertThat(a).isNotEqualTo(b).doesNotStartWith("encrypted:");
        var bytes = Base64.getDecoder().decode(a);
        assertThat(bytes[0]).isEqualTo((byte) 0x01);
        assertThat(bytes).hasSize(1 + 12 + 4 + 16);
        assertThat(GO.decrypt(a)).isEqualTo(new Plaintext("same"));
        assertThat(GO.decrypt(b)).isEqualTo(new Plaintext("same"));
    }

    @Test
    void roundTripsUnicodeAndTheEmptyString() {
        assertThat(GO.decrypt(GO.encrypt(""))).isEqualTo(new Plaintext(""));
        assertThat(GO.decrypt(GO.encrypt("pässwörd ✓ 🔐"))).isEqualTo(new Plaintext("pässwörd ✓ 🔐"));
    }

    @Test
    void v0EnvelopeWhoseNonceStartsWith0x01StillDecrypts() {
        // Build a v0 envelope by hand: nonce 01.., ciphertext+tag from the JDK.
        var nonce = new byte[12];
        nonce[0] = 0x01;
        var v1 = Encryption.seal(Encryption.parseKey(GO_KEY), nonce, "tricky");
        var v0 = java.util.Arrays.copyOfRange(v1, 1, v1.length);
        assertThat(v0[0]).isEqualTo((byte) 0x01);
        assertThat(GO.decrypt(Base64.getEncoder().encodeToString(v0))).isEqualTo(new Plaintext("tricky"));
    }

    // ── §4 outcomes ────────────────────────────────────────────────────

    @Test
    void externalRefsComeBackAsExternalNotFailures() {
        assertThat(GO.decrypt("aws-sm://prod/client-secret"))
                .isEqualTo(new External(new SecretRef.External("aws-sm", "prod/client-secret")));
        assertThat(GO.decrypt("vault://path#k")).isInstanceOf(External.class);
    }

    @Test
    void literalIsItsOwnPlaintext() {
        assertThat(GO.decrypt("literal:dev-secret")).isEqualTo(new Plaintext("dev-secret"));
    }

    @Test
    void blankIsEmpty() {
        assertThat(GO.decrypt("")).isEqualTo(new Failed(Reason.EMPTY));
        assertThat(GO.decrypt("  ")).isEqualTo(new Failed(Reason.EMPTY));
    }

    @ParameterizedTest
    @ValueSource(strings = {"hunter2", "oICxQ~FakeClientSecretForTestsOnly00000", "abcd", "QUJDRA==", "encrypt:QUJDRA=="})
    void plaintextThatIsNotAnEnvelopeIsNotEncrypted(String stored) {
        assertThat(GO.decrypt(stored)).isEqualTo(new Failed(Reason.NOT_ENCRYPTED));
    }

    @Test
    void malformedEnvelopesAreMalformed() {
        assertThat(GO.decrypt("encrypted:")).isEqualTo(new Failed(Reason.MALFORMED));
        assertThat(GO.decrypt("encrypted:not base64")).isEqualTo(new Failed(Reason.MALFORMED));
        assertThat(GO.decrypt("encrypted:QUJDRA==")).isEqualTo(new Failed(Reason.MALFORMED));
        // 27 bytes: one short of the smallest v0 envelope.
        assertThat(GO.decrypt("encrypted:" + Base64.getEncoder().encodeToString(new byte[27]))).isEqualTo(new Failed(Reason.MALFORMED));
        // 28 zero bytes is long enough to be an envelope — it just authenticates with no key.
        assertThat(GO.decrypt("encrypted:" + Base64.getEncoder().encodeToString(new byte[28]))).isEqualTo(new Failed(Reason.NO_MATCHING_KEY));
    }

    @Test
    void tamperedOrForeignKeyEnvelopesHaveNoMatchingKey() {
        assertThat(Encryption.withKey(OTHER_KEY).decrypt(GO_V1)).isEqualTo(new Failed(Reason.NO_MATCHING_KEY));
        var bytes = Base64.getDecoder().decode(GO_V1);
        bytes[20] ^= 1;
        assertThat(GO.decrypt(Base64.getEncoder().encodeToString(bytes))).isEqualTo(new Failed(Reason.NO_MATCHING_KEY));
        bytes = Base64.getDecoder().decode(GO_V0);
        bytes[bytes.length - 1] ^= 1;
        assertThat(GO.decrypt(Base64.getEncoder().encodeToString(bytes))).isEqualTo(new Failed(Reason.NO_MATCHING_KEY));
    }

    @Test
    void surroundingWhitespaceIsTolerated() {
        assertThat(GO.decrypt(" " + GO_V1 + "\n")).isEqualTo(new Plaintext(GO_PLAINTEXT));
    }

    // ── §5 encryptSecretRef ────────────────────────────────────────────

    @Test
    void plaintextBecomesAPrefixedEnvelopeThatRoundTrips() {
        var secret = "oICxQ~FakeClientSecretForTestsOnly00000";
        var stored = GO.encryptSecretRef(secret);
        assertThat(stored).startsWith("encrypted:");
        assertThat(GO.decrypt(stored)).isEqualTo(new Plaintext(secret));
        assertThat(GO.decrypt(GO.encryptSecretRef("encrypt:mysecret"))).isEqualTo(new Plaintext("mysecret"));
    }

    @Test
    void encryptSecretRefIsIdempotentAndPassesReferencesThrough() {
        var once = GO.encryptSecretRef("x");
        assertThat(GO.encryptSecretRef(once)).isEqualTo(once);
        assertThat(GO.encryptSecretRef("  " + once + " ")).isEqualTo(once);
        for (var ref : new String[]{"aws-sm://name", "aws-ps://p", "gcp-sm://s", "vault://path#k", "env://VAR", "literal:v"}) {
            assertThat(GO.encryptSecretRef(ref)).isEqualTo(ref);
        }
        assertThat(GO.encryptSecretRef("")).isEmpty();
        assertThat(GO.encryptSecretRef("  ")).isEqualTo("  ");
    }

    @Test
    void aBareEnvelopeFedBackInIsTreatedAsPlaintextAndWrappedAgain() {
        // Only the `encrypted:` prefix is an "already encrypted" claim; a user-supplied
        // secret that happens to be base64 must never be stored raw.
        var bare = GO.encrypt("x");
        var stored = GO.encryptSecretRef(bare);
        assertThat(stored).startsWith("encrypted:").isNotEqualTo("encrypted:" + bare);
        assertThat(GO.decrypt(stored)).isEqualTo(new Plaintext(bare));
    }

    @Test
    void encryptOfAnEnvelopeWrapsItTwice() {
        var inner = GO.encrypt("x");
        var outer = GO.encrypt(inner);
        assertThat(outer).isNotEqualTo(inner);
        assertThat(GO.decrypt(outer)).isEqualTo(new Plaintext(inner));
    }

    @Test
    void encryptSecretRefRejectsAnUnreadableEncryptedClaim() {
        assertThatThrownBy(() -> GO.encryptSecretRef("encrypted:not base64")).isInstanceOf(IllegalArgumentException.class);
    }

    // ── §6 rotation ────────────────────────────────────────────────────

    @Test
    void previousKeyStillDecryptsButNeverEncrypts() {
        var rotating = Encryption.of(KeyRotation.of(OTHER_KEY, GO_KEY));
        assertThat(rotating.decrypt(GO_V1)).isEqualTo(new Plaintext(GO_PLAINTEXT));
        assertThat(rotating.decrypt(GO_V0)).isEqualTo(new Plaintext(GO_PLAINTEXT));
        var fresh = rotating.encrypt("new-data");
        assertThat(Encryption.withKey(OTHER_KEY).decrypt(fresh)).isEqualTo(new Plaintext("new-data"));
        assertThat(GO.decrypt(fresh)).isEqualTo(new Failed(Reason.NO_MATCHING_KEY));
        assertThat(rotating.keys().decryptionKeys()).containsExactly(Encryption.parseKey(OTHER_KEY), Encryption.parseKey(GO_KEY));
    }

    @Test
    void needsReEncryptionFlagsOldKeyAndOldFormatOnly() {
        var rotating = Encryption.of(KeyRotation.of(OTHER_KEY, GO_KEY));
        assertThat(rotating.needsReEncryption(GO_V1)).as("previous key").isTrue();
        assertThat(rotating.needsReEncryption("encrypted:" + GO_V1)).isTrue();
        assertThat(GO.needsReEncryption(GO_V0)).as("v0 under the current key").isTrue();
        assertThat(GO.needsReEncryption(GO_V1)).as("v1 under the current key").isFalse();
        assertThat(rotating.needsReEncryption(rotating.encrypt("fresh"))).isFalse();
        assertThat(GO.needsReEncryption("encrypted:" + Base64.getEncoder().encodeToString(new byte[40]))).as("unreadable envelope").isTrue();
        for (var notInline : new String[]{"", "aws-sm://x", "literal:x", "hunter2", "abcd", "encrypted:not base64"}) {
            assertThat(GO.needsReEncryption(notInline)).as(notInline).isFalse();
        }
    }

    @Test
    void reEncryptMigratesToTheCurrentKeyKeepingTheColumnShape() {
        var rotating = Encryption.of(KeyRotation.of(OTHER_KEY, GO_KEY));
        var bare = rotating.reEncrypt(GO_V0).orElseThrow();
        assertThat(bare).doesNotStartWith("encrypted:");
        assertThat(Encryption.withKey(OTHER_KEY).decrypt(bare)).isEqualTo(new Plaintext(GO_PLAINTEXT));
        assertThat(rotating.needsReEncryption(bare)).isFalse();

        var prefixed = rotating.reEncrypt("encrypted:" + GO_V1).orElseThrow();
        assertThat(prefixed).startsWith("encrypted:");
        assertThat(Encryption.withKey(OTHER_KEY).decrypt(prefixed)).isEqualTo(new Plaintext(GO_PLAINTEXT));

        for (var untouched : new String[]{"", "aws-sm://x", "literal:x", "hunter2", "encrypted:not base64"}) {
            assertThat(rotating.reEncrypt(untouched)).as(untouched).isEmpty();
        }
        assertThat(Encryption.withKey(OTHER_KEY).reEncrypt(GO_V1)).as("no key opens it").isEmpty();
    }

    // ── §3 keyed hashing (verify-only secrets) ──────────────────────────

    /// Golden vector (`docs/spec/encryption.md` §3): key bytes 0x00..0x1f,
    /// plaintext `client-secret-golden` — pinned so the Go port's identical
    /// vector can be compared byte for byte.
    static final String HASH_KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";
    static final String HASH_PLAINTEXT = "client-secret-golden";
    static final String HASH_GOLDEN = "hashed:v1:HhInGB9kwvg6VsfBL0oHER0eslXRAg6GBwoTsRa2D4E=";

    /// Pins the exact bytes. Mutant (c) — hashing with plain SHA-256 instead
    /// of the keyed HMAC — fails this directly: SHA-256("client-secret-golden")
    /// is a different 32 bytes than HMAC-SHA256(key, "client-secret-golden").
    @Test
    void hashSecretRefReproducesTheGoldenVector() {
        assertThat(Encryption.withKey(HASH_KEY).hashSecretRef(HASH_PLAINTEXT)).isEqualTo(HASH_GOLDEN);
    }

    @Test
    void verifySecretMatchesTheGoldenVectorAndRejectsAWrongSecret() {
        var enc = Encryption.withKey(HASH_KEY);
        assertThat(enc.verifySecret(HASH_GOLDEN, HASH_PLAINTEXT)).isEqualTo(new Matched(false));
        assertThat(enc.verifySecret(HASH_GOLDEN, "wrong")).isEqualTo(new NoMatch());
    }

    @Test
    void hashSecretRefIsDeterministicUnlikeEncrypt() {
        // Unlike #encrypt (fresh random nonce every call), the same plaintext
        // under the same key always hashes to the same string — that IS the
        // point: nothing per-call to defeat a stored-value comparison.
        assertThat(GO.hashSecretRef("same")).isEqualTo(GO.hashSecretRef("same"));
        assertThat(GO.hashSecretRef("same")).isNotEqualTo(GO.hashSecretRef("different"));
    }

    @Test
    void verifySecretAcceptsBothTheHashedAndTheLegacyEncryptedShape() {
        var legacyRef = GO.encryptSecretRef("legacy-secret");
        assertThat(GO.verifySecret(legacyRef, "legacy-secret"))
                .as("a legacy encrypted: ref matches by decrypt-and-compare, and always migrates")
                .isEqualTo(new Matched(true));
        assertThat(GO.verifySecret(legacyRef, "wrong")).isEqualTo(new NoMatch());

        var hashedRef = GO.hashSecretRef("hashed-secret");
        assertThat(GO.verifySecret(hashedRef, "hashed-secret"))
                .as("already hashed under the current key: no migration needed")
                .isEqualTo(new Matched(false));
        assertThat(GO.verifySecret(hashedRef, "wrong")).isEqualTo(new NoMatch());
    }

    @Test
    void verifySecretOnAnEmptyOrMalformedRefNeverMatches() {
        assertThat(GO.verifySecret("", "anything")).isEqualTo(new NoMatch());
        assertThat(GO.verifySecret("hashed:v1:not base64!", "anything")).isEqualTo(new NoMatch());
        // 16 bytes: not a HmacSHA256-length MAC — malformed, not a match.
        assertThat(GO.verifySecret("hashed:v1:" + Base64.getEncoder().encodeToString(new byte[16]), "anything"))
                .isEqualTo(new NoMatch());
    }

    @Test
    void decryptOfAHashedRefFailsWithItsOwnReasonNotAnException() {
        var hashedRef = GO.hashSecretRef("x");
        assertThat(GO.decrypt(hashedRef)).isEqualTo(new Failed(Reason.HASHED));
    }

    @Test
    void needsReEncryptionAndReEncryptLeaveHashedRefsAlone() {
        // Hashed refs migrate lazily at verify time (#verifySecret), never
        // through the AES-GCM rotation batch job.
        var hashedRef = GO.hashSecretRef("x");
        assertThat(GO.needsReEncryption(hashedRef)).isFalse();
        assertThat(GO.reEncrypt(hashedRef)).isEmpty();
    }

    /// Key rotation: a ref hashed under the previous key still verifies, is
    /// flagged for rehashing, and the rehashed form verifies under the
    /// current key alone (no previous key needed any more).
    @Test
    void keyRotationVerifiesAHashUnderThePreviousKeyAndFlagsItForRehash() {
        var previousOnly = Encryption.withKey(OTHER_KEY);
        var hashedUnderPrevious = previousOnly.hashSecretRef("rotate-me");

        // The current key alone does not recognise a hash sealed under a
        // wholly different key.
        assertThat(GO.verifySecret(hashedUnderPrevious, "rotate-me")).isEqualTo(new NoMatch());

        // Rotating (current = GO_KEY, previous = OTHER_KEY) verifies it via
        // the previous key — same key order [#decrypt] uses — and flags it.
        var rotating = Encryption.of(KeyRotation.of(GO_KEY, OTHER_KEY));
        assertThat(rotating.verifySecret(hashedUnderPrevious, "rotate-me")).isEqualTo(new Matched(true));

        // Rehashing under the current key drops the dependency on the previous one.
        var rehashed = rotating.hashSecretRef("rotate-me");
        assertThat(GO.verifySecret(rehashed, "rotate-me")).isEqualTo(new Matched(false));
    }

    @Test
    void hashedOutcomeDoesNotPrintTheMac() {
        assertThat(new SecretRef.Hashed(new byte[32]).toString()).doesNotContain("0, 0, 0");
    }

    // ── §1 keys / fromEnv ──────────────────────────────────────────────

    @Test
    void generateKeyIsThirtyTwoBytesPaddedBase64AndUsable() {
        var key = Encryption.generateKey();
        assertThat(key).hasSize(44).matches("^[A-Za-z0-9+/]{43}=$");
        assertThat(Base64.getDecoder().decode(key)).hasSize(32);
        var enc = Encryption.withKey(key);
        assertThat(enc.decrypt(enc.encrypt("k"))).isEqualTo(new Plaintext("k"));
        assertThat(Encryption.generateKey()).isNotEqualTo(key);
    }

    @Test
    void keysMustBeExactlyThirtyTwoBase64Bytes() {
        assertThatThrownBy(() -> Encryption.withKey("AAAAAAAAAAAAAAAAAAAAAA=="))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("32 bytes, got 16");
        assertThatThrownBy(() -> Encryption.withKey("not base64")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KeyRotation.of(GO_KEY, "short"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageStartingWith("previous key");
    }

    @Test
    void fromEnvIsDisabledWithoutACurrentKey() {
        assertThat(Encryption.fromEnv(new EnvReader(Map.of()))).isEmpty();
        assertThat(Encryption.fromEnv(new EnvReader(Map.of("FLOWCATALYST_APP_KEY", "", "FLOWCATALYST_APP_KEY_PREVIOUS", GO_KEY)))).isEmpty();
    }

    @Test
    void fromEnvReadsCurrentAndOptionalPreviousKeysStripped() {
        var single = Encryption.fromEnv(new EnvReader(Map.of("FLOWCATALYST_APP_KEY", GO_KEY + "\n"))).orElseThrow();
        assertThat(single.keys()).isInstanceOf(KeyRotation.Single.class);
        assertThat(single.decrypt(GO_V1)).isEqualTo(new Plaintext(GO_PLAINTEXT));

        var rotating = Encryption.fromEnv(new EnvReader(Map.of(
                "FLOWCATALYST_APP_KEY", OTHER_KEY, "FLOWCATALYST_APP_KEY_PREVIOUS", " " + GO_KEY + " "))).orElseThrow();
        assertThat(rotating.keys()).isEqualTo(new KeyRotation.Rotating(Encryption.parseKey(OTHER_KEY), Encryption.parseKey(GO_KEY)));
        assertThat(rotating.decrypt(GO_V1)).isEqualTo(new Plaintext(GO_PLAINTEXT));
    }

    @Test
    void aMalformedConfiguredKeyIsFatalNotSilentlyDisabled() {
        assertThatThrownBy(() -> Encryption.fromEnv(new EnvReader(Map.of("FLOWCATALYST_APP_KEY", "junk"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Encryption.fromEnv(new EnvReader(Map.of("FLOWCATALYST_APP_KEY", GO_KEY, "FLOWCATALYST_APP_KEY_PREVIOUS", "junk"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageStartingWith("previous key");
    }

    @Test
    void plaintextOutcomeDoesNotPrintTheSecret() {
        assertThat(new Plaintext("hunter2").toString()).doesNotContain("hunter2");
    }

    // ── §3 the external-scheme list is closed, and rejects (ruling 2026-09-08) ──

    @Test
    void anUnknownSchemeIsRejectedOnWriteNotSealedAsThoughItWereTheSecret() {
        // The whole message, as Go writes it: the list names each scheme in its
        // "aws-sm://" form so a typo like "aws-smm://" reads against the right
        // spelling (a contains-"aws-sm" check passed on the typo itself).
        assertThatThrownBy(() -> GO.encryptSecretRef("aws-smm://prod/db-password"))
                .isInstanceOf(Encryption.UnsupportedSchemeException.class)
                .hasMessage("unsupported secret-manager scheme \"aws-smm://\"; supported: aws-sm://, aws-ps://, "
                        + "gcp-sm://, vault://, env:// (prefix the value with \"encrypt:\" to store it as an "
                        + "encrypted plaintext secret instead)");
        // The point of the ruling: it must NOT come back as an envelope.
        assertThatThrownBy(() -> GO.encryptSecretRef("vaultt://secret/x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"aws-sm", "aws-ps", "gcp-sm", "vault", "env"})
    void everySupportedSchemeStillPassesThroughVerbatim(String scheme) {
        var ref = scheme + "://prod/db-password";
        assertThat(GO.encryptSecretRef(ref)).isEqualTo(ref);
    }

    @Test
    void theEncryptDirectiveIsTheOverrideForASecretThatLooksLikeAUrl() {
        var stored = GO.encryptSecretRef("encrypt:foo://bar");
        assertThat(stored).startsWith("encrypted:");
        assertThat(GO.decrypt(stored)).isEqualTo(new Plaintext("foo://bar"));
    }

    @Test
    void aSecretThatMerelyContainsTheSeparatorIsNotASchemeAndIsEncrypted() {
        // "p@ss" is not an RFC 3986 scheme token, so this is a password, not a reference.
        var stored = GO.encryptSecretRef("p@ss://word");
        assertThat(stored).startsWith("encrypted:");
        assertThat(GO.decrypt(stored)).isEqualTo(new Plaintext("p@ss://word"));
    }

    @Test
    void theRejectionIsWriteSideOnlySoRowsAlreadyStoredKeepReading() {
        // parse() is unchanged: an unknown scheme is still Plain on the read path...
        assertThat(SecretRef.parse("foo://bar")).isEqualTo(new SecretRef.Plain("foo://bar"));
        // ...and decrypt answers exactly what it did before the ruling.
        assertThat(GO.decrypt("foo://bar")).isEqualTo(new Failed(Reason.NOT_ENCRYPTED));
    }
}
