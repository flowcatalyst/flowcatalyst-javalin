package io.flowcatalyst.platform.function;

import java.util.Objects;

/// The keyless signer that published a version (spec
/// `function-registry.md` §6.2): the OIDC issuer and subject. May be absent
/// as a whole — a version published with signatures off; when present both
/// components are required. `ClientPolicy.permits` (spec §6.4) compares them
/// **exactly** — no pattern, no case folding, no trimming.
///
/// @param issuer  the OIDC issuer, e.g. `https://token.actions.githubusercontent.com`
/// @param subject the OIDC subject claim
public record SignerIdentity(String issuer, String subject) {
    public SignerIdentity {
        Objects.requireNonNull(issuer, "issuer");
        Objects.requireNonNull(subject, "subject");
    }
}
