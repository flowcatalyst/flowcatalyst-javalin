package io.flowcatalyst.parity;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.FileAppender;
import io.flowcatalyst.platform.seed.Seeder;
import io.flowcatalyst.platform.shared.database.Migrator;
import io.flowcatalyst.platform.shared.database.Pools;
import io.flowcatalyst.server.Env;
import io.flowcatalyst.server.EnvReader;
import io.flowcatalyst.server.Server;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/// The in-process Java `Server` (parity-harness spec §1): the same
/// `Migrator.migrate` → `new Seeder(ds).run()` → `Server(...).start()` path
/// `Main` and every API test use, over its own `parity_java` clone.
///
/// SPEC? parity-harness.md §2 lists `FC_API_PORT` as literally `0` for Java
/// (the established Java-test convention — `LockfileCoverageTest` does the
/// same) and separately says `FC_JWT_ISSUER` / `FC_EXTERNAL_BASE_URL` /
/// `FC_WEBAUTHN_ORIGINS` are "each side's own `http://127.0.0.1:<port>`".
/// Those two are in tension: `Env` is built once, before `Server#start`
/// binds the (ephemeral, with `0`) listener, so the issuer baked into every
/// minted token would have to be a port nobody has picked yet. This picks a
/// free port up front — exactly as [GoSide] already must — and passes that
/// *number* as `FC_API_PORT` instead of the literal `0`, so the issuer is
/// correct from the first token. [Server.Running#apiPort()] is asserted to
/// agree, so a genuine race (another process grabbing the port between the
/// probe and the bind) surfaces loudly rather than silently mismatching.
public final class JavaSide implements Side {

    private final Server.Running running;
    private final String baseUrl;
    private final Pools pools;

    private JavaSide(Server.Running running, String baseUrl, Pools pools) {
        this.running = running;
        this.baseUrl = baseUrl;
        this.pools = pools;
    }

    /// Migrates and seeds `databaseUrl` (a fresh clone — the adoption path,
    /// spec §1), then starts the platform API over it with `baseEnv`
    /// overlaid by this side's own port/issuer/origin variables.
    public static JavaSide start(String databaseUrl, Map<String, String> baseEnv, Path logFile) {
        attachFileAppender(logFile);

        int port = freePort();
        String baseUrl = "http://127.0.0.1:" + port;
        Map<String, String> env = new LinkedHashMap<>(baseEnv);
        env.put("FC_DATABASE_URL", databaseUrl);
        env.put("FC_API_PORT", String.valueOf(port));
        env.put("FC_METRICS_PORT", "0");
        env.put("FC_PLATFORM_ENABLED", "true");
        env.put("FC_JWT_ISSUER", baseUrl);
        env.put("FC_EXTERNAL_BASE_URL", baseUrl);
        env.put("FC_WEBAUTHN_ORIGINS", baseUrl);

        // The four per-group pools a real boot opens (admission.md §11.7),
        // sized from the same budget Main uses; migration and seeding run on
        // the API pool exactly as StartCommand's do.
        Pools pools = Pools.open(databaseUrl, new EnvReader(env));
        Migrator.migrate(pools.api());
        new Seeder(pools.api()).run();

        Env envRecord = Env.load(env);
        Server.Running running = new Server(envRecord, new Server.Mode.Platform(pools), Server.Spa.none(),
                new PrometheusRegistry()).start();

        int actualPort = running.apiPort();
        if (actualPort != port) {
            LoggerFactory.getLogger(JavaSide.class).warn(
                    "Java bound port {} but FC_JWT_ISSUER was set for {} (a race for the free port) — "
                            + "tokens will carry the wrong issuer", actualPort, port);
        }
        return new JavaSide(running, baseUrl, pools);
    }

    @Override
    public String baseUrl() {
        return baseUrl;
    }

    @Override
    public void stop() {
        running.stop();
        pools.close();
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException("pick a free port", e);
        }
    }

    /// Adds a plain file appender to the root logger — additive, so it
    /// neither disturbs the harness's own console logging nor requires a
    /// `logback.xml` (`server`'s own [io.flowcatalyst.server.Logging] resets
    /// the whole context, which would be wrong here: several sides' logs and
    /// the harness's own share this one JVM).
    private static void attachFileAppender(Path logFile) {
        var context = (LoggerContext) LoggerFactory.getILoggerFactory();
        var encoder = new PatternLayoutEncoder();
        encoder.setContext(context);
        encoder.setPattern("%d{HH:mm:ss.SSS} %-5level [%thread] %logger{36} - %msg%n%ex");
        encoder.start();

        var appender = new FileAppender<ILoggingEvent>();
        appender.setContext(context);
        appender.setName("parity-java-log");
        appender.setFile(logFile.toString());
        appender.setAppend(false);
        appender.setEncoder(encoder);
        appender.start();

        Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
        root.addAppender(appender);
    }
}
