package io.flowcatalyst.server.dbsecret;

import io.flowcatalyst.platform.shared.json.Json;
import tools.jackson.core.JacksonException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;

import java.util.Objects;

/// Fetches + parses the RDS-style secret (`username`/`password`/`port`) from
/// a [SecretSource] (spec §2).
public final class DbSecretFetcher {

    private DbSecretFetcher() {
    }

    /// The production [SecretSource]: a [SecretsManagerClient] built once for
    /// `arn`'s own region when it has one ([DbSecretDsn#regionFromArn]),
    /// falling back to the SDK's default region chain for a bare secret name
    /// (spec §2). Built once and reused across refresh ticks — never rebuilt
    /// per fetch.
    public static SecretSource aws(String arn) {
        var builder = SecretsManagerClient.builder();
        var region = DbSecretDsn.regionFromArn(arn);
        if (!region.isEmpty()) {
            builder.region(Region.of(region));
        }
        var client = builder.build();
        return a -> client.getSecretValue(GetSecretValueRequest.builder().secretId(a).build()).secretString();
    }

    /// The subset of the secret this port reads: `host`/`dbName` come from
    /// `DB_HOST`/`DB_NAME`, never from the secret.
    public record Credentials(String username, String password, int port) {
        public Credentials {
            Objects.requireNonNull(username, "username");
            Objects.requireNonNull(password, "password");
        }
    }

    /// Fetches and parses the secret at `arn` from `source`. Error messages
    /// are exactly the spec's (§2):
    ///
    ///   - `"secret <arn> has no string value"` — no `SecretString` (a
    ///     binary-only secret, or [SecretSource#secretString] returned `null`);
    ///   - `"parse secret <arn> JSON"` — the string value is not the expected
    ///     JSON shape (cause chained);
    ///   - `"secret <arn> is missing username/password"` — parsed but either
    ///     field is blank.
    public static Credentials fetch(SecretSource source, String arn) {
        var raw = source.secretString(arn);
        if (raw == null) {
            throw new IllegalStateException("secret " + arn + " has no string value");
        }
        SecretJson parsed;
        try {
            parsed = Json.MAPPER.readValue(raw, SecretJson.class);
        } catch (JacksonException e) {
            throw new IllegalStateException("parse secret " + arn + " JSON", e);
        }
        if (parsed == null || isBlank(parsed.username()) || isBlank(parsed.password())) {
            throw new IllegalStateException("secret " + arn + " is missing username/password");
        }
        var port = parsed.port() == null ? 0 : parsed.port();
        return new Credentials(parsed.username(), parsed.password(), port);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /// The secret JSON's wire shape (spec §2): `{username, password, port?}`.
    private record SecretJson(String username, String password, Integer port) {
    }
}
