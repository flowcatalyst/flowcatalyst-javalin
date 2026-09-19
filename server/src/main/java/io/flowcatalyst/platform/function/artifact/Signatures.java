package io.flowcatalyst.platform.function.artifact;

import java.util.Objects;

/// The publish-time signature policy, chosen ONCE at startup from `Env`
/// (spec `function-api.md` §5.1 step 5) and threaded into `PublishVersion`.
/// Sealed so a caller's `switch` names both outcomes explicitly — there is no
/// third, half-configured state.
///
/// [#resolve] is the composition root's ONLY way to build one: `OFF` is
/// refused outright unless `devMode` is `true` — "signatures off" must be
/// impossible to reach on a production task definition by typo or by intent
/// (spec §5.1, §8 P9). [Required] is built with
/// [TrustRoot#sigstorePublicGood()] by the composition root, not here, so a
/// test can hand in its own [TrustRoot] (`TestSigstore`) without touching the
/// classpath resource.
public sealed interface Signatures {

    /// Every publish must carry a bundle that verifies under `verifier` and
    /// whose signer the owner's policy permits.
    record Required(SignatureVerifier verifier) implements Signatures {
        public Required {
            Objects.requireNonNull(verifier, "verifier");
        }
    }

    /// No signature is required; a bundle sent anyway is stored, unverified,
    /// and the version's signer is `null` (spec §5.1 step 5).
    record Off() implements Signatures {
    }

    /// `trustRoot` is a [java.util.function.Supplier] — not a plain
    /// [TrustRoot] — so [#OFF] never pays for (or requires) building one; a
    /// test asserting P9's refusal hands in a supplier that would fail if
    /// ever called.
    ///
    /// @throws IllegalStateException `mode` is [SignaturesMode#OFF] and
    ///                                `devMode` is `false` — the message
    ///                                names both `FC_FN_SIGNATURES` and
    ///                                `FLOWCATALYST_DEV_MODE` so the fix is
    ///                                obvious from the log line alone
    static Signatures resolve(SignaturesMode mode, boolean devMode, java.util.function.Supplier<TrustRoot> trustRoot) {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(trustRoot, "trustRoot");
        return switch (mode) {
            case REQUIRED -> new Required(new SignatureVerifier(trustRoot.get()));
            case OFF -> {
                if (!devMode) {
                    throw new IllegalStateException(
                            "FC_FN_SIGNATURES=off requires FLOWCATALYST_DEV_MODE=true; refusing to start "
                                    + "with function-publish signature verification disabled outside dev mode");
                }
                yield new Off();
            }
        };
    }

    /// [#resolve] with the platform's own Sigstore public-good [TrustRoot] —
    /// what the composition root calls; a test that wants a `TestSigstore`
    /// trust root calls the three-argument form directly.
    static Signatures resolve(SignaturesMode mode, boolean devMode) {
        return resolve(mode, devMode, TrustRoot::sigstorePublicGood);
    }
}
