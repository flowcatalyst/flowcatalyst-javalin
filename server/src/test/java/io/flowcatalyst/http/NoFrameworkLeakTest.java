package io.flowcatalyst.http;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// Pins `docs/spec/vertx-listener.md` §1: only [io.flowcatalyst.http.vertx]
/// imports Vert.x. Every handler, filter and exception mapper is written
/// against the `io.flowcatalyst.http` seam and nothing else — Javalin/Jetty
/// are gone from the codebase entirely (`docs/vertx-plan.md` Phase 3).
///
/// Scans the source tree rather than a hand-maintained file list, so a new
/// file that reaches for a Vert.x type is caught the same day it is added.
///
/// Mutant (run by hand, not by CI): add `import io.vertx.core.Vertx;` to any
/// handler outside the allowed list — e.g. `platform/role/api/RoleApi.java`
/// — and this test fails, naming the offending file; reverting the import
/// makes it pass again.
class NoFrameworkLeakTest {

    private static final Pattern VERTX_IMPORT = Pattern.compile("^import io\\.vertx\\.", Pattern.MULTILINE);

    /// Path suffixes (POSIX-separated, relative to `server/src/{main,test}/java`)
    /// allowed to import Vert.x directly: the adapter package itself, this
    /// test, and two test-only fixtures that stand up a throwaway Vert.x
    /// client/server as "an arbitrary HTTP/2 target" — a test tool, not
    /// application code reaching past the seam.
    private static final List<String> ALLOWED_PREFIXES = List.of(
            "io/flowcatalyst/http/vertx/");
    private static final List<String> ALLOWED_EXACT = List.of(
            "io/flowcatalyst/http/NoFrameworkLeakTest.java",
            "io/flowcatalyst/server/transport/Http2Test.java",
            "io/flowcatalyst/server/transport/HttpMediatorVersionTest.java");

    @Test
    void noFileOutsideTheVertxAdapterImportsVertx() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path root : List.of(Path.of("src/main/java"), Path.of("src/test/java"))) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> files = Files.walk(root)) {
                files.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                    String rel = root.relativize(p).toString().replace('\\', '/');
                    if (isAllowed(rel)) return;
                    String content;
                    try {
                        content = Files.readString(p);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    if (VERTX_IMPORT.matcher(content).find()) {
                        offenders.add(root + "/" + rel);
                    }
                });
            }
        }
        assertThat(offenders)
                .as("files importing io.vertx outside io.flowcatalyst.http.vertx (docs/spec/vertx-listener.md §1)")
                .isEmpty();
    }

    private static boolean isAllowed(String rel) {
        if (ALLOWED_EXACT.contains(rel)) return true;
        for (String prefix : ALLOWED_PREFIXES) {
            if (rel.startsWith(prefix)) return true;
        }
        return false;
    }
}
