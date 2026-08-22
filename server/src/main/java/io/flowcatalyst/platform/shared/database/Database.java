package io.flowcatalyst.platform.shared.database;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/// Connection-pool factory. Accepts the same `FC_DATABASE_URL` the Go service
/// uses (`postgresql://user:pass@host:port/db?sslmode=disable`, libpq style)
/// as well as a plain JDBC URL, and returns a HikariCP pool named `fc`.
public final class Database {

    private Database() {
    }

    /// Opens a pool for `url`.
    ///
    /// @param url         `postgresql://…` / `postgres://…` (libpq form, as
    ///                    the Go service reads it) or `jdbc:postgresql://…`
    /// @param maxPoolSize maximum connections (Hikari `maximumPoolSize`)
    public static HikariDataSource newPool(String url, int maxPoolSize) {
        var config = new HikariConfig();
        config.setPoolName("fc");
        config.setMaximumPoolSize(maxPoolSize);
        config.setMinimumIdle(Math.min(2, maxPoolSize));
        config.setConnectionTimeout(Duration.ofSeconds(10).toMillis());
        config.setIdleTimeout(Duration.ofMinutes(5).toMillis());
        config.setMaxLifetime(Duration.ofMinutes(30).toMillis());
        config.setAutoCommit(true);

        Jdbc jdbc = toJdbc(url);
        config.setJdbcUrl(jdbc.url());
        if (jdbc.user() != null) {
            config.setUsername(jdbc.user());
        }
        if (jdbc.password() != null) {
            config.setPassword(jdbc.password());
        }
        jdbc.properties().forEach(config::addDataSourceProperty);
        return new HikariDataSource(config);
    }

    /// A JDBC URL plus the credentials and driver properties split out of a
    /// libpq-style URL. `user` / `password` are `null` when the URL carries
    /// none (Hikari then falls back to the driver's defaults).
    public record Jdbc(String url, String user, String password, Map<String, String> properties) {
        public Jdbc {
            Objects.requireNonNull(url, "url");
            properties = properties == null ? Map.of() : Map.copyOf(properties);
        }
    }

    /// Converts `postgresql://user:pass@host:port/db?sslmode=disable` to
    /// `jdbc:postgresql://host:port/db` + user/password + driver properties.
    /// A URL that already starts with `jdbc:` is returned unchanged.
    ///
    /// `sslmode` is passed through: pgjdbc understands the libpq values
    /// (`disable`, `allow`, `prefer`, `require`, `verify-ca`, `verify-full`).
    /// `application_name` becomes pgjdbc's `ApplicationName`; any other query
    /// parameter is forwarded verbatim as a driver property.
    public static Jdbc toJdbc(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("database url is empty");
        }
        if (url.startsWith("jdbc:")) {
            return new Jdbc(url, null, null, Map.of());
        }
        if (!url.startsWith("postgresql://") && !url.startsWith("postgres://")) {
            throw new IllegalArgumentException("unsupported database url scheme: " + url);
        }
        URI uri = URI.create(url);
        String host = uri.getHost() != null ? uri.getHost() : "localhost";
        int port = uri.getPort() != -1 ? uri.getPort() : 5432;
        String db = uri.getPath() == null ? "" : uri.getPath().replaceFirst("^/", "");

        String user = null;
        String password = null;
        if (uri.getRawUserInfo() != null) {
            String[] up = uri.getRawUserInfo().split(":", 2);
            user = decode(up[0]);
            password = up.length > 1 ? decode(up[1]) : null;
        }

        Map<String, String> props = new LinkedHashMap<>();
        if (uri.getRawQuery() != null && !uri.getRawQuery().isEmpty()) {
            for (String pair : uri.getRawQuery().split("&")) {
                if (pair.isEmpty()) {
                    continue;
                }
                String[] kv = pair.split("=", 2);
                String key = decode(kv[0]);
                String value = kv.length > 1 ? decode(kv[1]) : "";
                switch (key) {
                    case "sslmode" -> props.put("sslmode", value);
                    case "application_name" -> props.put("ApplicationName", value);
                    case "user" -> user = value;
                    case "password" -> password = value;
                    default -> props.put(key, value);
                }
            }
        }
        String jdbcUrl = "jdbc:postgresql://" + host + ":" + port + "/" + db;
        return new Jdbc(jdbcUrl, user, password, Map.copyOf(props));
    }

    private static String decode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }
}
