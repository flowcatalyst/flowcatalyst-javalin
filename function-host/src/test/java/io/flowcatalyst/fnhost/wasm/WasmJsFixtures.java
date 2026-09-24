package io.flowcatalyst.fnhost.wasm;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/// The committed `function-hello-js` Wasm fixture (`docs/spec/function-js-guest.md`
/// §2/§4) — mirrors [WasmFixtures], its own `js/` directory and `SHA256SUMS`
/// so another agent changing the main `wasm/` fixtures never collides with
/// this one. Rebuilt only by `make wasm-fixtures`
/// (`examples/function-hello-js` + `npm run build`) — CI has no JS→Wasm
/// toolchain.
public final class WasmJsFixtures {

    /// The committed guest's classpath resource.
    public static final String GUEST_RESOURCE = "/wasm/js/function_hello_js.wasm";

    private WasmJsFixtures() {
    }

    /// Copies the committed `function-hello-js` module into `dir` (an
    /// artifact must be a file the artifact store can fetch and digest) and
    /// returns its path.
    public static Path guest(Path dir) {
        Path target = dir.resolve("function_hello_js.wasm");
        try (InputStream in = WasmJsFixtures.class.getResourceAsStream(GUEST_RESOURCE)) {
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
