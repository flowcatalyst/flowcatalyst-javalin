package io.flowcatalyst.server;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
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
        reloadTestLoggingConfig();
    }

    /// Puts `logback-test.xml` back, rather than calling [Logging#init] again.
    ///
    /// `init` resets the Logback context and installs the **production**
    /// appender, so "restoring" with it left every test that ran after this
    /// class in the same JVM logging through the production pattern — with
    /// unbounded `%ex`. Once the failure paths began attaching real causes
    /// (CONVENTIONS §10) that meant 2,755 stack-frame lines in a server run,
    /// which buried surefire's own summary. The class under test reconfigures
    /// global state; the teardown has to hand back the state the suite
    /// expects, not a third one.
    private static void reloadTestLoggingConfig() {
        var context = (LoggerContext) LoggerFactory.getILoggerFactory();
        context.reset();
        var configurator = new JoranConfigurator();
        configurator.setContext(context);
        try (var in = LoggingTest.class.getResourceAsStream("/logback-test.xml")) {
            configurator.doConfigure(in);
        } catch (Exception e) {
            throw new IllegalStateException("could not restore logback-test.xml", e);
        }
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
    @DisplayName("RUST_LOG: a bare level sets the root; fc_router=<level> sets the router package; other targets are ignored")
    void rustLogParsing() {
        var basic = Logging.parseRustLog("info");
        assertThat(basic.root()).isEqualTo(Level.INFO);
        assertThat(basic.routerLevel()).isNull();
        assertThat(basic.ignoredTargets()).isEmpty();

        var full = Logging.parseRustLog("info,fc_router=debug,tower_http=warn");
        assertThat(full.root()).isEqualTo(Level.INFO);
        assertThat(full.routerLevel()).isEqualTo(Level.DEBUG);
        // Kills the "RUST_LOG fc_router= directive ignored" mutant: a
        // regression that dropped the target=level branch entirely would
        // leave routerLevel null here too.
        assertThat(full.ignoredTargets()).containsExactly("tower_http=warn");
    }

    @Test
    void rustLogUnknownLevelWordsBehaveLikeAnUnknownFcLogLevel() {
        assertThat(Logging.parseRustLog("verbose").root()).isEqualTo(Level.INFO);
        assertThat(Logging.parseRustLog("fc_router=verbose").routerLevel()).isEqualTo(Level.INFO);
    }

    @Test
    void rustLogBlankOrUnsetIsInfoWithNoRouterOverride() {
        assertThat(Logging.parseRustLog(null).root()).isEqualTo(Level.INFO);
        assertThat(Logging.parseRustLog(null).routerLevel()).isNull();
        assertThat(Logging.parseRustLog("").root()).isEqualTo(Level.INFO);
        assertThat(Logging.parseRustLog("  ").root()).isEqualTo(Level.INFO);
    }

    @Test
    @DisplayName("FC_LOG_LEVEL set wins outright — RUST_LOG (including any fc_router= directive) is not consulted at all")
    void fcLogLevelWinsOverRustLogEntirely() {
        var resolution = Logging.resolveLevels(new EnvReader(Map.of(
                "FC_LOG_LEVEL", "error",
                "RUST_LOG", "info,fc_router=debug")));

        assertThat(resolution.root()).isEqualTo(Level.ERROR);
        assertThat(resolution.routerLevel()).as("RUST_LOG is not consulted when FC_LOG_LEVEL is set").isNull();
        assertThat(resolution.ignoredTargets()).isEmpty();
    }

    @Test
    @DisplayName("RUST_LOG sets io.flowcatalyst.router's own level when FC_LOG_LEVEL is unset")
    void rustLogActuallySetsTheRouterLoggerLevel() {
        Logging.init(Map.of("RUST_LOG", "info,fc_router=debug"));

        var routerLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("io.flowcatalyst.router.Anything");
        var rootLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("some.other.package");
        assertThat(routerLogger.getEffectiveLevel()).isEqualTo(Level.DEBUG);
        assertThat(rootLogger.getEffectiveLevel()).isEqualTo(Level.INFO);
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
