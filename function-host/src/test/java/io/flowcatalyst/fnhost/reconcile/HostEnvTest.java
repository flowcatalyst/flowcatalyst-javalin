package io.flowcatalyst.fnhost.reconcile;

import io.flowcatalyst.platform.function.Digest;
import io.flowcatalyst.platform.function.DnsLabel;
import io.flowcatalyst.platform.function.artifact.SignatureVerifier;
import io.flowcatalyst.platform.function.artifact.Signatures;
import io.flowcatalyst.platform.function.artifact.TestSigstore;
import io.flowcatalyst.platform.function.artifact.Verification;
import io.flowcatalyst.server.EnvReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// [HostEnv] (`docs/spec/function-host-reconciler.md` §1.4).
class HostEnvTest {

    private static final Map<String, String> REQUIRED = Map.of(
            "FC_FN_PLATFORM_URL", "http://platform.example",
            "FC_FN_CLIENT_ID", "client-1",
            "FC_FN_CLIENT_SECRET", "secret-1");

    private static HostEnv load(Map<String, String> env) {
        return HostEnv.load(new EnvReader(env));
    }

    @Test
    void defaultsPoolMaxLoadedAndCacheDirWhenEverythingRequiredIsSet() {
        HostEnv env = load(REQUIRED);
        assertThat(env.pool()).isEqualTo(new DnsLabel("default"));
        assertThat(env.maxLoaded()).isEqualTo(200);
        assertThat(env.cacheDir().toString()).contains("fc-fn-cache");
        assertThat(env.signatures()).isInstanceOf(Signatures.Required.class);
    }

