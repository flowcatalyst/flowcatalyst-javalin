package io.flowcatalyst.server.dbsecret;

/// Seam over `SecretsManagerClient.getSecretValue` so [DbSecretFetcher] and
/// [DbSecretRefresher] are testable without a real AWS client (spec §2).
/// [DbSecretFetcher#aws] is the production implementation; tests supply a
/// stub.
@FunctionalInterface
public interface SecretSource {

    /// The secret's current string value for `arn` (`SecretString`), or
    /// `null` when the secret has none (a binary-only secret).
    String secretString(String arn);
}
