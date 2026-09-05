package io.flowcatalyst.fcdev;

import java.net.URI;
import java.net.URISyntaxException;

/// `mysqlDSN` (Go `outbox_create_table.go`), reshaped for `mysql-connector-j`
/// (`docs/spec/fcdev-commands.md` §3.1): `--db-url`/`FC_OUTBOX_DB_URL`
/// accepts a `mysql://user:pass@host[:port]/db?params` URL, converted to a
/// JDBC URL (port `3306` when absent, credentials as connection
/// properties); anything without `://` at all is taken as an
/// already-JDBC-shaped DSN body and gets `jdbc:mysql://` prefixed onto it
/// (there is no Go-style `user:pass@tcp(host:port)/db` DSN support —
/// `mysql-connector-j` only understands JDBC URLs); anything already
/// starting `jdbc:` passes through verbatim.
///
/// A host with no explicit port defaults to MySQL's standard `3306` — the
/// one behaviour [MysqlJdbcUrlTest] pins directly: drop that default and a
/// URL like `mysql://user:pass@localhost/app` would connect to whatever
/// `mysql-connector-j` assumes with no host at all, not port 3306.
final class MysqlJdbcUrl {

    static final int DEFAULT_PORT = 3306;

    private MysqlJdbcUrl() {
    }

    /// @throws IllegalArgumentException when `raw` looks like a URL
    ///                                   (contains `://`) but is not a
    ///                                   parseable `mysql://` URL
    static String toJdbcUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("empty mysql db-url");
        }
        if (raw.startsWith("jdbc:")) {
            return raw; // already a JDBC URL — pass through verbatim
        }
        if (!raw.contains("://")) {
            // No scheme at all: treat it as already being the "host[:port]/db"
            // body a JDBC URL wants and just prefix the scheme.
            return "jdbc:mysql://" + raw;
        }
        URI uri;
        try {
            uri = new URI(raw);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("parse mysql url \"" + raw + "\": " + e.getMessage(), e);
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("mysql url \"" + raw + "\" has no host — use a mysql://host[:port]/db "
                    + "URL, or a full jdbc:mysql:… URL");
        }
        int port = uri.getPort() > 0 ? uri.getPort() : DEFAULT_PORT;
        String db = uri.getPath() == null ? "" : uri.getPath().replaceFirst("^/", "");

        var jdbc = new StringBuilder("jdbc:mysql://").append(host).append(':').append(port).append('/').append(db);
        var query = new StringBuilder();
        String userInfo = uri.getUserInfo();
        if (userInfo != null && !userInfo.isBlank()) {
            int colon = userInfo.indexOf(':');
            String user = colon >= 0 ? userInfo.substring(0, colon) : userInfo;
            String pass = colon >= 0 ? userInfo.substring(colon + 1) : "";
            appendParam(query, "user", user);
            appendParam(query, "password", pass);
        }
        if (uri.getRawQuery() != null && !uri.getRawQuery().isBlank()) {
            query.append(query.isEmpty() ? "" : "&").append(uri.getRawQuery());
        }
        if (!query.isEmpty()) {
            jdbc.append('?').append(query);
        }
        return jdbc.toString();
    }

    private static void appendParam(StringBuilder sb, String key, String value) {
        if (!sb.isEmpty()) sb.append('&');
        sb.append(key).append('=').append(value);
    }
}
