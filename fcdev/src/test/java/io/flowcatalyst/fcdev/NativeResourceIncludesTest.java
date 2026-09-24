package io.flowcatalyst.fcdev;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// A GraalVM native image contains only the classpath resources its `-H:IncludeResources`
/// pattern names; anything else is simply absent at run time. A new resource folder added to
/// `server` (the manifest `schemas/`, 2026-09-24) crashed the native fcdev at start with "missing
/// schemas/function-manifest.schema.json on the classpath", and the Sigstore trust root under
/// `function/` was missing too. This checks every resource file each native build ships against
/// its pattern: fc-server's covers the server module's resources, fcdev's covers the server's and
/// its own.
class NativeResourceIncludesTest {

    /// Resources deliberately left out of the native images, with the reason.
    private static final Set<String> NOT_READ_AT_RUN_TIME = Set.of(
            "frontend.source-commit",            // build bookkeeping: which SPA source commit is embedded
            "openapi/openapi.lock.source-commit", // build bookkeeping
            "function/README.md");                // documentation for the trust-root file beside it

    @Test
    void fcServersNativeImageIncludesEveryServerResource() throws IOException {
        Pattern include = includePattern(Path.of("..", "server", "pom.xml"));
        assertThat(uncovered(include, Path.of("..", "server", "src", "main", "resources"))).isEmpty();
    }

    @Test
    void fcdevsNativeImageIncludesEveryServerAndFcdevResource() throws IOException {
        Pattern include = includePattern(Path.of("pom.xml"));
        assertThat(uncovered(include, Path.of("..", "server", "src", "main", "resources"))).isEmpty();
        assertThat(uncovered(include, Path.of("src", "main", "resources"))).isEmpty();
    }

    private static List<String> uncovered(Pattern include, Path root) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile)
                    .map(f -> root.relativize(f).toString().replace('\\', '/'))
                    .filter(rel -> !rel.startsWith("META-INF/"))
                    .filter(rel -> !NOT_READ_AT_RUN_TIME.contains(rel))
                    .filter(rel -> !include.matcher(rel).matches())
                    .sorted()
                    .toList();
        }
    }

    private static Pattern includePattern(Path pom) throws IOException {
        Matcher m = Pattern.compile("-H:IncludeResources=([^<]+)</buildArg>").matcher(Files.readString(pom));
        assertThat(m.find()).as("an IncludeResources build arg in " + pom).isTrue();
        return Pattern.compile(m.group(1));
    }
}
