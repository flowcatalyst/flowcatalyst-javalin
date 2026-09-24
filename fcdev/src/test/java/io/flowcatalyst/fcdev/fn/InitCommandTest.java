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

    /// The scaffold carries the real function API: the jar under lib/m2 holds the same
    /// `Function.class` bytes this fcdev was built against.
    @Test
    void theScaffoldCarriesTheRealFunctionApiInItsLocalRepository(@TempDir Path projectDir) throws Exception {
        Path dir = projectDir.resolve("myfn");
        var r = FnCliTestSupport.run(env(), "fn", "init", dir.toString());
        assertThat(r.exit()).as(r.err()).isZero();
        String v = io.flowcatalyst.fcdev.Version.current();
        Path jar = dir.resolve("lib/m2/io/flowcatalyst/flowcatalyst-function-api/" + v + "/flowcatalyst-function-api-" + v + ".jar");
        assertThat(dir.resolve("lib/m2/io/flowcatalyst/flowcatalyst-function-api/" + v + "/flowcatalyst-function-api-" + v + ".pom")).exists();
        byte[] expected;
        try (var in = Function.class.getResourceAsStream("Function.class")) {
            expected = in.readAllBytes();
        }
        try (var zip = new java.util.zip.ZipFile(jar.toFile())) {
            var entry = zip.getEntry("io/flowcatalyst/function/Function.class");
            assertThat(entry).as("Function.class in the shipped jar").isNotNull();
            try (var in = zip.getInputStream(entry)) {
                assertThat(in.readAllBytes()).isEqualTo(expected);
            }
        }
        String pom = Files.readString(dir.resolve("pom.xml"));
        assertThat(pom).contains("<url>file://${project.basedir}/lib/m2</url>");
        assertThat(pom).containsPattern("<artifactId>flowcatalyst-function-api</artifactId>\\s*<version>"
                + java.util.regex.Pattern.quote(v) + "</version>");
    }

    /// The whole point: a freshly scaffolded project builds with plain `mvn package` and nothing
    /// installed — the function API resolves from the scaffold's own lib/m2 (not offline mode:
    /// `-o` blocks file:// repositories too; the build plugins come from the local cache).
    /// Skipped (not failed) when no `mvn` is on the PATH.
    @Test
    void aFreshScaffoldBuildsWithPlainMavenPackage(@TempDir Path projectDir) throws Exception {
        Path dir = projectDir.resolve("myfn");
        var r = FnCliTestSupport.run(env(), "fn", "init", dir.toString(), "--name", "myfn");
        assertThat(r.exit()).as(r.err()).isZero();
        java.util.Optional<Path> mvn = java.util.Arrays.stream(System.getenv().getOrDefault("PATH", "").split(java.io.File.pathSeparator))
                .map(p -> Path.of(p, "mvn")).filter(Files::isExecutable).findFirst();
        org.junit.jupiter.api.Assumptions.assumeTrue(mvn.isPresent(), "no mvn on PATH");
        Path log = projectDir.resolve("mvn.log");
        Process p = new ProcessBuilder(mvn.get().toString(), "-q", "-B", "package")
                .directory(dir.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        assertThat(p.waitFor(180, java.util.concurrent.TimeUnit.SECONDS)).as("mvn finished").isTrue();
        assertThat(p.exitValue()).as(Files.readString(log)).isZero();
        assertThat(dir.resolve("target/myfn.jar")).exists();
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
    /// Pins that `--lang js` below does NOT change this — the default `--lang java`'s
    /// `--runtime wasm` behaviour is untouched.
    @Test
    void wasmRuntimeWritesManifestOnly(@TempDir Path projectDir) throws IOException {
        Path dir = projectDir.resolve("myfn");
        var r = FnCliTestSupport.run(env(), "fn", "init", dir.toString(), "--runtime", "wasm");
        assertThat(r.exit()).as(r.err()).isZero();
        try (var files = Files.list(dir)) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactly("manifest.json");
        }
    }

    // ── --lang js (docs/spec/function-js-guest.md §3) ───────────────────────────────────────────

    /// The default stays `java` — a plain `fn init` (no `--lang`) never writes any of the JS
    /// scaffold's files.
    @Test
    void defaultLangIsJavaNotJs(@TempDir Path projectDir) throws IOException {
        Path dir = projectDir.resolve("myfn");
        var r = FnCliTestSupport.run(env(), "fn", "init", dir.toString());
        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(Files.exists(dir.resolve("package.json"))).as("mutant: default to js").isFalse();
        assertThat(Files.exists(dir.resolve("pom.xml"))).isTrue();
    }

    /// The full JS scaffold: `package.json` depends on `@flowcatalyst/function` via
    /// `file:lib/flowcatalyst-function` (never a public npm registry — the package is not
    /// published there), the manifest declares `runtime: wasm`, and the library itself is
    /// present under `lib/flowcatalyst-function`.
    @Test
    void langJsWritesTheScaffoldWithTheLibraryDependencyAndWasmManifest(@TempDir Path projectDir)
            throws IOException {
        Path dir = projectDir.resolve("myfn");
        var r = FnCliTestSupport.run(env(), "fn", "init", dir.toString(), "--lang", "js");
        assertThat(r.exit()).as(r.err()).isZero();

        assertThat(Files.exists(dir.resolve("pom.xml"))).as("no Java scaffold alongside it").isFalse();
        assertThat(Files.isRegularFile(dir.resolve("package.json"))).isTrue();
        assertThat(Files.isRegularFile(dir.resolve("tsconfig.json"))).isTrue();
        assertThat(Files.isRegularFile(dir.resolve("src/index.ts"))).isTrue();
        assertThat(Files.isRegularFile(dir.resolve("README.md"))).isTrue();

        String packageJson = Files.readString(dir.resolve("package.json"));
        assertThat(packageJson)
                .as("mutant: point at npm instead of the shipped local copy")
                .contains("\"@flowcatalyst/function\": \"file:lib/flowcatalyst-function\"");

        JsonNode manifest = readManifest(dir);
        assertThat(manifest.path("runtime").asString()).isEqualTo("wasm");
        assertThat(manifest.path("entrypoint").asString()).isEqualTo("handle");
        FunctionLimits defaults = FunctionLimits.defaults();
        assertThat(Manifest.parseStrict(manifest, Runtime.WASM, defaults, ClientCeilings.of(defaults)).runtime())
                .isEqualTo(Runtime.WASM);

        assertThat(Files.isRegularFile(dir.resolve("lib/flowcatalyst-function/package.json")))
                .as("mutant: the library never gets extracted").isTrue();
        assertThat(Files.isRegularFile(dir.resolve("lib/flowcatalyst-function/interface.d.ts"))).isTrue();
        assertThat(Files.isRegularFile(dir.resolve("lib/flowcatalyst-function/src/index.ts"))).isTrue();
        assertThat(Files.isRegularFile(dir.resolve("lib/flowcatalyst-function/src/handler.ts"))).isTrue();
    }

    /// The shipped library is the REAL `clients/function-js` source, byte for byte — not some
    /// separately hand-maintained copy that could silently drift from it.
    @Test
    void langJsLibraryMatchesTheRealSourceByteForByte(@TempDir Path projectDir) throws IOException {
        Path dir = projectDir.resolve("myfn");
        var r = FnCliTestSupport.run(env(), "fn", "init", dir.toString(), "--lang", "js");
        assertThat(r.exit()).as(r.err()).isZero();

        Path realSource = Path.of("..", "clients", "function-js", "src", "handler.ts");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isRegularFile(realSource),
                "clients/function-js not checked out beside fcdev");
        assertThat(Files.readString(dir.resolve("lib/flowcatalyst-function/src/handler.ts")))
                .isEqualTo(Files.readString(realSource));
    }

    /// `runtime: wasm` is not optional for `--lang js` (D4: JavaScript is a build concern, the
    /// host sees a module) — an explicit, CONFLICTING `--runtime jvm` is refused rather than
    /// silently overridden or silently accepted as a broken combination.
    @Test
    void langJsRefusesAnExplicitConflictingRuntime(@TempDir Path projectDir) throws IOException {
        Path dir = projectDir.resolve("myfn");
        var r = FnCliTestSupport.run(env(), "fn", "init", dir.toString(), "--lang", "js", "--runtime", "jvm");
        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("--lang js");
        assertThat(Files.exists(dir)).as("nothing written on refusal").isFalse();
    }

    /// `--lang js --runtime wasm` (the only non-conflicting explicit combination) behaves
    /// exactly like `--lang js` alone.
    @Test
    void langJsAcceptsAnExplicitNonConflictingRuntime(@TempDir Path projectDir) throws IOException {
        Path dir = projectDir.resolve("myfn");
        var r = FnCliTestSupport.run(env(), "fn", "init", dir.toString(), "--lang", "js", "--runtime", "wasm");
        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(Files.isRegularFile(dir.resolve("package.json"))).isTrue();
    }

    @Test
    void unknownLangIsRefused(@TempDir Path projectDir) {
        Path dir = projectDir.resolve("myfn");
        var r = FnCliTestSupport.run(env(), "fn", "init", dir.toString(), "--lang", "python");
        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("--lang");
        assertThat(Files.exists(dir)).isFalse();
    }

    /// The whole point, JS's own version of [#aFreshScaffoldBuildsWithPlainMavenPackage]: a
    /// freshly scaffolded JS project actually bundles and compiles to a real Wasm module with
    /// nothing but what `fn init` wrote. Skipped when the toolchain
    /// (`docs/spec/function-js-guest.md` §0: `extism-js`, `wasm-opt`, `wasm-merge`, `npm`) is not
    /// on `PATH` — CI has none of it.
    @Test
    void aFreshJsScaffoldBuildsToARealWasmModule(@TempDir Path projectDir) throws Exception {
        Path dir = projectDir.resolve("myfn");
        var r = FnCliTestSupport.run(env(), "fn", "init", dir.toString(), "--lang", "js", "--name", "myfn");
        assertThat(r.exit()).as(r.err()).isZero();

        java.util.function.Predicate<String> onPath = bin -> java.util.Arrays.stream(
                        System.getenv().getOrDefault("PATH", "").split(java.io.File.pathSeparator))
                .anyMatch(p -> Files.isExecutable(Path.of(p, bin)));
        org.junit.jupiter.api.Assumptions.assumeTrue(onPath.test("npm") && onPath.test("extism-js")
                        && onPath.test("wasm-opt") && onPath.test("wasm-merge"),
                "npm/extism-js/wasm-opt/wasm-merge not all on PATH");

        Path log = projectDir.resolve("npm.log");
        Process install = new ProcessBuilder("npm", "install").directory(dir.toFile())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        assertThat(install.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)).as("npm install finished").isTrue();
        assertThat(install.exitValue()).as(Files.readString(log)).isZero();

        Process build = new ProcessBuilder("npm", "run", "build").directory(dir.toFile())
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile())).start();
        assertThat(build.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)).as("npm run build finished").isTrue();
        assertThat(build.exitValue()).as(Files.readString(log)).isZero();

        assertThat(dir.resolve("dist/function.wasm")).exists();
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
