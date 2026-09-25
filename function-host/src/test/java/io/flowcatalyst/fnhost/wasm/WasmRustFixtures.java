package io.flowcatalyst.fnhost.wasm;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/// The committed `function-hello-rust` Wasm fixture (`docs/spec/function-rust-guest.md`
/// §2/§4) — mirrors [WasmJsFixtures], its own `rust/` directory and `SHA256SUMS`
/// so another agent changing the main `wasm/` or `wasm/js/` fixtures never collides
/// with this one. Rebuilt only by `make wasm-fixtures`
/// (`examples/function-hello-rust` + `cargo build --release --locked --target
/// wasm32-unknown-unknown`) — CI installs the Rust toolchain for the test guest
/// already, but the fixture is still committed so a build with no network/registry
/// access still passes.
public final class WasmRustFixtures {

    /// The committed guest's classpath resource.
    public static final String GUEST_RESOURCE = "/wasm/rust/function_hello_rust.wasm";

    private WasmRustFixtures() {
    }

    /// Copies the committed `function-hello-rust` module into `dir` (an
    /// artifact must be a file the artifact store can fetch and digest) and
    /// returns its path.
    public static Path guest(Path dir) {
        Path target = dir.resolve("function_hello_rust.wasm");
        try (InputStream in = WasmRustFixtures.class.getResourceAsStream(GUEST_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing test resource " + GUEST_RESOURCE
                        + " — run `make wasm-fixtures`");
            }
            Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
