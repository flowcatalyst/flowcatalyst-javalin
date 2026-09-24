package io.flowcatalyst.fnhost.http;

import com.sun.net.httpserver.HttpServer;
import io.flowcatalyst.fnhost.reconcile.DesiredDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The function-host intermittent (backlog, 2026-09-22): tests bound the host on the wildcard
/// (`0.0.0.0:0`) and dialled `127.0.0.1`. On macOS, with SO_REUSEADDR (Netty's and the JDK's
/// default), a wildcard port-0 bind can land on a port a `127.0.0.1` listener already holds —
/// measured at 1 in 100 against 200 held ports — and the kernel then hands `127.0.0.1:port`
/// connections to that more specific listener: a harness fake server answering 404, or one
/// closing, answering nothing. Tests now bind the loopback address they dial, where the same
/// clash is refused at bind time instead of silently splitting traffic.
class LoopbackBindTest {

    @Test
    void aLoopbackBoundHostRefusesAPortALoopbackListenerAlreadyHolds(@TempDir Path dir) throws IOException {
        HttpServer squatter = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        squatter.start();
        try {
            int held = squatter.getAddress().getPort();
            var options = FnHttpServer.Options.of(held, 512, "http://127.0.0.1:1").withHost("127.0.0.1");
            assertThatThrownBy(() -> FnHttpTestSupport.start(dir,
                    new DesiredDocument(List.of(), List.of(), List.of()), 50, options).close())
                    .as("the clash must fail the bind, not share the port")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("binding the function host listener");
        } finally {
            squatter.stop(0);
        }
    }

    /// Every host a test starts is bound on loopback: no `"0.0.0.0"` literal, and every
    /// `Options.of(...)` shortcut (which production binds on the wildcard) is followed by
    /// `.withHost(...)`.
    @Test
    void noFunctionHostTestBindsTheWildcard() throws IOException {
        Path root = Path.of("src/test/java");
        Pattern shortcut = Pattern.compile("(FnHttpServer|FnObservability)\\.Options\\.of\\(");
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                if (file.getFileName().toString().equals("LoopbackBindTest.java")) continue;
                String src = Files.readString(file);
                if (src.contains("\"0.0.0.0\"")) offenders.add(file + ": \"0.0.0.0\"");
                var m = shortcut.matcher(src);
                while (m.find()) {
                    int end = closingParen(src, m.end());
                    if (!src.startsWith(".withHost(", end)) offenders.add(file + ": Options.of without withHost at " + m.start());
                }
            }
        }
        assertThat(offenders).isEmpty();
    }

    private static int closingParen(String s, int afterOpen) {
        int depth = 1;
        int i = afterOpen;
        while (depth > 0) {
            char c = s.charAt(i++);
            if (c == '(') depth++;
            else if (c == ')') depth--;
        }
        return i;
    }
}
