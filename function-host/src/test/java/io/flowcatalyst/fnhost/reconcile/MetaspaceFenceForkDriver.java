package io.flowcatalyst.fnhost.reconcile;

import io.flowcatalyst.fnhost.load.FunctionRegistry;
import io.flowcatalyst.fnhost.load.JvmFunctionLoader;
import io.flowcatalyst.platform.function.Digest;
import io.flowcatalyst.platform.function.DnsLabel;
import io.flowcatalyst.platform.function.FunctionAddress;
import io.flowcatalyst.platform.function.Manifest;
import io.flowcatalyst.platform.function.artifact.FileArtifactStore;
import io.flowcatalyst.platform.function.artifact.Signatures;
import io.flowcatalyst.platform.shared.json.Json;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/// Runs, in a FORKED JVM (started by {@link MetaspaceFenceForkTest} with a
/// deliberately tiny `-XX:MaxMetaspaceSize`), the exact scenario
/// `docs/function-runner-report.md` reported as the anomaly: one
/// `reconcileOnce` eagerly loading a document with more class-heavy "typical"
/// functions than the metaspace fence can hold. This is production code
/// (`Reconciler`/`JvmFunctionLoader`), not a simulation — the ONLY thing
/// simulated is which jars are on disk (the bench's own pre-built,
/// byte-distinct `typical-*.jar` fixtures, `bench/function-host/artifacts`).
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
                    null, null, manifest, null, "app_fence", null, Map.of(), Map.of(), List.of()));
        }
        DesiredDocument doc = new DesiredDocument(entries, List.of(), List.of());
        fake.desiredStateReturns((p, etag) -> new ControlPlane.Fetched.Changed("etag1", doc));

        // THE original crash point, unguarded: `reconcileOnce` synchronously eager-loads
        // every warm entry. Before the fix this died with an uncaught OutOfMemoryError
        // partway through; it must now return normally regardless of the metaspace fence.
        reconciler.reconcileOnce(Instant.now());

        int loaded = registry.snapshot().size();
        HeartbeatReport lastHeartbeat = fake.heartbeats().getLast();
        long failedOom = lastHeartbeat.loaded().stream()
                .filter(e -> e.state() instanceof HeartbeatReport.LoadState.Failed f
                        && "LOAD:OUT_OF_METASPACE".equals(f.error()))
                .count();
        long reportedTotal = lastHeartbeat.loaded().size();

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
        System.out.println("FAILED_OOM=" + failedOom);
        System.out.println("HEARTBEAT_ENTRIES=" + reportedTotal);
        System.out.println("SECOND_CYCLE_OK=" + secondCycleOk);
        System.out.println("DONE");
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
