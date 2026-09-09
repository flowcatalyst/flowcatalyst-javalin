package io.flowcatalyst.fcdev;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// The Java and Go `fcdev` deliberately share one cluster data directory
/// ([DevPaths]), so starting a cluster **Go** initialised is the ordinary
/// case, not an exotic one.
///
/// Go's `initdb` writes a `pg_hba.conf` of `password` for every local and host
/// line, where this binary's writes `trust`. Zonky makes its own connections —
/// the startup readiness probe above all — and sends whatever `connectConfig`
/// carries. With no password those are refused, and the symptom is thoroughly
/// misleading: Postgres logs "database system is ready to accept connections"
/// and then `fcdev` dies 60s later with "Gave up waiting for server to start",
/// having never listened on its own port.
class AdoptedGoClusterTest {

    @Test
    @DisplayName("a cluster whose pg_hba.conf demands a password (Go's) still starts")
    void startsAClusterThatDemandsAPassword() throws Exception {
        io.flowcatalyst.server.Logging.init(Map.of("FC_LOG_LEVEL", "warn", "FC_LOG_FORMAT", "text"));
        Path root = Files.createTempDirectory("fcdev-adopted-go");
        Path data = root.resolve("data");
        Path cache = root.resolve("cache");

        // First start initialises the cluster the way this binary does.
        try (var pg = EmbeddedPg.start(data, 0, cache)) {
            assertThat(pg.port()).isPositive();
        } catch (Exception | ExceptionInInitializerError e) {
            LoggerFactory.getLogger(AdoptedGoClusterTest.class).warn("embedded PostgreSQL cannot start here", e);
            Assumptions.abort("embedded PostgreSQL cannot start in this environment: " + e);
            return;
        }

        // Rewrite its pg_hba.conf to exactly what Go's initdb leaves behind.
        Path hba = data.resolve("data").resolve("pg_hba.conf");
        assertThat(hba).exists();
        Files.write(hba, List.of(
                "local   all             all                                     password",
                "host    all             all             127.0.0.1/32            password",
                "host    all             all             ::1/128                 password"));

        // Starting it again is the adoption path, and must work.
        try (var pg = EmbeddedPg.start(data, 0, cache)) {
            assertThat(pg.port()).isPositive();
            assertThat(pg.url()).contains(EmbeddedPg.USER + ":" + EmbeddedPg.PASSWORD);
        }
    }
}
