package io.flowcatalyst.fnhost.http;

import io.flowcatalyst.platform.function.FunctionAddress;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/// `io.flowcatalyst.fnhost.FunctionInvocationEvent` (`docs/spec/function-host-process.md`
/// §2, test P10): committed once per ENTERED invocation, with the right
/// fields, captured with a real [RecordingStream] — never a mock of the JFR
/// API.
class FunctionInvocationEventTest {

    private static final FunctionAddress ADDR = FnHttpTestSupport.ADDR_A;
    private static final String EVENT_NAME = "io.flowcatalyst.fnhost.FunctionInvocation";

    private static String echoStatusSource() {
        return """
                package fixture.jfr;
                import io.flowcatalyst.function.*;
                import java.util.*;
                public final class EchoStatusFn implements Function {
                    public Result handle(Request in, FunctionContext ctx) throws Exception {
                        return Result.http(200, Map.of(), new byte[0]);
                    }
                }
                """;
    }

    @Test
    void committedOnceWithTheRightFields(@TempDir Path dir) throws Exception {
        Path jar = FnHttpTestSupport.functionJar(dir, "echo", "fixture.jfr.EchoStatusFn", echoStatusSource());
        var manifest = FnHttpTestSupport.manifest("p", false, 10, 5000, "fixture.jfr.EchoStatusFn",
                "[{\"path\":\"/*\",\"auth\":\"none\"}]");
        var entry = FnHttpTestSupport.liveEntry(ADDR, "fnc_1", "v1", 1, jar, manifest, null, null, null);

        List<RecordedEvent> captured = new CopyOnWriteArrayList<>();
        CountDownLatch seen = new CountDownLatch(1);
        try (RecordingStream rs = new RecordingStream()) {
            rs.enable(EVENT_NAME);
            rs.onEvent(EVENT_NAME, e -> {
                captured.add(e);
                seen.countDown();
            });
            rs.startAsync();

            try (var h = FnHttpTestSupport.start(dir, FnHttpTestSupport.oneFunction(entry))) {
                var resp = h.get("/functions/" + ADDR.render() + "/x");
                assertThat(resp.statusCode()).isEqualTo(200);

                assertThat(seen.await(30, TimeUnit.SECONDS))
                        .as("mutant: never commit").isTrue();
            }
        }

        assertThat(captured).as("mutant: never commit — exactly one committed event for one invocation")
                .hasSize(1);
        RecordedEvent event = captured.get(0);
        assertThat(event.getString("address")).isEqualTo(ADDR.render());
        assertThat(event.getInt("version")).isEqualTo(1);
        assertThat(event.getString("invocationId")).isNotBlank();
        assertThat(event.getInt("status")).isEqualTo(200);
        assertThat(event.getString("outcome")).isEqualTo("ok");
    }

    @Test
    void notCommittedForAHostRefusal(@TempDir Path dir) throws Exception {
        Path jar = FnHttpTestSupport.functionJar(dir, "echo", "fixture.jfr.EchoStatusFn", echoStatusSource());
        var manifest = FnHttpTestSupport.manifest("p", false, 10, 5000, "fixture.jfr.EchoStatusFn",
                "[{\"path\":\"/events/*\",\"auth\":\"webhook\"}]");
        var entry = FnHttpTestSupport.liveEntry(ADDR, "fnc_1", "v1", 1, jar, manifest, "secret", "app_1", "clt_1");

        List<RecordedEvent> captured = new CopyOnWriteArrayList<>();
        try (RecordingStream rs = new RecordingStream()) {
            rs.enable(EVENT_NAME);
            rs.onEvent(EVENT_NAME, captured::add);
            rs.startAsync();

            try (var h = FnHttpTestSupport.start(dir, FnHttpTestSupport.oneFunction(entry))) {
                // Unauthorized — the function is never entered.
                var resp = h.post("/functions/" + ADDR.render() + "/events/x", new byte[0]);
                assertThat(resp.statusCode()).isEqualTo(401);
            }
            rs.stop();
            // A generous, bounded grace period for any (wrongly committed) event to arrive.
            Thread.sleep(Duration.ofMillis(500));
        }

        assertThat(captured).as("a host refusal never enters the function — no event for it").isEmpty();
    }
}
