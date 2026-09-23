package io.flowcatalyst.fnhost.reconcile;

import io.flowcatalyst.fnhost.FnHost;
import io.flowcatalyst.fnhost.load.FunctionRegistry;
import io.flowcatalyst.fnhost.load.JvmFunctionLoader;
import io.flowcatalyst.platform.function.Digest;
import io.flowcatalyst.platform.function.DnsLabel;
import io.flowcatalyst.platform.function.FunctionAddress;
import io.flowcatalyst.platform.function.Manifest;
import io.flowcatalyst.platform.function.artifact.FileArtifactStore;
import io.flowcatalyst.platform.function.artifact.Signatures;
import io.flowcatalyst.platform.shared.json.Json;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/// Runs, in a FORKED JVM (started by {@link MetaspaceFenceForkTest} with a
/// deliberately tiny `-XX:MaxMetaspaceSize`), the exact scenario
/// `docs/function-runner-report.md` reported as the anomaly, THEN extended
/// (`docs/spec/function-host-process.md` §3) to prove the fix end to end
/// through the real assembly: not just `Reconciler#reconcileOnce` in
/// isolation, but a real {@link FnHost#start} — observability listener,
/// guarded first reconcile, reconcile loop, and the FUNCTION listener
/// binding and actually serving an invocation — all in one forked process at
/// a real, tight metaspace fence. This is production code
/// (`FnHost`/`Reconciler`/`JvmFunctionLoader`), not a simulation — the ONLY
/// thing simulated is which jars are on disk (the bench's own pre-built,
/// byte-distinct `typical-*.jar` fixtures, `bench/function-host/artifacts`)
/// and the control plane (a `FakeControlPlane` in place of a real platform).
///
/// Prints one `KEY=value` line per fact the test asserts on, then `DONE` —
/// a process that never reaches `DONE` (killed, hung, or exits non-zero) is
/// itself a failure the parent test catches via the exit code / missing line.
public final class MetaspaceFenceForkDriver {

    private MetaspaceFenceForkDriver() {
    }

    public static void main(String[] args) throws Exception {
        String artifactsDir = args[0];
        int jarCount = Integer.parseInt(args[1]);

        Path cacheDir = Files.createTempDirectory("metaspace-fence-fork-cache");
        FakeControlPlane fake = new FakeControlPlane();
        FunctionRegistry registry = new FunctionRegistry(jarCount + 10);
        Reconciler reconciler = new Reconciler(new DnsLabel("pool"), "fork-host", fake,
                new FileArtifactStore(cacheDir), new Signatures.Off(), new JvmFunctionLoader(), registry);

        Manifest manifest = Manifest.readStored(Json.MAPPER.readTree("""
                {"runtime":"jvm","entrypoint":"fixture.typical.TypicalFn","pool":"default","warm":true,
                 "limits":{"maxDurationMs":5000,"maxConcurrency":5},
                 "endpoints":[{"path":"/*","auth":"none"}]}
                """));

        List<DesiredDocument.Entry> entries = new ArrayList<>();
        for (int i = 0; i < jarCount; i++) {
            Path jar = Path.of(artifactsDir, "typical-" + pad(i) + ".jar");
            Digest digest = digestOf(jar);
            FunctionAddress address = FunctionAddress.parse("fence.svc.f" + pad(i));
            entries.add(new DesiredDocument.Entry(address, "fnc_" + i, "v_" + i, 1,
                    DesiredDocument.Role.LIVE, DesiredDocument.Mode.WARM, digest, jar.toUri().toString(),
                    null, null, manifest, null, "app_fence", null, Map.of(), Map.of(), List.of(), List.of()));
        }
        DesiredDocument doc = new DesiredDocument(entries, List.of(), List.of());
        fake.desiredStateReturns((p, etag) -> new ControlPlane.Fetched.Changed("etag1", doc));

        HostEnv env = new HostEnv(new DnsLabel("pool"), "http://127.0.0.1:1", "client-1", "secret-1", "fork-host",
                new Signatures.Off(), jarCount + 10, cacheDir, 0, 512, 3, 0, false, 16);
        FnHost host = new FnHost(env, reconciler, registry);

        // THE original crash point, unguarded: FnHost#start's first reconcile synchronously
        // eager-loads every warm entry. Before item 1's headroom guard and item 2's guarded
        // catch, this died with an uncaught OutOfMemoryError partway through, the FUNCTION
        // listener never bound, and the process kept running with /ready answering 200 forever
        // on the observability listener alone. It must now return normally, with the FUNCTION
        // listener bound, regardless of the metaspace fence.
        host.start();

        int loaded = registry.snapshot().size();
        HeartbeatReport lastHeartbeat = fake.heartbeats().getLast();
        long failedHeadroom = countReason(lastHeartbeat, "LOAD:METASPACE_HEADROOM");
        long failedOom = countReason(lastHeartbeat, "LOAD:OUT_OF_METASPACE");
        long reportedTotal = lastHeartbeat.loaded().size();

        int port = host.port();
        boolean portOpen = port > 0;

        int invokeStatus = -1;
        FunctionAddress toInvoke = registry.snapshot().stream().findFirst()
                .map(FunctionRegistry.Snapshot::address).orElse(null);
        if (portOpen && toInvoke != null) {
            invokeStatus = invoke(port, toInvoke);
        }

        boolean secondCycleOk;
        try {
            fake.desiredStateReturns((p, etag) -> new ControlPlane.Fetched.NotModified());
            reconciler.reconcileOnce(Instant.now()); // proves the process is still fully alive/serving
            secondCycleOk = true;
        } catch (Throwable t) {
            secondCycleOk = false;
            t.printStackTrace(System.out);
        }

        System.out.println("DOCUMENT_SIZE=" + entries.size());
        System.out.println("LOADED=" + loaded);
        System.out.println("FAILED_HEADROOM=" + failedHeadroom);
        System.out.println("FAILED_OOM=" + failedOom);
        System.out.println("HEARTBEAT_ENTRIES=" + reportedTotal);
        System.out.println("PORT_OPEN=" + portOpen);
        System.out.println("INVOKE_STATUS=" + invokeStatus);
        System.out.println("SECOND_CYCLE_OK=" + secondCycleOk);
        System.out.println("DONE");

        host.close();
    }

    private static long countReason(HeartbeatReport report, String reason) {
        return report.loaded().stream()
                .filter(e -> e.state() instanceof HeartbeatReport.LoadState.Failed f && reason.equals(f.error()))
                .count();
    }

    /// A valid body against `TypicalFn`'s own schema (`{"name","amount"}`) —
    /// a loaded function must answer 200, not merely accept the connection.
    private static int invoke(int port, FunctionAddress address) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/functions/" + address.render() + "/x"))
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"fence-check\",\"amount\":1}"))
                .build();
        HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
        return response.statusCode();
    }

    private static String pad(int i) {
        return String.format("%03d", i);
    }

    private static Digest digestOf(Path jar) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(jar)) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) {
                md.update(buffer, 0, n);
            }
        }
        return Digest.parse("sha256:" + HexFormat.of().formatHex(md.digest()));
    }
}
