package io.flowcatalyst.platform.shared.encryption;

import java.util.Objects;

/// Outcome of [Encryption#decrypt(String)] — an outcome, not an exception,
/// because "not inline", "no secret" and "no key opens it" are routine at a
/// read boundary (`docs/spec/encryption.md` §4). Callers switch exhaustively.
public sealed interface Decryption permits Decryption.Plaintext, Decryption.External, Decryption.Failed {

    /// The secret in clear: an envelope the current or previous key opened, or a `literal:`.
    record Plaintext(String value) implements Decryption {
        public Plaintext {
            Objects.requireNonNull(value, "value");
        }

        @Override
        public String toString() {
            return "Plaintext[***]";
        }
    }

    /// Not inline: resolve `ref` through its secret-manager provider.
    record External(SecretRef.External ref) implements Decryption {
        public External {
            Objects.requireNonNull(ref, "ref");
        }
    }

    /// Nothing usable came out; `reason` says why.
    record Failed(Reason reason) implements Decryption {
        public Failed {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /// Why a stored value could not be decrypted.
    enum Reason {
        /// Blank — no secret stored.
        EMPTY,
        /// A bare value that is not an envelope at all (not base64, or shorter than the smallest
        /// envelope): plaintext was stored.
        NOT_ENCRYPTED,
        /// Claims to be an envelope (`encrypted:` prefix) but the payload is not base64 or is
        /// shorter than the smallest envelope. A bare value never reports this — without the
        /// prefix, "too short" means plaintext ([#NOT_ENCRYPTED]).
        MALFORMED,
        /// A well-formed envelope neither the current nor the previous key authenticates.
        NO_MATCHING_KEY,
        /// `hashed:v1:` — a keyed-hash MAC, one-way by design: there is no
        /// plaintext to recover. Verify it with
        /// [Encryption#verifySecret(String, String)] instead.
        HASHED
    }
}
