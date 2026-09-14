package io.flowcatalyst.server;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.joran.JoranConfigurator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.event.KeyValuePair;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// Pins `docs/spec/logging.md` §5 — one row per table entry, each with the
/// mutant it exists to catch named in the test's own comment.
@SuppressWarnings("deprecation")
class GoJsonEncoderTest {

    private final GoJsonEncoder encoder = new GoJsonEncoder();
    private final ObjectMapper mapper = new ObjectMapper();

    private final PrintStream originalErr = System.err;

    @BeforeEach
    void clearMdc() {
        MDC.clear();
    }

    @AfterEach
    void restore() {
        System.setErr(originalErr);
        MDC.clear();
        reloadTestLoggingConfig();
    }

    /// Same rationale as `LoggingTest#reloadTestLoggingConfig`: `Logging.init`
    /// (row 8, end-to-end) resets the global Logback context and installs the
    /// production appender, so later tests in the same JVM must get
    /// `logback-test.xml` back rather than inherit that.
    private static void reloadTestLoggingConfig() {
        var context = (LoggerContext) LoggerFactory.getILoggerFactory();
        context.reset();
        var configurator = new JoranConfigurator();
        configurator.setContext(context);
        try (var in = GoJsonEncoderTest.class.getResourceAsStream("/logback-test.xml")) {
            configurator.doConfigure(in);
        } catch (Exception e) {
            throw new IllegalStateException("could not restore logback-test.xml", e);
        }
    }

    private static LoggingEvent event(Level level, String message, Throwable throwable) {
        var context = (LoggerContext) LoggerFactory.getILoggerFactory();
        var logger = context.getLogger("x");
        var evt = new LoggingEvent(Logger.class.getName(), logger, level, message, throwable, null);
        evt.setThreadName("main");
        return evt;
    }

    private JsonNode parse(LoggingEvent evt) throws IOException {
        byte[] bytes = encoder.encode(evt);
        String line = new String(bytes, StandardCharsets.UTF_8);
        assertThat(line).endsWith("\n");
        return mapper.readTree(line);
    }

    @Test
    @DisplayName("plain INFO line: exact keys, exact order — mutant: nested/extra keys")
    void plainInfoLine() throws IOException {
        var evt = event(Level.INFO, "hello", null);

        var node = parse(evt);
        assertThat(node.propertyNames()).containsExactly("time", "level", "msg", "logger", "thread");
        assertThat(node.path("level").asText()).isEqualTo("INFO");
        assertThat(node.path("msg").asText()).isEqualTo("hello");
        assertThat(node.path("logger").asText()).isEqualTo("x");
        assertThat(node.path("thread").asText()).isEqualTo("main");
    }

    @Test
    @DisplayName("key-values in call order, correct types, before logger — mutant: wrong types or order")
    void keyValuesInCallOrderWithTypes() throws IOException {
        var evt = event(Level.INFO, "with kv", null);
        evt.setKeyValuePairs(List.of(
                new KeyValuePair("queue", "q1"),
                new KeyValuePair("attempts", 3),
                new KeyValuePair("ok", true),
                new KeyValuePair("gone", null)));

        var node = parse(evt);
        assertThat(node.propertyNames())
                .containsExactly("time", "level", "msg", "queue", "attempts", "ok", "gone", "logger", "thread");
        assertThat(node.path("queue").isTextual()).isTrue();
        assertThat(node.path("queue").asText()).isEqualTo("q1");
        assertThat(node.path("attempts").isNumber()).isTrue();
        assertThat(node.path("attempts").asInt()).isEqualTo(3);
        assertThat(node.path("ok").isBoolean()).isTrue();
        assertThat(node.path("ok").asBoolean()).isTrue();
        assertThat(node.path("gone").isNull()).isTrue();
    }

    @Test
    @DisplayName("MDC is flat, before the key-values, and gone once cleared — mutant: MDC nested or leaked")
    void mdcFlatBeforeKeyValuesAndClearable() throws IOException {
        var withMdc = event(Level.INFO, "has mdc", null);
        withMdc.setMDCPropertyMap(Map.of("correlation_id", "abc"));
        withMdc.setKeyValuePairs(List.of(new KeyValuePair("k", "v")));

        var node = parse(withMdc);
        assertThat(node.propertyNames())
                .containsExactly("time", "level", "msg", "correlation_id", "k", "logger", "thread");
        assertThat(node.path("correlation_id").asText()).isEqualTo("abc");

        var withoutMdc = event(Level.INFO, "no mdc", null);
        var cleared = parse(withoutMdc);
        assertThat(cleared.propertyNames()).doesNotContain("correlation_id");
    }

