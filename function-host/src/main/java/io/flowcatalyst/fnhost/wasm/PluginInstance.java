package io.flowcatalyst.fnhost.wasm;

import org.extism.sdk.chicory.Plugin;

import java.util.Objects;

/// One Extism plugin instance of one Wasm version: its own linear memory, its
/// own WASI streams. **Not thread-safe** (W0) — [InstancePool] hands it to
/// exactly one call at a time.
final class PluginInstance {

    private final Plugin plugin;
    private final GuestOutputStream stdout;
    private final GuestOutputStream stderr;

    PluginInstance(Plugin plugin, GuestOutputStream stdout, GuestOutputStream stderr) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.stdout = Objects.requireNonNull(stdout, "stdout");
        this.stderr = Objects.requireNonNull(stderr, "stderr");
    }

    /// Calls `export` with `input`; the guest's output on success.
    ///
    /// @throws RuntimeException the guest returned Extism's error code
    ///                          (`ExtismFunctionException`), trapped, or was
    ///                          interrupted (`WasmInterruptedException`)
    byte[] call(String export, byte[] input) {
        return plugin.call(export, input);
    }

    /// Emits any partial stdout/stderr line the call left behind.
    void flushOutput() {
        stdout.flush();
        stderr.flush();
    }
}
