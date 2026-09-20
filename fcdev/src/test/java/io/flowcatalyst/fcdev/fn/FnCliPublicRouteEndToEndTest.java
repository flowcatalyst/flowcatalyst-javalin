package io.flowcatalyst.fcdev.fn;

import io.flowcatalyst.fcdev.FcdevFunctionsFixture;
import io.flowcatalyst.fcdev.StartCommand;
import io.flowcatalyst.fnhost.FnHost;
import io.flowcatalyst.fnhost.load.FixtureJars;
import io.flowcatalyst.platform.shared.auth.Authenticator;
import io.flowcatalyst.platform.shared.tsid.EntityType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static io.flowcatalyst.fcdev.FcdevFunctionsFixture.adminPost;
import static io.flowcatalyst.fcdev.FcdevFunctionsFixture.obj;

import java.net.Socket;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/// End-to-end `fcdev fn domain` + the public listener, against the REAL dev
/// stack (`docs/spec/function-developer-surface.md` §2, extending E1's own
/// harness per its own instruction): `fn domain claim hello.localhost` ⇒
/// `VERIFIED` (dev-mode `.localhost` auto-verify, no DNS) → deploy a
/// function with that public route → a request through the PUBLIC port ⇒
/// 200. Same conventions as `FnCliEndToEndTest`: no `--client-id`/etc flags,
/// only `XDG_DATA_HOME` pointed at the fixture's own state dir.
@SuppressWarnings("deprecation")
class FnCliPublicRouteEndToEndTest {

    private final FcdevFunctionsFixture fixture = new FcdevFunctionsFixture();
    private Thread reconcilePump;
    private final AtomicBoolean stopPump = new AtomicBoolean();

    @AfterEach
    void shutdown() throws Exception {
        stopPump.set(true);
        if (reconcilePump != null) reconcilePump.join(5000);
        fixture.close();
    }

    private Map<String, String> cliEnv() {
        return Map.of("XDG_DATA_HOME", fixture.root.toString());
    }

    @Test
    void claimLocalhostDomainDeployAndReachThroughThePublicPort(@TempDir Path work) throws Exception {
        StartCommand.Started started = fixture.boot(Map.of());
        FnHost host = fixture.inProcessHost();
        reconcilePump = Thread.ofPlatform().name("reconcile-pump").start(() -> {
            while (!stopPump.get()) {
                host.triggerReconcile();
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        });

        String run = Long.toUnsignedString(System.nanoTime(), 36);
        String hostname = "hello-" + run + ".localhost";

        // ── fn domain claim: dev-mode .localhost auto-verifies at claim time ──
        var claim = FnCliTestSupport.run(cliEnv(), "fn", "domain", "claim", hostname);
        assertThat(claim.exit()).as(claim.out() + claim.err()).isZero();
        assertThat(claim.out()).as("mutant: .localhost must auto-verify under dev mode").contains("VERIFIED");

        var list = FnCliTestSupport.run(cliEnv(), "fn", "domain", "list");
        assertThat(list.exit()).as(list.err()).isZero();
        assertThat(list.out()).contains(hostname).contains("VERIFIED");

        // ── deploy a function whose manifest publishes a public route on that hostname.
        // The owning application is provisioned by an ANCHOR fixture first — the fn-cli
        // client itself holds no ADMIN_APPLICATION_CREATE (same discipline as
        // FnCliEndToEndTest); never a load-bearing assertion by itself. ──
        String appCode = "pubrt" + run;
        String[] anchor = {
                Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
                Authenticator.TEST_SCOPE, "ANCHOR",
                Authenticator.TEST_PERMISSIONS, "platform:*:*:*"};
        adminPost(started.apiPort(), anchor, "/api/applications",
                obj("code", appCode, "name", appCode, "type", "APPLICATION"), 201);
        String address = appCode + ".default.sample";
        Path jar = work.resolve("pub-fn.jar");
        FixtureJars.builder().source("fixture.pub.SampleFn", """
                package fixture.pub;
                import io.flowcatalyst.function.*;
                public final class SampleFn implements Function {
                    public Result handle(Request in, FunctionContext ctx) throws Exception {
                        return Result.json(200, "{\\"path\\":\\"" + in.path() + "\\"}");
                    }
                }
                """).build(jar);
        Path manifest = work.resolve("manifest.json");
        Files.writeString(manifest, """
                {"runtime":"jvm","entrypoint":"fixture.pub.SampleFn","pool":"default","warm":false,
                 "endpoints":[{"path":"/*","auth":"none"}],
                 "public":[{"hostname":"%s","pathPrefix":"/"}]}
                """.formatted(hostname));

        var deploy = FnCliTestSupport.run(cliEnv(), "fn", "deploy", jar.toString(), address,
                "--manifest", manifest.toString(), "--wait", "20s");
        assertThat(deploy.exit()).as(deploy.out() + deploy.err()).isZero();

        // ── the public listener's real, bound port (never probe-and-release — hard rule #4) ──
        int publicPort = awaitPositive(host::publicPort, "public listener never bound");

        var resp = awaitPublicOk(publicPort, hostname, "/x");
        assertThat(resp).as("mutant: the public listener never actually reaches the function")
                .contains("HTTP/1.1 200").contains("\"path\":\"/x\"");
    }

    private static int awaitPositive(java.util.function.IntSupplier supplier, String description) throws InterruptedException {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            int v = supplier.getAsInt();
            if (v > 0) return v;
            Thread.sleep(20);
        }
        throw new AssertionError(description);
    }

    /// Retries the raw request briefly — same reasoning as `FnCliEndToEndTest#awaitInvokeOk`:
    /// the reconcile pump runs concurrently, so `deploy --wait` returning READY can win a race
    /// against the host actually finishing the NEXT reconcile that materialises the public route.
    /// A raw socket is used deliberately (`java.net.http` forbids setting `Host`; see
    /// `RawHttpClient`'s own doc in `function-host`, mirrored here since fcdev has no dependency
    /// on function-host's TEST sources).
    private static String awaitPublicOk(int port, String hostname, String path) throws Exception {
        String last = "";
        for (int i = 0; i < 60; i++) {
            try (Socket socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(5000);
                OutputStream out = socket.getOutputStream();
                String req = "GET " + path + " HTTP/1.1\r\nHost: " + hostname + "\r\nConnection: close\r\n\r\n";
                out.write(req.getBytes(StandardCharsets.US_ASCII));
                out.flush();
                var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                }
                last = sb.toString();
                if (last.contains("HTTP/1.1 200")) {
                    return last;
                }
            } catch (Exception e) {
                last = e.toString();
            }
            Thread.sleep(50);
        }
        throw new AssertionError("public route never answered 200: " + last);
    }
}
