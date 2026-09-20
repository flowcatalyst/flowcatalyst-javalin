package io.flowcatalyst.fnhost.context;

import com.sun.net.httpserver.HttpServer;
import io.flowcatalyst.function.HttpCall;
import io.flowcatalyst.function.HttpCallRefusedException;
import io.flowcatalyst.function.HttpReply;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// X8 integration half (`docs/spec/function-context.md` §2): [AllowlistHttpCaller]
/// against real local [HttpServer]s — exact-host allow, `http://` refused off
/// loopback, a 302 handed back unfollowed, the timeout cut to the remaining
/// [InvocationDeadline].
class AllowlistHttpCallerTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private HttpServer start(String path, com.sun.net.httpserver.HttpHandler handler) throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext(path, handler);
        s.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        s.start();
        this.server = s;
        return s;
    }

    @Test
    void x8_exactHostOnTheAllowlistIsPermitted() throws Exception {
        HttpServer s = start("/ok", ex -> {
            byte[] body = "hi".getBytes();
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        AllowlistHttpCaller caller = new AllowlistHttpCaller(
                AllowlistHttpCaller.newSharedClient(), List.of("localhost"), Clock.systemUTC());
        HttpReply reply = caller.send(new HttpCall("GET", "http://localhost:" + s.getAddress().getPort() + "/ok",
                Map.of(), new byte[0]));
        assertThat(reply.status()).isEqualTo(200);
        assertThat(new String(reply.body())).isEqualTo("hi");
    }

    @Test
    void x8_aHostNotOnTheAllowlistIsRefused() throws Exception {
        HttpServer s = start("/ok", ex -> {
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        AllowlistHttpCaller caller = new AllowlistHttpCaller(
                AllowlistHttpCaller.newSharedClient(), List.of("some-other-host.test"), Clock.systemUTC());
        assertThatThrownBy(() -> caller.send(new HttpCall("GET",
                "http://localhost:" + s.getAddress().getPort() + "/ok", Map.of(), new byte[0])))
                .isInstanceOf(HttpCallRefusedException.class);
    }

    @Test
    void x8_httpToANonLoopbackHostIsRefusedBeforeAnyNetworkCall() throws Exception {
        // No server is even started for this host — refusal must happen purely from the URL,
        // never attempt a connection (a real DNS/connect failure would be a different exception).
        AllowlistHttpCaller caller = new AllowlistHttpCaller(
                AllowlistHttpCaller.newSharedClient(), List.of("external.example.test"), Clock.systemUTC());
        assertThatThrownBy(() -> caller.send(new HttpCall("GET", "http://external.example.test/x",
                Map.of(), new byte[0])))
                .as("mutant: allow http off loopback").isInstanceOf(HttpCallRefusedException.class);
    }

    @Test
    void x8_aRedirectIsHandedBackNotFollowed() throws Exception {
        HttpServer s = start("/redirect", ex -> {
            ex.getResponseHeaders().add("Location", "http://localhost:1/elsewhere");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        AllowlistHttpCaller caller = new AllowlistHttpCaller(
                AllowlistHttpCaller.newSharedClient(), List.of("localhost"), Clock.systemUTC());
        HttpReply reply = caller.send(new HttpCall("GET", "http://localhost:" + s.getAddress().getPort() + "/redirect",
                Map.of(), new byte[0]));
        assertThat(reply.status()).as("mutant: follow redirects").isEqualTo(302);
        List<String> location = reply.headers().entrySet().stream()
                .filter(e -> e.getKey().equalsIgnoreCase("Location"))
                .findFirst().map(Map.Entry::getValue).orElse(List.of());
        assertThat(location).contains("http://localhost:1/elsewhere");
    }

    @Test
    void x8_theTimeoutIsCutToTheRemainingInvocationDeadline() throws Exception {
        HttpServer s = start("/slow", ex -> {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            try {
                ex.sendResponseHeaders(200, -1);
            } catch (Exception ignored) {
                // client will already have given up
            }
            ex.close();
        });
        AllowlistHttpCaller caller = new AllowlistHttpCaller(
                AllowlistHttpCaller.newSharedClient(), List.of("localhost"), Clock.systemUTC());
        HttpCall call = new HttpCall("GET", "http://localhost:" + s.getAddress().getPort() + "/slow",
                Map.of(), new byte[0]);

        Instant deadline = Clock.systemUTC().instant().plusMillis(300);
        long start = System.nanoTime();
        assertThatThrownBy(() -> ScopedValue.where(InvocationDeadline.CURRENT, deadline)
                .call(() -> caller.send(call)))
                .as("mutant: use the default 30s ceiling regardless of the deadline")
                .isInstanceOf(HttpTimeoutException.class);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
        // The deadline was ~300ms away; the server sleeps 5s. A elapsed time nowhere near either
        // AllowlistHttpCaller#DEFAULT_CALL_TIMEOUT (30s) or the server's 5s pins that the SHORTER,
        // deadline-derived timeout is what actually governed the call.
        assertThat(elapsed).as("mutant: use the default 30s ceiling regardless of the deadline")
                .isLessThan(Duration.ofSeconds(3));
    }

    @Test
    void x8b_theCallsOwnTimeoutIsHonouredWhenTighterThanTheDefault() throws Exception {
        HttpServer s = start("/slow", ex -> {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            try {
                ex.sendResponseHeaders(200, -1);
            } catch (Exception ignored) {
                // client will already have given up
            }
            ex.close();
        });
        AllowlistHttpCaller caller = new AllowlistHttpCaller(
                AllowlistHttpCaller.newSharedClient(), List.of("localhost"), Clock.systemUTC());
        // No InvocationDeadline bound here (NO_DEADLINE_BUDGET = 30s applies) — the call's OWN
        // 300ms timeout is the only thing tighter than the server's 5s sleep, so it alone can be
        // what causes a timeout this fast.
        HttpCall call = new HttpCall("GET", "http://localhost:" + s.getAddress().getPort() + "/slow",
                Map.of(), new byte[0], Duration.ofMillis(300));

        long start = System.nanoTime();
        assertThatThrownBy(() -> caller.send(call))
                .as("mutant: ignore HttpCall#timeout and always use the host default")
                .isInstanceOf(HttpTimeoutException.class);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
        assertThat(elapsed).as("mutant: ignore HttpCall#timeout and always use the host default")
                .isLessThan(Duration.ofSeconds(3));
    }
}
