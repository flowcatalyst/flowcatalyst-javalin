package io.flowcatalyst.fnhost.load;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static io.flowcatalyst.fnhost.load.TestSupport.ADDRESS;
import static org.assertj.core.api.Assertions.assertThat;

/// L8 (`docs/spec/function-host-core.md` §3): every [Reason]
/// [JvmFunctionLoader#load] can return, each from a fixture jar built for
/// it. For the three refusals that are a pure jar scan (`NATIVE_LIBRARY`,
/// `SECURITY_PROVIDER`, `BUNDLES_API`), the fixture's entrypoint also
/// carries a `static` initialiser that writes a marker file — proving the
/// scan really does precede loading any class, not merely that the
/// eventual outcome is right. Mutant: move the scan after
/// `Class.forName`/`loadClass` — the marker file would then exist.
class RefusalTest {

    /// One row per suffix the spec names (§2.2) plus a top-level entry: a
    /// list that silently lost `.dylib` passed the single-`.so` test.
    @ParameterizedTest
    @ValueSource(strings = {"lib/x86_64/libfoo.so", "win/foo.dll", "darwin/libfoo.dylib", "libfoo.jnilib", "libbar.so"})
    void nativeLibraryEntryIsRefusedBeforeAnyClassLoads(String entry, @TempDir Path dir) throws Exception {
        Path marker = dir.resolve("native.marker");
        Path jar = markerFixture(dir, "native", marker)
                .entry(entry, new byte[] {1, 2, 3})
                .build(TestSupport.tempJar(dir, "native-lib"));

        LoadOutcome outcome = new JvmFunctionLoader().load(jar, "fixture.l8.MarkerProbe", ADDRESS, 1);

        assertThat(outcome).isInstanceOf(Refused.class);
        assertThat(((Refused) outcome).reason()).isEqualTo(Reason.NATIVE_LIBRARY);
        assertThat(((Refused) outcome).detail()).isEqualTo(entry);
        assertThat(Files.exists(marker)).as("the scan must precede loading, so the marker is never written").isFalse();
    }

    @Test
    void securityProviderEntryIsRefusedBeforeAnyClassLoads(@TempDir Path dir) throws Exception {
        Path marker = dir.resolve("provider.marker");
        Path jar = markerFixture(dir, "provider", marker)
                .entry("META-INF/services/java.security.Provider", "com.example.FakeProvider\n")
                .build(TestSupport.tempJar(dir, "security-provider"));

        LoadOutcome outcome = new JvmFunctionLoader().load(jar, "fixture.l8.MarkerProbe", ADDRESS, 1);

        assertThat(outcome).isInstanceOf(Refused.class);
        assertThat(((Refused) outcome).reason()).isEqualTo(Reason.SECURITY_PROVIDER);
        assertThat(Files.exists(marker)).as("the scan must precede loading, so the marker is never written").isFalse();
    }

    @Test
    void bundledApiClassIsRefusedBeforeAnyClassLoads(@TempDir Path dir) throws Exception {
        Path marker = dir.resolve("bundles-api.marker");
        Path jar = markerFixture(dir, "bundles", marker)
                .entry("io/flowcatalyst/function/Evil.class", new byte[] {1, 2, 3})
                .build(TestSupport.tempJar(dir, "bundles-api"));

        LoadOutcome outcome = new JvmFunctionLoader().load(jar, "fixture.l8.MarkerProbe", ADDRESS, 1);

        assertThat(outcome).isInstanceOf(Refused.class);
        assertThat(((Refused) outcome).reason()).isEqualTo(Reason.BUNDLES_API);
        assertThat(((Refused) outcome).detail()).isEqualTo("io/flowcatalyst/function/Evil.class");
        assertThat(Files.exists(marker)).as("the scan must precede loading, so the marker is never written").isFalse();
    }

    @Test
    void bundledApiClassInASubpackageIsNotRefused_exactPackageOnly(@TempDir Path dir) throws Exception {
        // io/flowcatalyst/function/sub/Evil.class is NOT the API package itself —
        // proves the BUNDLES_API scan matches the package exactly, like rule 2.
        Path jar = FixtureJars.builder()
                .source("fixture.l8.Plain", """
                        package fixture.l8;
                        import io.flowcatalyst.function.*;
                        public final class Plain implements Function {
                            public Result handle(Request in, FunctionContext ctx) { return Result.ack(); }
                        }
                        """)
                .entry("io/flowcatalyst/function/sub/Evil.class", new byte[] {1, 2, 3})
                .build(TestSupport.tempJar(dir, "bundles-subpackage"));

        LoadOutcome outcome = new JvmFunctionLoader().load(jar, "fixture.l8.Plain", ADDRESS, 1);

        assertThat(outcome).isInstanceOf(Loaded.class);
        ((Loaded) outcome).function().close();
    }

