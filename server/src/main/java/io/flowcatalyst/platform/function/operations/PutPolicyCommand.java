package io.flowcatalyst.platform.function.operations;

import io.flowcatalyst.platform.function.FunctionOwner;

import java.util.List;
import java.util.Objects;

/// `PutFunctionPolicy`'s command — a full replacement (spec
/// `function-api.md` §4.3). A ceiling `null` ⇒ the platform default applies.
public record PutPolicyCommand(FunctionOwner owner, List<SignerInput> signers, Integer maxDurationMs,
                               Integer maxConcurrency, Integer maxWasmMemoryMb, Integer maxDbPoolSize) {

    public PutPolicyCommand {
        Objects.requireNonNull(owner, "owner");
        signers = signers == null ? List.of() : List.copyOf(signers);
    }

    /// One signer rule as given on the wire — `runtimes` still raw strings
    /// (parsed/validated by the operation).
    public record SignerInput(String issuer, String subject, List<String> runtimes) {
        public SignerInput {
            runtimes = runtimes == null ? List.of() : List.copyOf(runtimes);
        }
    }
}
