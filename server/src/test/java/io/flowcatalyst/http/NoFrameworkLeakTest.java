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

/// Pins `docs/spec/http-seam.md` §4 row 10: outside a short, named list of
/// bootstrap sites, nothing under `server/src/main` or `server/src/test` may
/// import `io.javalin.*` — every handler, filter and exception mapper is
/// written against the `io.flowcatalyst.http` seam and nothing else.
///
/// Scans the source tree rather than a hand-maintained file list, so a new
/// file that reaches for a Javalin type is caught the same day it is added.
///
/// Mutant (run by hand, not by CI): add `import io.javalin.http.Context;` to
/// any handler outside the allowed list — e.g. `platform/role/api/RoleApi.java`
/// — and this test fails, naming the offending file; reverting the import
/// makes it pass again.
class NoFrameworkLeakTest {

    private static final Pattern JAVALIN_IMPORT = Pattern.compile("^import io\\.javalin\\.", Pattern.MULTILINE);

    /// Path suffixes (POSIX-separated, relative to `server/src/{main,test}/java`)
    /// allowed to import Javalin directly — the seam's own adapter, the four
    /// bootstrap sites that build a `Javalin` app, the transport package they
    /// configure, and the test harness every other test builds on.
    private static final List<String> ALLOWED_PREFIXES = List.of(
            "io/flowcatalyst/http/javalin/");
    private static final List<String> ALLOWED_EXACT = List.of(
            "io/flowcatalyst/server/Server.java",
            "io/flowcatalyst/server/Metrics.java",
            "io/flowcatalyst/outbox/OutboxAdminApi.java",
            "io/flowcatalyst/mcp/McpServer.java",
            "io/flowcatalyst/platform/shared/TestHttp.java");
    private static final List<String> ALLOWED_TRANSPORT_PREFIX = List.of(
            "io/flowcatalyst/server/transport/");

    @Test
    void noFileOutsideTheAllowedListImportsJavalin() throws IOException {
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

    private static boolean isAllowed(String rel) {
        if (ALLOWED_EXACT.contains(rel)) return true;
        for (String prefix : ALLOWED_PREFIXES) {
            if (rel.startsWith(prefix)) return true;
        }
        for (String prefix : ALLOWED_TRANSPORT_PREFIX) {
            if (rel.startsWith(prefix)) return true;
        }
        return false;
    }

    private static final Pattern VERTX_IMPORT = Pattern.compile("^import io\\.vertx\\.", Pattern.MULTILINE);

    /// `docs/spec/vertx-listener.md` §1: only the Vert.x adapter package
    /// imports Vert.x. The bootstrap sites go through `VertxListener`, the
    /// harness through the same class — neither sees an `io.vertx` type.
    @Test
    void noFileOutsideTheVertxAdapterImportsVertx() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path root : List.of(Path.of("src/main/java"), Path.of("src/test/java"))) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> files = Files.walk(root)) {
                files.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                    String rel = root.relativize(p).toString().replace('\\', '/');
                    if (rel.startsWith("io/flowcatalyst/http/vertx/")) return;
                    if (rel.equals("io/flowcatalyst/http/NoFrameworkLeakTest.java")) return;
                    String content;
                    try {
                        content = Files.readString(p);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    if (VERTX_IMPORT.matcher(content).find()) offenders.add(root + "/" + rel);
                });
            }
        }
        org.assertj.core.api.Assertions.assertThat(offenders).as("files importing io.vertx outside io.flowcatalyst.http.vertx").isEmpty();
    }
}
