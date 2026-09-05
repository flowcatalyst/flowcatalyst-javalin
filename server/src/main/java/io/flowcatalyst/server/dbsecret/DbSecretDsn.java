package io.flowcatalyst.server.dbsecret;

import io.flowcatalyst.server.Env;

/// Pure DSN/ARN string helpers (spec §2). No env/network reads, so the
/// parity-critical bits — port precedence, password URL-escaping, and
/// host-already-has-port — are unit-testable without a fetch.
public final class DbSecretDsn {

    /// `DB_NAME` default when none is configured.
    static final String DEFAULT_DB_NAME = "flowcatalyst";

    /// Port used when neither the secret nor `DB_PORT` supplies one.
    static final int DEFAULT_PORT = 5432;

    private DbSecretDsn() {
    }

    /// Extracts the region from an ARN of the form
    /// `arn:partition:service:REGION:account:resource`. `""` when `s` is not
    /// an ARN with a region segment (e.g. a bare secret name).
    public static String regionFromArn(String s) {
        var parts = s.split(":", -1);
        if (parts.length < 4 || !parts[0].equals("arn")) {
            return "";
        }
        return parts[3];
    }

    /// Assembles the Postgres DSN from the secret's credentials plus the
    /// env-supplied host/name/port (spec §2).
    ///
    /// @param username   the secret's `username`
    /// @param password   the secret's `password`, URL-encoded into the DSN
    ///                    via [Env#queryEscape]
    /// @param host       `DB_HOST`; used as-is when it already contains `:`
    /// @param secretPort the secret's own `port`, or `0`/negative when the
    ///                    secret carries none
    /// @param dbPort     raw `DB_PORT` (`""` when unset)
    /// @param dbName     the database name; `""`/`null` defaults to
    ///                    [#DEFAULT_DB_NAME]
    public static String build(String username, String password, String host, int secretPort, String dbPort,
            String dbName) {
        var name = (dbName == null || dbName.isEmpty()) ? DEFAULT_DB_NAME : dbName;
        var port = (dbPort == null || dbPort.isEmpty()) ? String.valueOf(DEFAULT_PORT) : dbPort;
        if (secretPort > 0) {
            port = String.valueOf(secretPort);
        }
        var hostPort = host.contains(":") ? host : host + ":" + port;
        return "postgresql://" + username + ":" + Env.queryEscape(password) + "@" + hostPort + "/" + name;
    }
}
