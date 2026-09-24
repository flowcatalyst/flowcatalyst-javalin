package io.flowcatalyst.fnhost.wasm;

import io.flowcatalyst.platform.function.Manifest;
import io.flowcatalyst.platform.shared.json.Json;
import run.endive.wabt.Wat2Wasm;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/// Wasm fixtures for the host's tests (`docs/spec/function-wasm-runtime.md` §5).
///
/// - The **behaviour** guest is the committed Rust module
///   `src/test/resources/wasm/fc_test_guest.wasm` (source in
///   `src/test/wasm-guests/fc-test-guest/`, rebuilt only by
///   `make wasm-fixtures`; [WasmFixturesTest] pins its sha256).
/// - **Refusal** fixtures are assembled from WAT text at test time with
///   Endive's `wabt`.
///
/// Public so the listener's, loader's and reconciler's tests (other packages)
/// share one copy.
public final class WasmFixtures {

    /// The committed guest's classpath resource.
    public static final String GUEST_RESOURCE = "/wasm/fc_test_guest.wasm";

    private WasmFixtures() {
    }

    /// Copies the committed guest into `dir` (an artifact must be a file the
    /// artifact store can fetch and digest) and returns its path.
    public static Path guest(Path dir) {
        Path target = dir.resolve("fc_test_guest.wasm");
        try (InputStream in = WasmFixtures.class.getResourceAsStream(GUEST_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing test resource " + GUEST_RESOURCE);
            }
            Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /// Assembles `wat` and writes the module to `dir/<name>.wasm`.
    public static Path wat(Path dir, String name, String wat) {
        Path target = dir.resolve(name + ".wasm");
        try {
            Files.write(target, Wat2Wasm.parse(wat));
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /// A stored `runtime: wasm` manifest.
    ///
    /// @param endpointsJson the `endpoints` array, verbatim
    public static Manifest manifest(String entrypoint, int maxConcurrency, int maxDurationMs, int wasmMemoryMb,
                                    String endpointsJson, List<String> config, List<String> secrets,
                                    List<String> httpAllow) {
        ObjectNode root = Json.MAPPER.createObjectNode();
        root.put("runtime", "wasm");
        root.put("entrypoint", entrypoint);
        root.put("pool", "pool");
        root.put("warm", false);
        ObjectNode limits = root.putObject("limits");
        limits.put("maxDurationMs", maxDurationMs);
        limits.put("maxConcurrency", maxConcurrency);
        limits.put("wasmMemoryMb", wasmMemoryMb);
        root.set("endpoints", Json.MAPPER.readTree(endpointsJson));
        ArrayNode configNode = root.putArray("config");
        config.forEach(configNode::add);
        ArrayNode secretsNode = root.putArray("secrets");
        secrets.forEach(secretsNode::add);
        ArrayNode allowNode = root.putArray("httpAllow");
        httpAllow.forEach(allowNode::add);
        return Manifest.readStored(root);
    }

    /// [#manifest] with one `auth: none` endpoint `/*`, nothing declared.
    public static Manifest manifest(String entrypoint, int maxConcurrency, int timeoutMs, int wasmMemoryMb) {
        return manifest(entrypoint, maxConcurrency, timeoutMs, wasmMemoryMb,
                "[{\"path\":\"/*\",\"auth\":\"none\",\"timeoutMs\":" + timeoutMs + "}]",
                List.of(), List.of(), List.of());
    }
}
