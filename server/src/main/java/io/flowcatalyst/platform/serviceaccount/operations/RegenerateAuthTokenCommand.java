package io.flowcatalyst.platform.serviceaccount.operations;

/// The input DTO for [RegenerateAuthToken] (audit `operation` =
/// `RegenerateAuthTokenCommand`). The minted plaintext travels back through a
/// **caller-owned sink** passed separately to [RegenerateAuthToken#of] — never
/// through this record, which is serialised verbatim into `aud_logs.operation_json`
/// (spec §5; mirrors `principal.operations.SetDeveloperCredential`, which keeps
/// its `disclose` consumer off `SetDeveloperCredentialCommand` for the same reason).
public record RegenerateAuthTokenCommand(String serviceAccountId) {
}
