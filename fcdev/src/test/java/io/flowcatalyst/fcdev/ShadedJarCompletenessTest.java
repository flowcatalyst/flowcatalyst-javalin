package io.flowcatalyst.fcdev;

import io.flowcatalyst.fcdev.ShadedJarCompleteness.Missing;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The `shaded-jar-completeness` gate (`fcdev/pom.xml`) must catch the 2026-10-14 failure shape:
/// a platform class shaded into the jar whose dependency class was left out.
class ShadedJarCompletenessTest {

    @TempDir
    Path dir;

    @Test
    void aJarMissingAClassItsOwnCodeReferencesFails() throws IOException {
        Path classes = compileProbe();
        Path jar = jar(classes, "io/flowcatalyst/probe/Seeder.class"); // Tsid left out

        assertThat(ShadedJarCompleteness.check(jar))
                .containsExactly(new Missing("io.flowcatalyst.probe.Seeder", "io.flowcatalyst.probe.Tsid"));
        assertThatThrownBy(() -> ShadedJarCompleteness.main(new String[] {jar.toString()}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("io.flowcatalyst.probe.Seeder -> io.flowcatalyst.probe.Tsid");
    }

    @Test
    void aCompleteJarPasses() throws IOException {
        Path classes = compileProbe();
        Path jar = jar(classes, "io/flowcatalyst/probe/Seeder.class", "io/flowcatalyst/probe/Tsid.class");

        assertThat(ShadedJarCompleteness.check(jar)).isEmpty();
    }

    @Test
    void parsesJdepsRowsAndIgnoresOnlyAbsentAnnotationTypes() {
        // Verbatim shape of `jdeps --missing-deps` over the broken jar (70e7d1a3..d9cb6e07),
        // plus one of the SDK's jsr305 annotation rows that the fixed jar legitimately has.
        String out = """
                flowcatalyst-fcdev-0.0.1-SNAPSHOT.jar -> not found
                   io.flowcatalyst.platform.shared.tsid.EntityType    -> io.flowcatalyst.sdk.tsid.Tsid                      not found
                   io.flowcatalyst.sdk.generated.model.AddNoteRequest -> javax.annotation.Nonnull                           not found
                """;

        assertThat(ShadedJarCompleteness.parse(out)).containsExactly(
                new Missing("io.flowcatalyst.platform.shared.tsid.EntityType", "io.flowcatalyst.sdk.tsid.Tsid"));
    }

    /// `Seeder` calls `Tsid` the way the platform's seeder calls the SDK's Tsid.
    private Path compileProbe() throws IOException {
        Path src = Files.createDirectories(dir.resolve("src/io/flowcatalyst/probe"));
        Files.writeString(src.resolve("Tsid.java"), """
                package io.flowcatalyst.probe;
                public final class Tsid {
                    private Tsid() {}
                    public static String next() { return "0"; }
                }
                """);
        Files.writeString(src.resolve("Seeder.java"), """
                package io.flowcatalyst.probe;
                public final class Seeder {
                    private Seeder() {}
                    public static String seed() { return Tsid.next(); }
                }
                """);
        Path out = Files.createDirectories(dir.resolve("classes"));
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        int rc = javac.run(null, null, null, "-d", out.toString(),
                src.resolve("Tsid.java").toString(), src.resolve("Seeder.java").toString());
        assertThat(rc).as("javac exit").isZero();
        return out;
    }

    private Path jar(Path classes, String... entries) throws IOException {
        Path jar = dir.resolve("probe-" + entries.length + ".jar");
        try (OutputStream file = Files.newOutputStream(jar); JarOutputStream out = new JarOutputStream(file)) {
            for (String entry : entries) {
                out.putNextEntry(new JarEntry(entry));
                out.write(Files.readAllBytes(classes.resolve(entry)));
                out.closeEntry();
            }
        }
        return jar;
    }
}
