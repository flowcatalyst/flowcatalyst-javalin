package io.flowcatalyst.platform.function;

import io.flowcatalyst.sdk.usecase.HasId;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/// A client's allowed keyless signers and per-client resource ceilings (spec
/// `function-registry.md` §6.4). Natural key — no TSID; `client_id` is the
/// primary key of `fn_client_policies`, so [#id] simply returns it.
///
/// @param clientId        the client this policy governs
/// @param signers         allowed signer identities, each scoped to a set of runtimes
/// @param maxDurationMs   ceiling override; `null` = the platform default applies
/// @param maxConcurrency  ceiling override; `null` = the platform default applies
/// @param maxWasmMemoryMb ceiling override; `null` = the platform default applies
/// @param maxDbPoolSize   ceiling override; `null` = the platform default applies
/// @param createdAt       creation time
/// @param updatedAt       last change
public record ClientPolicy(
        String clientId,
        List<SignerRule> signers,
        Integer maxDurationMs,
        Integer maxConcurrency,
        Integer maxWasmMemoryMb,
        Integer maxDbPoolSize,
        Instant createdAt,
        Instant updatedAt) implements HasId {

    public ClientPolicy {
        Objects.requireNonNull(clientId, "clientId");
        signers = List.copyOf(signers);
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    @Override
    public String id() {
        return clientId;
    }

    /// One allowed keyless signer, scoped to the runtimes it may publish for
    /// (spec §6.4).
    ///
    /// @param issuer   the OIDC issuer, compared exactly
    /// @param subject  the OIDC subject, compared exactly
    /// @param runtimes the runtimes this signer may publish for
    public record SignerRule(String issuer, String subject, Set<Runtime> runtimes) {
        public SignerRule {
            Objects.requireNonNull(issuer, "issuer");
            Objects.requireNonNull(subject, "subject");
            runtimes = Set.copyOf(runtimes);
        }
    }

    /// Whether some rule permits `identity` to publish for `runtime`: an
    /// **equal** issuer, an **equal** subject, and `runtime` in that rule's
    /// set — no patterns, no case folding, no trimming (spec §6.4, §8 M15).
    /// `null` identity permits nothing; an empty signer list permits nothing.
    public boolean permits(SignerIdentity identity, Runtime runtime) {
        if (identity == null) {
            return false;
        }
        for (SignerRule rule : signers) {
            if (rule.issuer().equals(identity.issuer()) && rule.subject().equals(identity.subject())
                    && rule.runtimes().contains(runtime)) {
                return true;
            }
        }
        return false;
    }

    /// Resolves each ceiling against the platform default (spec §4.6): the
    /// policy's column when set, else the platform default — so out of the
    /// box a function may lower a limit but never raise one.
    public ClientCeilings ceilings(FunctionLimits defaults) {
        Objects.requireNonNull(defaults, "defaults");
        return new ClientCeilings(
                maxDurationMs != null ? maxDurationMs : defaults.maxDurationMs(),
                maxConcurrency != null ? maxConcurrency : defaults.maxConcurrency(),
                maxWasmMemoryMb != null ? maxWasmMemoryMb : defaults.wasmMemoryMb(),
                maxDbPoolSize != null ? maxDbPoolSize : defaults.dbPoolSize());
    }
}