    @Test
    void entrypointNotFoundInJar(@TempDir Path dir) throws Exception {
        Path jar = FixtureJars.builder()
                .source("fixture.l8.SomethingElse", "package fixture.l8; public final class SomethingElse {}")
                .build(TestSupport.tempJar(dir, "no-entrypoint"));

        LoadOutcome outcome = new JvmFunctionLoader().load(jar, "fixture.l8.Missing", ADDRESS, 1);

        assertThat(outcome).isEqualTo(new Refused(Reason.ENTRYPOINT_NOT_FOUND, "fixture.l8.Missing"));
    }

    @Test
    void entrypointDoesNotImplementFunction(@TempDir Path dir) throws Exception {
        Path jar = FixtureJars.builder()
                .source("fixture.l8.NotAFunction", "package fixture.l8; public final class NotAFunction {}")
                .build(TestSupport.tempJar(dir, "not-a-function"));

        LoadOutcome outcome = new JvmFunctionLoader().load(jar, "fixture.l8.NotAFunction", ADDRESS, 1);

        assertThat(outcome).isEqualTo(new Refused(Reason.ENTRYPOINT_NOT_A_FUNCTION, "fixture.l8.NotAFunction"));
    }

    @Test
    void entrypointHasNoPublicNoArgConstructor(@TempDir Path dir) throws Exception {
        Path jar = FixtureJars.builder()
                .source("fixture.l8.NeedsArgs", """
                        package fixture.l8;
                        import io.flowcatalyst.function.*;
                        public final class NeedsArgs implements Function {
                            public NeedsArgs(String mustBeSupplied) {}
                            public Result handle(Request in, FunctionContext ctx) { return Result.ack(); }
                        }
                        """)
                .build(TestSupport.tempJar(dir, "not-instantiable"));

        LoadOutcome outcome = new JvmFunctionLoader().load(jar, "fixture.l8.NeedsArgs", ADDRESS, 1);

        assertThat(outcome).isInstanceOf(Refused.class);
        assertThat(((Refused) outcome).reason()).isEqualTo(Reason.ENTRYPOINT_NOT_INSTANTIABLE);
    }

    @Test
    void entrypointConstructorThrows(@TempDir Path dir) throws Exception {
        Path jar = FixtureJars.builder()
                .source("fixture.l8.ThrowsOnConstruct", """
                        package fixture.l8;
                        import io.flowcatalyst.function.*;
                        public final class ThrowsOnConstruct implements Function {
                            public ThrowsOnConstruct() { throw new IllegalStateException("nope"); }
                            public Result handle(Request in, FunctionContext ctx) { return Result.ack(); }
                        }
                        """)
                .build(TestSupport.tempJar(dir, "throws-on-construct"));

        LoadOutcome outcome = new JvmFunctionLoader().load(jar, "fixture.l8.ThrowsOnConstruct", ADDRESS, 1);

        assertThat(outcome).isInstanceOf(Refused.class);
        assertThat(((Refused) outcome).reason()).isEqualTo(Reason.ENTRYPOINT_NOT_INSTANTIABLE);
    }

    @Test
    void unreadableJarIsRefused(@TempDir Path dir) throws Exception {
        Path notAJar = dir.resolve("not-a-jar.jar");
        Files.writeString(notAJar, "this is not a zip file", StandardCharsets.UTF_8);

        LoadOutcome outcome = new JvmFunctionLoader().load(notAJar, "anything.AtAll", ADDRESS, 1);

        assertThat(outcome).isInstanceOf(Refused.class);
        assertThat(((Refused) outcome).reason()).isEqualTo(Reason.UNREADABLE_JAR);
    }

    /// A fixture whose entrypoint (`fixture.l8.MarkerProbe`) writes `marker`
    /// from a `static` initialiser — proof, for the pure-scan refusals,
    /// that the class was never loaded.
    private static FixtureJars.Builder markerFixture(Path dir, String label, Path marker) {
        String markerPath = marker.toString().replace("\\", "\\\\");
        return FixtureJars.builder()
                .source("fixture.l8.MarkerProbe", """
                        package fixture.l8;
                        import io.flowcatalyst.function.*;
                        public final class MarkerProbe implements Function {
                            static {
                                try {
                                    java.nio.file.Files.write(java.nio.file.Path.of("%s"), new byte[] {1});
                                } catch (java.io.IOException e) {
                                    throw new RuntimeException(e);
                                }
                            }
                            public Result handle(Request in, FunctionContext ctx) { return Result.ack(); }
                        }
                        """.formatted(markerPath));
    }
}
