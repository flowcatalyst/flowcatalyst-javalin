package io.flowcatalyst.fcdev.fn;

import io.flowcatalyst.function.Function;
import io.flowcatalyst.platform.function.ClientCeilings;
import io.flowcatalyst.platform.function.FunctionLimits;
import io.flowcatalyst.platform.function.Manifest;
import io.flowcatalyst.platform.function.Runtime;
import io.flowcatalyst.platform.shared.json.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// `fn init` (`docs/spec/function-manifest-authoring.md` M3): local only — no [FakePlatform]
/// anywhere in this class, unlike every other `fn` command test — scaffolds a starter project
/// derived from `examples/function-hello`.
class InitCommandTest {

    @TempDir
    Path stateDir;

    private Map<String, String> env() {
        return Map.of("XDG_DATA_HOME", stateDir.resolve("state").toString());
    }

    /// The generated pom names the function-api this reactor actually builds — not fcdev's
    /// release semver, which is a different number and resolves to nothing.
    @Test
    void generatedPomNamesTheFunctionApiVersionTheReactorBuilds(@TempDir Path projectDir) throws IOException {
        Path dir = projectDir.resolve("myfn");
        var r = FnCliTestSupport.run(env(), "fn", "init", dir.toString());
        assertThat(r.exit()).as(r.err()).isZero();

        String functionApiPom = Files.readString(Path.of("..", "function-api", "pom.xml"));
        var m = java.util.regex.Pattern.compile("<version>([^<]+)</version>").matcher(functionApiPom);
        assertThat(m.find()).isTrue();
        String reactorVersion = m.group(1);

        String pom = Files.readString(dir.resolve("pom.xml"));
        assertThat(pom).containsPattern("<artifactId>flowcatalyst-function-api</artifactId>\\s*<version>"
                + java.util.regex.Pattern.quote(reactorVersion) + "</version>");
        assertThat(r.out()).as("the author is told the artifact is not published yet")
                .contains("not published to a Maven repository yet");
    }

    // ── the generated manifest parses under the REAL publish reader ────────────────────────────

    @Test
    void generatedManifestParsesUnderParseStrictForJvm(@TempDir Path projectDir) {
        Path dir = projectDir.resolve("myfn");
        var r = FnCliTestSupport.run(env(), "fn", "init", dir.toString());
        assertThat(r.exit()).as(r.err()).isZero();

        JsonNode root = readManifest(dir);
        FunctionLimits defaults = FunctionLimits.defaults();
        Manifest manifest = Manifest.parseStrict(root, Runtime.JVM, defaults, ClientCeilings.of(defaults));
        assertThat(manifest.entrypoint()).isEqualTo("com.example.fn.Handler");
    }

    @Test
    void generatedManifestParsesUnderParseStrictForWasm(@TempDir Path projectDir) {
        Path dir = projectDir.resolve("myfn");
        var r = FnCliTestSupport.run(env(), "fn", "init", dir.toString(), "--runtime", "wasm");
        assertThat(r.exit()).as(r.err()).isZero();

        JsonNode root = readManifest(dir);
        FunctionLimits defaults = FunctionLimits.defaults();
        Manifest manifest = Manifest.parseStrict(root, Runtime.WASM, defaults, ClientCeilings.of(defaults));
        assertThat(manifest.runtime()).isEqualTo(Runtime.WASM);
    }

    /// `--runtime wasm` writes manifest.json only — there is no Wasm project template
    /// (mutant: write a pom.xml/handler anyway, this fails on the missing/unexpected file).
    @Test
    void wasmRuntimeWritesManifestOnly(@TempDir Path projectDir) throws IOException {
        Path dir = projectDir.resolve("myfn");
        var r = FnCliTestSupport.run(env(), "fn", "init", dir.toString(), "--runtime", "wasm");
        assertThat(r.exit()).as(r.err()).isZero();
        try (var files = Files.list(dir)) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactly("manifest.json");
        }
    }

    // ── the generated handler compiles, and its class name equals the manifest's entrypoint ────

    @Test
    void generatedHandlerCompilesAndItsClassNameEqualsTheManifestEntrypoint(@TempDir Path projectDir,
                                                                             @TempDir Path classesOut)
            throws Exception {
        Path dir = projectDir.resolve("myfn");
        var r = FnCliTestSupport.run(env(), "fn", "init", dir.toString(), "--package", "com.acme.sample");
        assertThat(r.exit()).as(r.err()).isZero();

        JsonNode root = readManifest(dir);
        String entrypoint = root.path("entrypoint").asString();
        assertThat(entrypoint).isEqualTo("com.acme.sample.Handler");

        Path handlerSource = dir.resolve("src/main/java/com/acme/sample/Handler.java");
        assertThat(Files.isRegularFile(handlerSource)).as("generated handler source exists").isTrue();

        Class<?> loaded = compileAndLoad(handlerSource, entrypoint, classesOut);
        assertThat(loaded.getName()).isEqualTo(entrypoint);
        assertThat(Function.class.isAssignableFrom(loaded))
                .as("generated handler implements io.flowcatalyst.function.Function").isTrue();
    }

    private static Class<?> compileAndLoad(Path source, String binaryName, Path outDir) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("a JDK (not a JRE) is required to run this test").isNotNull();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, null)) {
            List<String> options = List.of("-d", outDir.toString(), "-classpath", System.getProperty("java.class.path"));
            Iterable<? extends JavaFileObject> units = fileManager.getJavaFileObjectsFromPaths(List.of(source));
            boolean ok = compiler.getTask(null, fileManager, diagnostics, options, null, units).call();
            assertThat(ok).as(diagnostics.getDiagnostics().toString()).isTrue();
        }
        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[]{outDir.toUri().toURL()},
                InitCommandTest.class.getClassLoader())) {
            return Class.forName(binaryName, true, loader);
        }
    }

    // ── refuses to overwrite ─────────────────────────────────────────────────────────────────

    @Test
    void preExistingManifestMakesInitExitOneAndLeavesEveryFileUntouched(@TempDir Path projectDir) throws Exception {
        Path dir = projectDir.resolve("myfn");
        Files.createDirectories(dir);
        String original = "{\"pre-existing\":true}";
        Files.writeString(dir.resolve("manifest.json"), original);
        String originalHash = sha256(dir.resolve("manifest.json"));

        var r = FnCliTestSupport.run(env(), "fn", "init", dir.toString());
        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("manifest.json");

        assertThat(Files.readString(dir.resolve("manifest.json"))).isEqualTo(original);
        assertThat(sha256(dir.resolve("manifest.json"))).isEqualTo(originalHash);
        assertThat(Files.exists(dir.resolve("pom.xml"))).as("nothing else was written either").isFalse();
        try (var files = Files.list(dir)) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactly("manifest.json");
        }
    }

    // ── --manifest-only ──────────────────────────────────────────────────────────────────────

    @Test
    void manifestOnlyWritesExactlyOneFile(@TempDir Path projectDir) throws IOException {
        Path dir = projectDir.resolve("myfn");
        var r = FnCliTestSupport.run(env(), "fn", "init", dir.toString(), "--manifest-only");
        assertThat(r.exit()).as(r.err()).isZero();
        try (var files = Files.list(dir)) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactly("manifest.json");
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────

    private static JsonNode readManifest(Path dir) {
        try {
            return Json.MAPPER.readTree(Files.readString(dir.resolve("manifest.json")));
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static String sha256(Path file) throws IOException, NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }
}
