package io.flowcatalyst.fnhost.http;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/// A JWKS outage keeps the cached keys (unchanged) — and now says so: after a
/// key rotation the log is the operator's only trace of why bearer calls
/// answer 401. Mutant: the silent `return`.
class JwksKeySourceFailureLogTest {

    @Test
    void aJwksFetchThatFailsIsLoggedWithItsStatus() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/.well-known/openid-configuration", exchange -> {
            byte[] body = ("{\"issuer\":\"https://platform.example.test\",\"jwks_uri\":\"" + base
                    + "/.well-known/jwks.json\"}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/.well-known/jwks.json", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
        Logger log = (Logger) LoggerFactory.getLogger(JwksKeySource.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        log.addAppender(appender);
        try {
            var source = new JwksKeySource(HttpClient.newHttpClient(), base);

            assertThat(source.ensureKnown("kid-after-rotation")).isFalse();

            assertThat(appender.list).anySatisfy(e -> {
                assertThat(e.getLevel()).isEqualTo(Level.WARN);
                assertThat(e.getFormattedMessage()).contains("JWKS");
                assertThat(e.getKeyValuePairs()).anySatisfy(kv -> {
                    assertThat(kv.key).isEqualTo("status");
                    assertThat(kv.value).isEqualTo(503);
                });
            });
        } finally {
            log.detachAppender(appender);
            server.stop(0);
        }
    }
}
