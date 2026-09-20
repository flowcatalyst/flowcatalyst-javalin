package io.flowcatalyst.fnhost.context;

import io.flowcatalyst.platform.shared.database.Database;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/// A function's declared database connection, normalised and validated
/// (spec `function-context.md` §2): accepts `jdbc:postgresql://…` and
/// `postgres://user:pass@host[:port]/db[?params]` (libpq form, percent-decoded)
/// through the server's own [Database#toJdbc] — **PostgreSQL only**; anything
/// else, including a `jdbc:` URL for another driver, is `DB_UNSUPPORTED`.
///
/// `toString` never prints the password (`CONVENTIONS.md` §8: "a carrier of
/// key material masks `toString`") — [#identity] is the only place the
/// password appears at all outside the Hikari config built from it, and it
/// is never logged (it exists purely as [DbPools]'s map key, so two entries
/// that resolve to the exact same connection share one pool).
public final class Dsn {

    private final String raw;
    private final String jdbcUrl;
    private final String user;
    private final String password;
    private final Map<String, String> properties;

    private Dsn(String raw, String jdbcUrl, String user, String password, Map<String, String> properties) {
        this.raw = raw;
        this.jdbcUrl = jdbcUrl;
        this.user = user;
        this.password = password;
        this.properties = properties;
    }

    /// @throws UnsupportedDsnException `raw` is not a `postgresql://` /
    ///                                  `postgres://` / `jdbc:postgresql:` URL
    public static Dsn parse(String raw) {
        Database.Jdbc jdbc;
        try {
            jdbc = Database.toJdbc(raw);
        } catch (RuntimeException e) {
            // Database#toJdbc's own IllegalArgumentException message never echoes
            // credentials (it names only the unsupported scheme), but it is
            // rewrapped here regardless so a future change to that message can
            // never accidentally start leaking one through this path.
            throw new UnsupportedDsnException("unsupported or malformed database url");
        }
        if (!jdbc.url().startsWith("jdbc:postgresql:")) {
            throw new UnsupportedDsnException("only PostgreSQL is supported on this host");
        }
        return new Dsn(raw, jdbc.url(), jdbc.user(), jdbc.password(), jdbc.properties());
    }

    /// The exact string [DbPools] shares a pool by — every field that could
    /// make two DSNs describe genuinely different connections (`CONVENTIONS.md`
    /// §8 "one spelling per encoding contract"; here, connection identity).
    String identity() {
        StringBuilder sb = new StringBuilder(jdbcUrl).append('|').append(user).append('|').append(password);
        for (var e : new TreeMap<>(properties).entrySet()) {
            sb.append('|').append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    /// What [io.flowcatalyst.platform.shared.database.Database#newPool] must
    /// be built from — the ORIGINAL string, not [#jdbcUrl] (which has already
    /// had any `postgres://user:pass@…` userinfo split out of it: passing
    /// `jdbcUrl` alone back into `Database#newPool` would re-parse it as an
    /// already-`jdbc:`-prefixed URL and silently drop the credentials, one
    /// level of information this class must not lose on the way through).
    String raw() {
        return raw;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Dsn other && identity().equals(other.identity());
    }

    @Override
    public int hashCode() {
        return identity().hashCode();
    }

    @Override
    public String toString() {
        // Everything from '?' on is dropped, not filtered: a JDBC-form URL may carry
        // `password=` (or `sslpassword=`, or a driver property nobody thought of) as a
        // parameter, and a query string has no part worth printing next to that risk.
        int query = jdbcUrl.indexOf('?');
        String shown = query < 0 ? jdbcUrl : jdbcUrl.substring(0, query) + "?…";
        return "Dsn[url=" + shown + (user != null ? ", user=" + user : "") + "]";
    }

    /// `raw` is not a supported connection string — spec §2's `DB_UNSUPPORTED`.
    public static final class UnsupportedDsnException extends RuntimeException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        UnsupportedDsnException(String message) {
            super(message);
        }
    }
}