    @Test
    @DisplayName("attached cause: err is Class: message, stack has frames and Caused by — mutant: err/stack missing")
    void causeProducesErrAndStack() throws IOException {
        var throwable = new IllegalStateException("boom", new IOException("io"));
        var evt = event(Level.ERROR, "failed", throwable);

        var node = parse(evt);
        assertThat(node.path("err").asText()).isEqualTo("java.lang.IllegalStateException: boom");
        String stack = node.path("stack").asText();
        assertThat(stack).contains("at ");
        assertThat(stack).contains("Caused by: java.io.IOException: io");
    }

    @Test
    @DisplayName("time: RFC 3339, six fractional digits, default-zone offset, close to now — mutant: wrong format/zone")
    void timeFormatAndZone() throws IOException {
        var evt = event(Level.INFO, "t", null);
        var before = Instant.now();

        var node = parse(evt);
        var time = node.path("time").asText();
        assertThat(time).matches("^\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\.\\d{6}(Z|[+-]\\d\\d:\\d\\d)$");

        var parsedInstant = java.time.OffsetDateTime.parse(time).toInstant();
        assertThat(parsedInstant).isBetween(before.minusSeconds(1), Instant.now().plusSeconds(1));
    }

    @Test
    @DisplayName("level mapping: TRACE -> DEBUG, WARN/ERROR unchanged — mutant: level mapping")
    void levelMapping() throws IOException {
        assertThat(parse(event(Level.TRACE, "t", null)).path("level").asText()).isEqualTo("DEBUG");
        assertThat(parse(event(Level.DEBUG, "t", null)).path("level").asText()).isEqualTo("DEBUG");
        assertThat(parse(event(Level.WARN, "t", null)).path("level").asText()).isEqualTo("WARN");
        assertThat(parse(event(Level.ERROR, "t", null)).path("level").asText()).isEqualTo("ERROR");
    }

    @Test
    @DisplayName("message escaping round-trips quote/backslash/newline/control char — mutant: escaping")
    void messageEscapingRoundTrips() throws IOException {
        String raw = "quote\" backslash\\ newline\n ctrl\u0001end";
        var evt = event(Level.INFO, raw, null);

        var node = parse(evt);
        assertThat(node.path("msg").asText()).isEqualTo(raw);
    }

    @Test
    @DisplayName("a key-value literally named msg is renamed kv_msg — mutant: collision")
    void keyValueCollisionWithReservedKeyIsRenamed() throws IOException {
        var evt = event(Level.INFO, "the real message", null);
        evt.setKeyValuePairs(List.of(new KeyValuePair("msg", "not the message")));

        var node = parse(evt);
        assertThat(node.path("msg").asText()).isEqualTo("the real message");
        assertThat(node.path("kv_msg").asText()).isEqualTo("not the message");
        assertThat(node.has("kv_kv_msg")).isFalse();
    }

    @Test
    @DisplayName("end to end through Logging.init: one JSON line, none of the old encoder's keys — mutant: encoder not wired")
    void endToEndThroughLoggingInit() {
        var captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));

        Logging.init(Map.of("FC_LOG_LEVEL", "info", "FC_LOG_FORMAT", "json"));
        var log = LoggerFactory.getLogger("test.logger");
        log.info("hello end to end");

        var out = captured.toString(StandardCharsets.UTF_8);
        var lines = out.lines().filter(l -> !l.isBlank()).toList();
        assertThat(lines).hasSize(1);

        var line = lines.get(0);
        assertThat(line).startsWith("{").endsWith("}");
        var node = mapper.readTree(line);
        assertThat(node.path("msg").asText()).isEqualTo("hello end to end");
        assertThat(node.has("timestamp")).isFalse();
        assertThat(node.has("formattedMessage")).isFalse();
        assertThat(node.has("kvpList")).isFalse();
        assertThat(node.has("mdc")).isFalse();
        assertThat(node.has("throwable")).isFalse();
    }
}
