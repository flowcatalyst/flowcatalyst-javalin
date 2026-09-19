package io.flowcatalyst.fnhost.reconcile;

import io.flowcatalyst.fnhost.load.FixtureJars;
import io.flowcatalyst.platform.function.Digest;
import io.flowcatalyst.platform.function.DnsLabel;
import io.flowcatalyst.platform.function.FunctionAddress;
import io.flowcatalyst.platform.function.Manifest;
import io.flowcatalyst.platform.function.Runtime;
import io.flowcatalyst.platform.shared.json.Json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/// Shared fixtures for the reconciler's tests
/// (`docs/spec/function-host-reconciler.md` §3): a real, loadable function
/// jar built through D1's [FixtureJars] (never a binary committed to the
/// repo, same reasoning as `function-host-core.md` §3), and its content
/// digest — [io.flowcatalyst.platform.function.artifact.FileArtifactStore]
/// always recomputes and verifies it, so every test fixture's `Digest` must
/// be the fixture's REAL sha256, not a fabricated one.
final class TestFixtures {

    static final FunctionAddress ADDR_A = FunctionAddress.parse("recon.svc.a");
    static final FunctionAddress ADDR_B = FunctionAddress.parse("recon.svc.b");
    static final String ENTRYPOINT = "fixture.recon.Fn";

    private TestFixtures() {
    }

    /// A trivial `Function` jar whose bytes (and therefore digest) differ
    /// per `variant` — `Fn.V` embeds it as a compile-time constant.
    static Path functionJar(Path dir, String name, String variant) {
        Path jar = dir.resolve(name + ".jar");
        FixtureJars.builder()
                .source(ENTRYPOINT, """
                        package fixture.recon;
                        import io.flowcatalyst.function.*;
                        public final class Fn implements Function {
                            public static final String V = "%s";
                            public Result handle(Invocation in, FunctionContext ctx) { return Result.ack(); }
                        }
                        """.formatted(variant))
                .build(jar);
        return jar;
    }

    static Digest digestOf(Path file) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (var in = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    md.update(buffer, 0, n);
                }
            }
            return Digest.parse("sha256:" + HexFormat.of().formatHex(md.digest()));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /// A `file://` ref for `jar` — the reconciler's tests always exercise a
    /// real [io.flowcatalyst.platform.function.artifact.FileArtifactStore],
    /// never a fake one, so the fetch/cache/re-hash behaviour C1-C3 pin is
    /// exercised for real here too.
    static String fileRef(Path jar) {
        return jar.toUri().toString();
    }

    static Manifest jvmManifest(String pool, boolean warm) {
        return manifest("jvm", pool, warm);
    }

    static Manifest wasmManifest(String pool) {
        return manifest("wasm", pool, false);
    }

    private static Manifest manifest(String runtime, String pool, boolean warm) {
        String json = """
                {"runtime":"%s","entrypoint":"%s","pool":"%s","warm":%s}
                """.formatted(runtime, ENTRYPOINT, pool, warm);
        return Manifest.readStored(Json.MAPPER.readTree(json));
    }

    static DnsLabel pool(String value) {
        return new DnsLabel(value);
    }

    static Runtime jvm() {
        return Runtime.JVM;
    }
}
