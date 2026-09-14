package io.flowcatalyst.server;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.encoder.Encoder;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/// Programmatic Logback setup — the Java reading of `internal/logging`
/// (`logging.Init()`): structured output to **stderr**, root level from
/// `FC_LOG_LEVEL` (`debug` / `info` / `warn` / `error`, default `info`).
/// No `logback.xml`; the configuration is code so it is greppable.
///
/// Go always writes JSON. Here the default is JSON too, but
/// `FC_LOG_FORMAT` (alias `LOG_FORMAT`) = `text` selects a readable pattern,
/// and when neither is set and stderr is an interactive terminal the readable
/// pattern is chosen automatically. The JSON shape IS a contract now
/// (owner ruling 2026-09-14, `docs/spec/logging.md`): [GoJsonEncoder] emits
/// the same flat shape Go's `slog.NewJSONHandler` does — `time`, `level`,
/// `msg`, the MDC, the key-values, then the Java-only superset keys
/// `logger`/`thread`/`err`/`stack` — so logs from both codebases aggregate
/// in one pipeline on the same field names, MDC included ([MdcKeys]).
///
/// Request code puts the trace fields on the MDC:
///
/// ```java
/// MDC.put(Logging.MdcKeys.CORRELATION_ID, ec.correlationId());
/// ```
public final class Logging {

    private Logging() {
    }

    /// The structured-log field names shared with the Go platform (`FromContext`).
    public static final class MdcKeys {
        public static final String CORRELATION_ID = "correlation_id";
        public static final String CAUSATION_ID = "causation_id";
        public static final String PRINCIPAL_ID = "principal_id";
        public static final String EXECUTION_ID = "execution_id";

        private MdcKeys() {
        }
    }

    /// Output format.
    public enum Format { JSON, TEXT }

    /// Configure from the process environment.
    public static void init() {
        init(EnvReader.system());
    }

    /// Configure from an arbitrary environment (tests, the [DotEnv] merge).
    public static void init(Map<String, String> environment) {
        init(new EnvReader(environment));
    }

    public static void init(EnvReader env) {
        var format = formatOf(env.firstSet("FC_LOG_FORMAT", "LOG_FORMAT").orElse(""));
        var resolution = resolveLevels(env);
        init(resolution.root(), format);
        if (resolution.routerLevel() != null) {
            var routerLogger = (Logger) LoggerFactory.getLogger("io.flowcatalyst.router");
            routerLogger.setLevel(resolution.routerLevel());
        }
        if (!resolution.ignoredTargets().isEmpty()) {
            LoggerFactory.getLogger(Logging.class)
                    .info("RUST_LOG target(s) have no Java mapping and were ignored: {}", resolution.ignoredTargets());
        }
    }

    /// `FC_LOG_LEVEL`/`RUST_LOG` resolved: the root level, an optional level
    /// for `io.flowcatalyst.router` (Rust's `fc_router` target — the package
    /// every router class lives under), and any other `target=level` entries
    /// `RUST_LOG` named that this binary does not map anywhere.
    public record LevelResolution(Level root, Level routerLevel, List<String> ignoredTargets) {
    }

    /// `FC_LOG_LEVEL` wins outright when set — `RUST_LOG` is not consulted at
    /// all in that case, matching §5 of `docs/spec/router-env.md`. Otherwise
    /// `RUST_LOG` is parsed as the Rust router's own `tracing` filter: a
    /// comma-separated entry with no `=` sets the root level (the last bare
    /// token wins if more than one appears, matching `tracing_subscriber`'s
    /// own last-wins directive parsing); `fc_router=<level>` sets the router
    /// package's level; anything else is collected as ignored rather than
    /// silently dropped, so an operator who set `tower_http=warn` and expected
    /// something from it sees why nothing changed.
    static LevelResolution resolveLevels(EnvReader env) {
        var fcLogLevel = env.get("FC_LOG_LEVEL");
        if (!fcLogLevel.isBlank()) {
            return new LevelResolution(levelOf(fcLogLevel), null, List.of());
        }
        return parseRustLog(env.get("RUST_LOG"));
    }

    static LevelResolution parseRustLog(String raw) {
        if (raw == null || raw.isBlank()) {
            return new LevelResolution(Level.INFO, null, List.of());
        }
        var root = Level.INFO;
        Level routerLevel = null;
        var ignored = new ArrayList<String>();
        for (var rawEntry : raw.split(",")) {
            var token = rawEntry.trim();
            if (token.isEmpty()) {
                continue;
            }
            var eq = token.indexOf('=');
            if (eq < 0) {
                root = levelOf(token);
                continue;
            }
            var target = token.substring(0, eq).trim();
            var levelWord = token.substring(eq + 1).trim();
            if ("fc_router".equals(target)) {
                routerLevel = levelOf(levelWord);
            } else {
                ignored.add(token);
            }
        }
        return new LevelResolution(root, routerLevel, List.copyOf(ignored));
    }

    /// Reset the Logback context and install a single stderr appender.
    public static void init(Level rootLevel, Format format) {
        var context = (LoggerContext) LoggerFactory.getILoggerFactory();
        context.reset();

        var encoder = switch (format) {
            case JSON -> jsonEncoder(context);
            case TEXT -> textEncoder(context);
        };

        var appender = new ConsoleAppender<ILoggingEvent>();
        appender.setContext(context);
        appender.setName("stderr");
        appender.setTarget("System.err");
        appender.setEncoder(encoder);
        appender.start();

        Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
        root.setLevel(rootLevel);
        root.addAppender(appender);
    }

    /// `FC_LOG_LEVEL` → level. Go matches `debug`, `warn`/`warning`, `error`
    /// (and their upper-case forms) and treats everything else as info; this
    /// accepts any casing, which is a superset.
    static Level levelOf(String raw) {
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "debug", "trace" -> Level.DEBUG;
            case "warn", "warning" -> Level.WARN;
            case "error" -> Level.ERROR;
            default -> Level.INFO;
        };
    }

    /// `FC_LOG_FORMAT` → format: `text`/`plain`/`console` → TEXT, `json` → JSON,
    /// unset → TEXT on an interactive terminal, JSON otherwise.
    static Format formatOf(String raw) {
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "text", "plain", "console", "pretty" -> Format.TEXT;
            case "json" -> Format.JSON;
            default -> stderrIsTerminal() ? Format.TEXT : Format.JSON;
        };
    }

    private static boolean stderrIsTerminal() {
        var console = System.console();
        return console != null && console.isTerminal();
    }

    private static Encoder<ILoggingEvent> jsonEncoder(LoggerContext context) {
        var enc = new GoJsonEncoder();
        enc.setContext(context);
        enc.start();
        return enc;
    }

    private static Encoder<ILoggingEvent> textEncoder(LoggerContext context) {
        var enc = new PatternLayoutEncoder();
        enc.setContext(context);
        enc.setPattern("%d{HH:mm:ss.SSS} %-5level [%thread] %logger{36} %X{correlation_id:-} - %msg %kvp%n%ex");
        enc.start();
        return enc;
    }
}
