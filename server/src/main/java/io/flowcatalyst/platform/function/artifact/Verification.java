package io.flowcatalyst.platform.function.artifact;

import io.flowcatalyst.platform.function.SignerIdentity;

import java.time.Instant;
import java.util.Objects;

/// The outcome of [SignatureVerifier#verify]: who signed the artifact and
/// when, or the reason the bundle did not establish that (spec
/// `function-artifacts.md` §3). A sealed outcome, not an exception
/// (`CONVENTIONS.md` §8, "outcomes at verification boundaries") — a bad
/// signature is a routine, expected outcome for a verifier, not an
/// infrastructure failure. [SignatureVerifier#verify] establishes **who
/// signed these bytes and when**; whether that signer may publish is the
/// caller's next line, `ClientPolicy.permits(signer, runtime)`.
public sealed interface Verification {

    /// `signedAt` is the tlog entry's `integratedTime` — the moment Rekor
    /// logged the signature, which is also the instant the certificate was
    /// validated at (spec §3.2 step 6), not the time [SignatureVerifier#verify]
    /// was called.
    record Verified(SignerIdentity signer, Instant signedAt) implements Verification {
        public Verified {
            Objects.requireNonNull(signer, "signer");
            Objects.requireNonNull(signedAt, "signedAt");
        }
    }

    /// `detail` is diagnostic text for logs — never used for control flow;
    /// callers switch on `reason`.
    record Rejected(Reason reason, String detail) implements Verification {
        public Rejected {
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(detail, "detail");
        }
    }

    /// Every way §3.2's checks can fail, one per named step so a caller (or
    /// a log line) can say exactly which. There is no configuration that
    /// skips a check — every bundle runs every step, fail closed.
    enum Reason {
        /// §3.1: the bundle is not the JSON shape a v0.3 bundle must be —
        /// missing or wrongly typed fields, bad base64, malformed JSON.
        MALFORMED_BUNDLE,
        /// §3.1: the bundle is a shape this verifier does not read — the
        /// wrong media type, a DSSE envelope, a certificate chain or a
        /// public-key hint in place of the single leaf certificate, zero or
        /// several `tlogEntries`, another entry kind/version, or a leaf key
        /// that is not P-256 EC.
        UNSUPPORTED_BUNDLE,
        /// Step 1: the bundle's `messageDigest` does not equal the digest
        /// the caller expected.
        DIGEST_MISMATCH,
        /// Step 7: the artifact digest does not verify against the leaf
        /// certificate's key under its own signature.
        BAD_SIGNATURE,
        /// Step 6: no pinned certificate authority both covers `integratedTime`
        /// in its window and validates the leaf's chain.
        UNTRUSTED_CERTIFICATE,
        /// Step 6: the chain is trusted, but the leaf certificate's own
        /// `notBefore`/`notAfter` does not contain `integratedTime`.
        CERTIFICATE_NOT_VALID_AT_SIGNING,
        /// Step 6: the leaf's extended key usage does not include code signing.
        NOT_A_CODE_SIGNING_CERTIFICATE,
        /// Step 8: the issuer extension, or exactly one URI/rfc822 SAN, is
        /// absent (or the SAN is not exactly one entry).
        IDENTITY_MISSING,
        /// The tlog entry is missing its `inclusionPromise` or its
        /// `inclusionProof`/checkpoint — present, right kind and version,
        /// but without the inclusion evidence §3.1 requires.
        TLOG_MISSING,
        /// Step 2: `logId.keyId` does not match a pinned transparency-log
        /// key whose validity window contains `integratedTime`.
        TLOG_UNKNOWN_LOG,
        /// Step 3: the log entry's body is not about this signature over
        /// this digest — a genuine entry for someone else's artifact.
        TLOG_ENTRY_MISMATCH,
        /// Step 4: the log key's signature over the canonicalised inclusion
        /// promise does not verify — this is what authenticates `integratedTime`.
        TLOG_PROMISE_INVALID,
        /// Step 5: the RFC 6962 audit path does not produce the checkpoint's root hash.
        TLOG_INCLUSION_INVALID,
        /// Step 5: the checkpoint's signed note does not verify under the
        /// log key, or its size/root do not match the inclusion proof's.
        TLOG_CHECKPOINT_INVALID
    }
}
