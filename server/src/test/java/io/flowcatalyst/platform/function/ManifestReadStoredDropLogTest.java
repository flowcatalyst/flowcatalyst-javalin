package io.flowcatalyst.platform.function;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.flowcatalyst.platform.shared.json.Json;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/// The tolerant stored-manifest reader still drops what it cannot read, but
/// says so: a live function must not lose a route without a trace. Mutant:
/// the silent `ifPresent(list::add)`.
class ManifestReadStoredDropLogTest {

    @Test
    void aDroppedEndpointIsLoggedAndTheRestStillReads() {
        var stored = Json.MAPPER.readTree("""
                {"runtime":"jvm","entrypoint":"x.Fn","pool":"p",
                 "endpoints":[{"path":"/ok","auth":"none"},{"path":"no-leading-slash","auth":"none"}]}
                """);
        Logger log = (Logger) LoggerFactory.getLogger(Manifest.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        log.addAppender(appender);
        Manifest.resetDroppedLogForTest(); // another test in this JVM may have spent the throttle's window
        Manifest m;
        try {
            m = Manifest.readStored(stored);
        } finally {
            log.detachAppender(appender);
        }
        assertThat(m.endpoints()).as("the readable endpoint survives, the other is dropped").hasSize(1);
        assertThat(appender.list).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.WARN);
            assertThat(String.valueOf(e.getKeyValuePairs())).contains("endpoint").contains("no-leading-slash");
        });
    }
}
