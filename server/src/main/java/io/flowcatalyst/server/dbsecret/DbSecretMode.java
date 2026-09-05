package io.flowcatalyst.server.dbsecret;

import io.flowcatalyst.server.EnvReader;

import java.util.Objects;

/// Whether AWS-Secrets-Manager DB mode applies, resolved once from the
/// environment (spec `docs/spec/db-secret.md` §1).
///
/// Applicable only when **no** `FC_DATABASE_URL` / `DATABASE_URL` is set
/// **and** both `DB_SECRET_ARN` and `DB_HOST` are — otherwise the existing
/// `Env.databaseUrl()` path is unchanged ([NotApplicable]). `DB_SECRET_PROVIDER`
/// (default `aws`) is a startup error for any other value, thrown eagerly by
/// [#resolve] so `Main` fails before any subsystem starts.
public sealed interface DbSecretMode permits DbSecretMode.NotApplicable, DbSecretMode.Applicable {

    /// `DB_SECRET_REFRESH_INTERVAL_MS` default: 5 minutes (spec §3).
    int DEFAULT_REFRESH_INTERVAL_MS = 300_000;

    /// Resolves mode from `e`. Throws [IllegalStateException] when
    /// `DB_SECRET_PROVIDER` names an unsupported provider — the one error
    /// this can raise (spec §1).
    static DbSecretMode resolve(EnvReader e) {
        Objects.requireNonNull(e, "e");
        if (e.firstSet("FC_DATABASE_URL", "DATABASE_URL").isPresent()) {
            return NotApplicable.INSTANCE;
        }
        var arn = e.get("DB_SECRET_ARN");
        var host = e.get("DB_HOST");
        if (arn.isEmpty() || host.isEmpty()) {
            return NotApplicable.INSTANCE;
        }
        var provider = e.or("DB_SECRET_PROVIDER", "aws");
        if (!provider.equalsIgnoreCase("aws")) {
            throw new IllegalStateException(
                    "DB_SECRET_PROVIDER \"" + provider + "\" not supported (only \"aws\")");
        }
        var dbName = e.or("DB_NAME", "flowcatalyst");
        var dbPort = e.get("DB_PORT");
        var refreshIntervalMs = e.integer("DB_SECRET_REFRESH_INTERVAL_MS", DEFAULT_REFRESH_INTERVAL_MS);
        return new Applicable(arn, host, dbName, dbPort, refreshIntervalMs);
    }

    /// The env is not configured for Secrets-Manager DB mode.
    enum NotApplicable implements DbSecretMode {
        INSTANCE
    }

    /// Secrets-Manager DB mode applies.
    ///
    /// @param arn               `DB_SECRET_ARN`
    /// @param host              `DB_HOST`
    /// @param dbName            `DB_NAME`, defaulted to `flowcatalyst`
    /// @param dbPort            raw `DB_PORT` (`""` when unset — the default
    ///                          `5432` is applied by [DbSecretDsn#build],
    ///                          not here, since the secret's own `port` can
    ///                          still override it)
    /// @param refreshIntervalMs `DB_SECRET_REFRESH_INTERVAL_MS`, default
    ///                          [#DEFAULT_REFRESH_INTERVAL_MS]; `<= 0` disables
    ///                          the periodic refresh (spec §3)
    record Applicable(String arn, String host, String dbName, String dbPort, long refreshIntervalMs)
            implements DbSecretMode {
        public Applicable {
            Objects.requireNonNull(arn, "arn");
            Objects.requireNonNull(host, "host");
            Objects.requireNonNull(dbName, "dbName");
            Objects.requireNonNull(dbPort, "dbPort");
        }
    }
}
