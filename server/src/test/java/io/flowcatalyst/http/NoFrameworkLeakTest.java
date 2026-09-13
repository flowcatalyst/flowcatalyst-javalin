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

/// Pins `docs/spec/http-seam.md` §4 row 10 and `docs/spec/vertx-listener.md`
/// §1: every handler, filter and exception mapper is written against the
/// `io.flowcatalyst.http` seam and nothing else. Vert.x is the API/metrics
/// listener; Javalin survives only as [io.flowcatalyst.mcp.McpServer]'s own,
/// independent listener (a second Javalin instance embedding the MCP SDK's
/// servlet transport, never wired through this seam) — so this file runs
/// two scans, not one.
///
/// Scans the source tree rather than a hand-maintained file list, so a new
/// file that reaches for a Javalin or Vert.x type is caught the same day it
/// is added.
///
/// Mutants (run by hand, not by CI): add `import io.javalin.http.Context;` or
/// `import io.vertx.core.Vertx;` to any handler outside the allowed lists —
/// e.g. `platform/role/api/RoleApi.java` — and the matching test fails,
/// naming the offending file; reverting the import makes it pass again.
class NoFrameworkLeakTest {

    private static final Pattern JAVALIN_IMPORT = Pattern.compile("^import io\\.javalin\\.", Pattern.MULTILINE);

    /// Only [io.flowcatalyst.mcp.McpServer] is allowed to import Javalin
    /// directly: its own, independent listener, never the API/metrics
    /// listener and never wired through this seam.
    private static final List<String> JAVALIN_ALLOWED_EXACT = List.of(
            "io/flowcatalyst/mcp/McpServer.java");

    @Test
    void noFileOutsideTheAllowedListImportsJavalin() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path root : List.of(Path.of("src/main/java"), Path.of("src/test/java"))) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> files = Files.walk(root)) {
                files.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                    String rel = root.relativize(p).toString().replace('\\', '/');
                    if (JAVALIN_ALLOWED_EXACT.contains(rel)) return;
                    String content;
                    try {
                        content = Files.readString(p);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    if (JAVALIN_IMPORT.matcher(content).find()) {
                        offenders.add(root + "/" + rel);
                    }
                });
            }
        }
        assertThat(offenders)
                .as("files importing io.javalin outside the allowed list (docs/spec/http-seam.md §4 row 10)")
                .isEmpty();
    }

    private static final Pattern VERTX_IMPORT = Pattern.compile("^import io\\.vertx\\.", Pattern.MULTILINE);

    /// Path suffixes (POSIX-separated, relative to `server/src/{main,test}/java`)
    /// allowed to import Vert.x directly: the adapter package itself, this
    /// test, and two test-only fixtures that stand up a throwaway Vert.x
    /// client/server as "an arbitrary HTTP/2 target" — a test tool, not
    /// application code reaching past the seam.
    private static final List<String> VERTX_ALLOWED_PREFIXES = List.of(
            "io/flowcatalyst/http/vertx/");
    private static final List<String> VERTX_ALLOWED_EXACT = List.of(
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
                    if (isVertxAllowed(rel)) return;
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

    private static boolean isVertxAllowed(String rel) {
        if (VERTX_ALLOWED_EXACT.contains(rel)) return true;
        for (String prefix : VERTX_ALLOWED_PREFIXES) {
            if (rel.startsWith(prefix)) return true;
        }
        return false;
    }
}