    @Test
    void missingRequiredValuesFailWithOneMessageNamingAllOfThem() {
        assertThatThrownBy(() -> load(Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FC_FN_PLATFORM_URL")
                .hasMessageContaining("FC_FN_CLIENT_ID")
                .hasMessageContaining("FC_FN_CLIENT_SECRET");
    }

    @Test
    void missingOnlyOneRequiredValueNamesOnlyThatOne() {
        var partial = new java.util.HashMap<>(REQUIRED);
        partial.remove("FC_FN_CLIENT_SECRET");
        assertThatThrownBy(() -> load(partial))
                .hasMessageContaining("FC_FN_CLIENT_SECRET")
                .as("mutant: report every field as missing even when only one is")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("FC_FN_PLATFORM_URL")
                        .doesNotContain("FC_FN_CLIENT_ID,"));
    }

    @Test
    void anExplicitlySetInvalidHostIdFailsFast() {
        var withBadHostId = new java.util.HashMap<>(REQUIRED);
        withBadHostId.put("FC_FN_HOST_ID", "has a space");
        assertThatThrownBy(() -> load(withBadHostId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FC_FN_HOST_ID");
    }

    @Test
    void anExplicitHostIdIsUsedVerbatim() {
        var withHostId = new java.util.HashMap<>(REQUIRED);
        withHostId.put("FC_FN_HOST_ID", "my-host-1");
        assertThat(load(withHostId).hostId()).isEqualTo("my-host-1");
    }

    @Test
    void anUnsetHostIdGetsAGeneratedDefaultMatchingTheHeartbeatsOwnRule() {
        String hostId = load(REQUIRED).hostId();
        assertThat(hostId).matches("^[A-Za-z0-9._:-]{1,100}$");
    }

    @Test
    void offRequiresDevModeSameRuleAsThePlatform() {
        var offNoDevMode = new java.util.HashMap<>(REQUIRED);
        offNoDevMode.put("FC_FN_SIGNATURES", "off");
        assertThatThrownBy(() -> load(offNoDevMode))
                .as("mutant: accept FC_FN_SIGNATURES=off without dev mode")
                .isInstanceOf(IllegalStateException.class);

        offNoDevMode.put("FLOWCATALYST_DEV_MODE", "true");
        assertThat(load(offNoDevMode).signatures()).isInstanceOf(Signatures.Off.class);
    }

    @Test
    void toStringMasksTheClientSecret() {
        assertThat(load(REQUIRED).toString()).doesNotContain("secret-1");
    }

    // ── FC_FN_TRUST_ROOT (spec §1.4, §0): the same resolution the platform's
    //    own composition root uses ([Signatures#resolve(SignaturesMode,boolean,String)]) ──

    @Test
    void unsetTrustRootUsesThePublicGoodRootWhichCannotVerifyAPrivateEcosystemsBundle() throws Exception {
        Instant now = Instant.now();
        TestSigstore.Ecosystem eco = TestSigstore.build(TestSigstore.LeafSpec.valid(now.minusSeconds(60), now.plusSeconds(3600)));
        byte[] digest = randomDigestBytes();
        String bundle = TestSigstore.validBundleJson(eco, digest, now, 1L);

        HostEnv env = load(REQUIRED); // FC_FN_TRUST_ROOT unset
        assertThat(env.signatures()).isInstanceOf(Signatures.Required.class);
        SignatureVerifier verifier = ((Signatures.Required) env.signatures()).verifier();

        Verification result = verifier.verify(bundle, Digest.parse("sha256:" + HexFormat.of().formatHex(digest)));
        assertThat(result).as("mutant: FC_FN_TRUST_ROOT unset must resolve to the committed public-good root, "
                        + "which cannot verify a bundle signed under an unrelated private ecosystem")
                .isInstanceOf(Verification.Rejected.class);
    }

    @Test
    void setTrustRootPathIsTheRootThatActuallyVerifies(@TempDir Path dir) throws Exception {
        Instant now = Instant.now();
        TestSigstore.Ecosystem eco = TestSigstore.build(TestSigstore.LeafSpec.valid(now.minusSeconds(60), now.plusSeconds(3600)));
        Path trustRootFile = dir.resolve("trusted_root.json");
        Files.writeString(trustRootFile, trustedRootJson(eco, now.minusSeconds(3600)));

        var withTrustRoot = new java.util.HashMap<>(REQUIRED);
        withTrustRoot.put("FC_FN_TRUST_ROOT", trustRootFile.toString());
        HostEnv env = load(withTrustRoot);

        assertThat(env.signatures()).isInstanceOf(Signatures.Required.class);
        SignatureVerifier verifier = ((Signatures.Required) env.signatures()).verifier();

        byte[] digest = randomDigestBytes();
        String bundle = TestSigstore.validBundleJson(eco, digest, now, 1L);
        Verification result = verifier.verify(bundle, Digest.parse("sha256:" + HexFormat.of().formatHex(digest)));

        assertThat(result).as("mutant: ignore FC_FN_TRUST_ROOT and always resolve the public-good root")
                .isInstanceOf(Verification.Verified.class);
    }

    @Test
    void aTrustRootPathThatDoesNotExistFailsStartupNamingTheVariable() {
        var badPath = new java.util.HashMap<>(REQUIRED);
        badPath.put("FC_FN_TRUST_ROOT", "/no/such/path/trusted_root.json");
        assertThatThrownBy(() -> load(badPath))
                .as("mutant: swallow the missing file and silently fall back to the public-good root")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FC_FN_TRUST_ROOT");
    }

    // ── fixtures ──────────────────────────────────────────────────────────

    private static byte[] randomDigestBytes() {
        byte[] digest = new byte[32];
        new SecureRandom().nextBytes(digest);
        return digest;
    }

    /// A minimal `trusted_root.json` document ([io.flowcatalyst.platform.function.artifact.TrustRoot#parse]'s
    /// own shape) built straight from a [TestSigstore.Ecosystem] — there is
    /// no JSON-producing helper on `TestSigstore` itself (every other test
    /// hands the ecosystem's [io.flowcatalyst.platform.function.artifact.TrustRoot]
    /// straight to a [SignatureVerifier], never through a file), so this
    /// mirrors [TestSigstore.Ecosystem#trustRootFor]'s own construction
    /// (root cert DER as the sole CA in the chain, the log key's SPKI with
    /// its sha256 as `keyId`) but serialised, because [HostEnv] reads
    /// `FC_FN_TRUST_ROOT` as a FILE PATH, not an in-memory `TrustRoot`.
    private static String trustedRootJson(TestSigstore.Ecosystem eco, Instant caFrom) throws Exception {
        byte[] rootDer = eco.rootCert().getEncoded();
        byte[] logSpki = eco.logKey().getPublic().getEncoded();
        byte[] keyId = MessageDigest.getInstance("SHA-256").digest(logSpki);
        return """
                {"certificateAuthorities":[{"certChain":{"certificates":[{"rawBytes":"%s"}]},"validFor":{"start":"%s"}}],
                 "tlogs":[{"logId":{"keyId":"%s"},"publicKey":{"rawBytes":"%s","validFor":{"start":"%s"}}}]}
                """.formatted(b64(rootDer), caFrom, b64(keyId), b64(logSpki), caFrom);
    }

    private static String b64(byte[] data) {
        return Base64.getEncoder().encodeToString(data);
    }
}
