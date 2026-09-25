package io.flowcatalyst.platform.principal.operations;

import io.flowcatalyst.platform.shared.SecureTokens;
import io.flowcatalyst.platform.shared.encryption.Encryption;
import io.flowcatalyst.sdk.usecase.UseCaseException;

import java.util.Objects;

/// Mints and encrypts a developer client secret (spec §2). **Minting only** —
/// there is no stash, and that absence is the design.
///
/// ### Why there is no stash
///
/// The plaintext must reach the HTTP response exactly once, and events never
/// carry secrets, so it used to be parked in a process-local map for the
/// handler to pop after the commit. That map was written from **inside** the
/// operation's `execute`, which runs *before* the plan is committed: a commit
/// that then failed left a plaintext in memory for two minutes belonging to a
/// credential that was never stored. Nothing could deliver it — the handler
/// only pops after a successful `run` — but a side channel that can hold a
/// secret for a rollback is one refactor away from delivering it.
///
/// The caller now mints first, keeps the plaintext in a local variable, and
/// passes only the **encrypted** form into the command. The plaintext never
/// enters the operation, so it cannot outlive the request, and the ordering
/// invariant is structural rather than a rule someone has to remember: a
/// response can only carry a secret the caller itself minted, and the caller
/// only builds a response when `run` returned.
///
/// One instance per composition root; `toString` never prints a secret.
public final class DeveloperSecrets {

    private static final int SECRET_BYTES = 32;

    private final Encryption encryption; // null = app key not configured

    private DeveloperSecrets(Encryption encryption) {
        this.encryption = encryption;
    }

    /// Secrets encrypted under the platform app key.
    public static DeveloperSecrets withEncryption(Encryption encryption) {
        return new DeveloperSecrets(Objects.requireNonNull(encryption, "encryption"));
    }

    /// No app key: issuing a secret fails with 500 `SECRET`.
    public static DeveloperSecrets unconfigured() {
        return new DeveloperSecrets(null);
    }

    /// A freshly issued secret: the plaintext (returned once) and its
    /// at-rest keyed-hash form (`docs/spec/encryption.md` §3 — a developer
    /// client secret is verify-only, so it is stored `hashed:v1:…`, never
    /// reversibly encrypted).
    public record Issued(String plaintext, String hashedRef) {
        @Override
        public String toString() {
            return "Issued[***]";
        }
    }

    /// Mints 32 random bytes → base64url (no padding) and hashes them.
    ///
    /// The caller keeps [Issued#plaintext] and hands [Issued#hashedRef] to
    /// the operation — see the class doc for why the plaintext does not travel
    /// the other way.
    ///
    /// @throws UseCaseException internal `SECRET` when no app key is configured
    public Issued issue() {
        if (encryption == null) {
            throw UseCaseException.internal("SECRET", "FLOWCATALYST_APP_KEY not configured; cannot hash developer client secret", null);
        }
        String plaintext = SecureTokens.urlSafe(SECRET_BYTES);
        return new Issued(plaintext, encryption.hashSecretRef(plaintext));
    }

    @Override
    public String toString() {
        return "DeveloperSecrets[configured=" + (encryption != null) + "]";
    }
}
