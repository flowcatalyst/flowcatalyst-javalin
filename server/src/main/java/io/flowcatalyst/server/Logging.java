package io.flowcatalyst.server;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.JsonEncoder;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.encoder.Encoder;
import org.slf4j.LoggerFactory;

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
/// pattern is chosen automatically. The JSON shape is Logback's own
/// [JsonEncoder] (timestamp, level, thread, logger, message, mdc, kvp,
/// throwable) — the log *shape* is not a contract; the **field names** on MDC
/// are ([MdcKeys]), so logs from both codebases aggregate in one pipeline.
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
        init(levelOf(env.get("FC_LOG_LEVEL")), formatOf(env.firstSet("FC_LOG_FORMAT", "LOG_FORMAT").orElse("")));
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
        var enc = new JsonEncoder();
        enc.setContext(context);
        enc.setWithSequenceNumber(false);
        enc.setWithNanoseconds(false);
        enc.setWithContext(false);
        enc.setWithMessage(false);          // raw template — the formatted one is enough
        enc.setWithArguments(false);
        enc.setWithFormattedMessage(true);
        enc.setWithMDC(true);
        enc.setWithKVPList(true);
        enc.setWithThrowable(true);
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
