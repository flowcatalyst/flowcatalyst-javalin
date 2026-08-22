package io.flowcatalyst.server;

import ch.qos.logback.classic.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LoggingTest {

    private final PrintStream originalErr = System.err;

    @AfterEach
    void restore() {
        System.setErr(originalErr);
        MDC.clear();
        Logging.init(Level.INFO, Logging.Format.TEXT);
    }

    @Test
    void levelVocabulary() {
        assertThat(Logging.levelOf("")).isEqualTo(Level.INFO);
        assertThat(Logging.levelOf("debug")).isEqualTo(Level.DEBUG);
        assertThat(Logging.levelOf("DEBUG")).isEqualTo(Level.DEBUG);
        assertThat(Logging.levelOf("warn")).isEqualTo(Level.WARN);
        assertThat(Logging.levelOf("WARNING")).isEqualTo(Level.WARN);
        assertThat(Logging.levelOf("error")).isEqualTo(Level.ERROR);
        assertThat(Logging.levelOf("verbose")).isEqualTo(Level.INFO);
    }

    @Test
    void formatVocabulary() {
        assertThat(Logging.formatOf("json")).isEqualTo(Logging.Format.JSON);
        assertThat(Logging.formatOf("JSON")).isEqualTo(Logging.Format.JSON);
        assertThat(Logging.formatOf("text")).isEqualTo(Logging.Format.TEXT);
        assertThat(Logging.formatOf("console")).isEqualTo(Logging.Format.TEXT);
    }

    @Test
    void jsonToStderrWithMdcFieldsAndLevel() {
        var captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));

        Logging.init(Map.of("FC_LOG_LEVEL", "debug", "FC_LOG_FORMAT", "json"));
        MDC.put(Logging.MdcKeys.CORRELATION_ID, "corr-1");
        MDC.put(Logging.MdcKeys.PRINCIPAL_ID, "prn_abc");
        var log = LoggerFactory.getLogger("test.logger");
        log.debug("hello {}", "world");
        log.atInfo().addKeyValue("event_type", "x:y:z:w").log("with kvp");

        var out = captured.toString(StandardCharsets.UTF_8);
        assertThat(out).contains("\"level\":\"DEBUG\"")
                .contains("\"correlation_id\":\"corr-1\"")
                .contains("\"principal_id\":\"prn_abc\"")
                .contains("hello world")
                .contains("\"event_type\":\"x:y:z:w\"");
        assertThat(out.lines().filter(l -> !l.isBlank())).allSatisfy(l -> assertThat(l).startsWith("{").endsWith("}"));
    }

    @Test
    void infoIsTheDefaultLevel() {
        var captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        Logging.init(Map.of("FC_LOG_FORMAT", "text"));
        var log = LoggerFactory.getLogger("test.logger");
        log.debug("invisible");
        log.info("visible");
        var out = captured.toString(StandardCharsets.UTF_8);
        assertThat(out).contains("visible").doesNotContain("invisible").contains("INFO");
    }
}
