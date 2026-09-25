package io.flowcatalyst.fcdev.fn;

import io.flowcatalyst.fcdev.Version;
import io.flowcatalyst.platform.shared.json.Json;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import tools.jackson.databind.JsonNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/// `fn init <dir>` (`docs/spec/function-manifest-authoring.md` M3):
/// scaffolds a starter function project derived from `examples/function-hello`.
/// **Local only** — unlike every other `fn` subcommand, this one never builds
/// an [FnClient] or resolves platform credentials; it only reads
/// `--platform-url`/`FLOWCATALYST_PLATFORM_URL`/`fn-cli.json` for the
/// `$schema` pointer it writes into `manifest.json`, and falls back to the
/// local fcdev default when none of those name one.
@Command(name = "init", sortOptions = false,
        description = "Scaffold a starter function project (local only — never contacts the platform)")
public final class InitCommand implements Callable<Integer> {

    /// `fcdev start`'s own default (`StartOptions`: `FC_API_PORT` default `8080`,
    /// `StartCommand#writeFnCliCredentials`: `"http://localhost:" + apiPort`) —
    /// what a freshly-started local fcdev answers at when nothing else names a URL.
    static final String LOCAL_REPO = "lib/m2";
    static final String FUNCTION_API_JAR_RESOURCE = "/fn-init/flowcatalyst-function-api.jar";
    /// `--lang js` (`docs/spec/function-js-guest.md` §3): `@flowcatalyst/function`'s
    /// source, zipped the same way as [#FUNCTION_API_JAR_RESOURCE], extracted into
    /// [#JS_LIBRARY_DIR].
    static final String FUNCTION_JS_ZIP_RESOURCE = "/fn-init/flowcatalyst-function-js.zip";
    static final String JS_LIBRARY_DIR = "lib/flowcatalyst-function";
    /// `--lang rust` (`docs/spec/function-rust-guest.md` §3): `flowcatalyst-function`'s
    /// (Rust) source, zipped the same way, extracted into [#RUST_LIBRARY_DIR] — the
    /// same directory name as [#JS_LIBRARY_DIR] since exactly one of the two is ever
    /// written into a given scaffold.
    static final String FUNCTION_RUST_ZIP_RESOURCE = "/fn-init/flowcatalyst-function-rust.zip";
    static final String RUST_LIBRARY_DIR = "lib/flowcatalyst-function";
    private static final String DEFAULT_PLATFORM_URL = "http://localhost:8080";
    private static final String DEFAULT_PACKAGE = "com.example.fn";
    private static final String HANDLER_CLASS_NAME = "Handler";

    @Option(names = {"-h", "--help"}, usageHelp = true, description = "show this help and exit")
    boolean help;

    @Parameters(index = "0", paramLabel = "<dir>", description = "directory to write the project into")
    String dir;

    @Option(names = "--runtime", paramLabel = "<runtime>", defaultValue = "jvm",
            description = "jvm or wasm (default: ${DEFAULT-VALUE}). wasm has no project template — "
                    + "writes manifest.json only, same as --manifest-only, UNLESS --lang js/rust names one")
    String runtime;

    @Option(names = "--lang", paramLabel = "<lang>", defaultValue = "java",
            description = "java, js or rust (default: ${DEFAULT-VALUE}). js scaffolds a JavaScript "
                    + "project (runtime: wasm) — package.json, tsconfig.json, src/index.ts and "
                    + "the @flowcatalyst/function library, ready for `npm install && npm run build`. "
                    + "rust scaffolds a Rust project (runtime: wasm) — Cargo.toml, src/lib.rs and "
                    + "the flowcatalyst-function crate, ready for `cargo build --release --target "
                    + "wasm32-unknown-unknown`")
    String lang;

    @Option(names = "--package", paramLabel = "<java.package>",
            description = "the generated handler's package (default: " + DEFAULT_PACKAGE + ")")
    String pkg;

    @Option(names = "--name", paramLabel = "<artifactId>",
            description = "the generated pom's artifactId (default: <dir>'s own name)")
    String name;

    @Option(names = "--manifest-only", description = "write manifest.json only — no pom.xml/handler class")
    boolean manifestOnly;

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        FnCommand root = FnCommand.of(spec);
        PrintWriter out = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();

        String langValue = (lang == null || lang.isBlank()) ? "java" : lang.toLowerCase(Locale.ROOT);
        if (!langValue.equals("java") && !langValue.equals("js") && !langValue.equals("rust")) {
            err.println("--lang must be java, js or rust");
            return 1;
        }

        String runtimeValue;
        if (langValue.equals("js") || langValue.equals("rust")) {
            // A JS or Rust function declares runtime: wasm — build concern, not an author choice
            // (docs/spec/function-js-guest.md §3's plan ruling D4, mirrored for Rust by
            // docs/spec/function-rust-guest.md §3). --runtime defaults to "jvm" (its own @Option
            // default), so only an EXPLICIT, conflicting --runtime is refused — --lang js/rust
            // alone, or --lang js/rust --runtime wasm, both proceed.
            boolean runtimeExplicit = spec.commandLine().getParseResult().hasMatchedOption("--runtime");
            if (runtimeExplicit && !"wasm".equalsIgnoreCase(runtime)) {
                err.println("--lang " + langValue + " scaffolds a wasm function — omit --runtime or pass --runtime wasm");
                return 1;
            }
            runtimeValue = "wasm";
        } else {
            runtimeValue = (runtime == null || runtime.isBlank()) ? "jvm" : runtime.toLowerCase(Locale.ROOT);
            if (!runtimeValue.equals("jvm") && !runtimeValue.equals("wasm")) {
                err.println("--runtime must be jvm or wasm");
                return 1;
            }
        }
        // --lang java (the default) keeps today's behaviour exactly: --runtime wasm without
        // --lang writes manifest.json only, same as --manifest-only. This flag is never
        // consulted below when langValue is "js" or "rust" — those branches always write their
        // own project template — so it needs no js/rust exception of its own.
        boolean manifestOnlyEffective = manifestOnly || runtimeValue.equals("wasm");

        Path targetDir = Path.of(dir);
        String packageName = (pkg == null || pkg.isBlank()) ? DEFAULT_PACKAGE : pkg;
        String artifactId = (name == null || name.isBlank()) ? defaultArtifactId(targetDir) : name;
        String entrypoint = runtimeValue.equals("wasm") ? "handle" : packageName + "." + HANDLER_CLASS_NAME;
        String platformUrl = resolvePlatformUrl(root);

        Map<Path, byte[]> files = new LinkedHashMap<>();
        files.put(targetDir.resolve("manifest.json"),
                manifestJson(runtimeValue, entrypoint, platformUrl).getBytes(StandardCharsets.UTF_8));
        if (langValue.equals("js")) {
            files.put(targetDir.resolve("package.json"), packageJsonJs(artifactId).getBytes(StandardCharsets.UTF_8));
            files.put(targetDir.resolve("tsconfig.json"), TSCONFIG_JS.getBytes(StandardCharsets.UTF_8));
            files.put(targetDir.resolve("src/index.ts"), indexTs(artifactId).getBytes(StandardCharsets.UTF_8));
            files.put(targetDir.resolve("README.md"), README_JS.getBytes(StandardCharsets.UTF_8));
            // @flowcatalyst/function ships with the scaffold, the same reasoning as the JVM
            // function API below: the package is not on npm.
            files.putAll(functionJsLibraryFiles(targetDir));
        } else if (langValue.equals("rust")) {
            files.put(targetDir.resolve("Cargo.toml"), cargoTomlRust(artifactId).getBytes(StandardCharsets.UTF_8));
            files.put(targetDir.resolve("src/lib.rs"), libRs(artifactId).getBytes(StandardCharsets.UTF_8));
            files.put(targetDir.resolve("README.md"), README_RUST.getBytes(StandardCharsets.UTF_8));
            // flowcatalyst-function ships with the scaffold, the same reasoning as
            // @flowcatalyst/function above: the crate is not on crates.io.
            files.putAll(functionRustLibraryFiles(targetDir));
        } else if (!manifestOnlyEffective) {
            files.put(targetDir.resolve("pom.xml"),
                    pomXml(packageName, artifactId, Version.current()).getBytes(StandardCharsets.UTF_8));
            Path handlerPath = targetDir.resolve("src/main/java")
                    .resolve(packageName.replace('.', '/'))
                    .resolve(HANDLER_CLASS_NAME + ".java");
            files.put(handlerPath, handlerJava(packageName, HANDLER_CLASS_NAME).getBytes(StandardCharsets.UTF_8));
            // The function API ships with the scaffold (owner, 2026-09-24): a project-local Maven
            // repository the generated pom names, so `mvn package` resolves it with no install step
            // and no system scope. Versioned as the fcdev that carried it — a release version, so
            // it resolves from a plain file repository with no snapshot metadata or update checks.
            String v = Version.current();
            Path repoDir = targetDir.resolve(LOCAL_REPO).resolve("io/flowcatalyst/flowcatalyst-function-api").resolve(v);
            files.put(repoDir.resolve("flowcatalyst-function-api-" + v + ".jar"), functionApiJar());
            files.put(repoDir.resolve("flowcatalyst-function-api-" + v + ".pom"),
                    functionApiPom(v).getBytes(StandardCharsets.UTF_8));
        }

        var existing = new TreeSet<Path>();
        for (Path p : files.keySet()) {
            if (Files.exists(p)) existing.add(p);
        }
        if (!existing.isEmpty()) {
            err.println("refusing to overwrite existing file(s), nothing written: "
                    + existing.stream().map(Path::toString).collect(Collectors.joining(", ")));
            return 1;
        }

        try {
            for (var entry : files.entrySet()) {
                Path p = entry.getKey();
                if (p.getParent() != null) {
                    Files.createDirectories(p.getParent());
                }
                Files.write(p, entry.getValue());
            }
        } catch (IOException e) {
            err.println(e.getMessage());
            return 1;
        }

        printNextSteps(out, langValue, manifestOnlyEffective, artifactId);
        return 0;
    }

    private static String defaultArtifactId(Path targetDir) {
        Path fileName = targetDir.toAbsolutePath().normalize().getFileName();
        return fileName == null ? "function" : fileName.toString();
    }

    /// Resolution order: `--platform-url` flag, `FLOWCATALYST_PLATFORM_URL`, `fn-cli.json`'s
    /// `platformUrl` (written by `fcdev start`), else [#DEFAULT_PLATFORM_URL] — unlike
    /// [FnCredentials#resolve], this never throws: `fn init` writes a best-effort `$schema`
    /// pointer, it does not need a usable credential set to do its job.
    private static String resolvePlatformUrl(FnCommand root) {
        String flag = blankToNull(root.platformUrl);
        if (flag != null) return flag;
        String env = blankToNull(root.env().get("FLOWCATALYST_PLATFORM_URL"));
        if (env != null) return env;
        Path file = root.paths().fnCliCredentialsPath();
        if (Files.isRegularFile(file)) {
            try {
                JsonNode node = Json.MAPPER.readTree(Files.readString(file));
                String fromFile = blankToNull(node.path("platformUrl").isString()
                        ? node.path("platformUrl").asString() : null);
                if (fromFile != null) return fromFile;
            } catch (IOException | RuntimeException ignored) {
                // A corrupt/unreadable fn-cli.json is treated as "no file", same as
                // FnCredentials#resolve — falls through to the hard-coded default below.
            }
        }
        return DEFAULT_PLATFORM_URL;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static String manifestJson(String runtimeValue, String entrypoint, String platformUrl) {
        return """
                {
                  "$schema": "%s/api/schemas/function-manifest.json",
                  "runtime": "%s",
                  "entrypoint": "%s",
                  "endpoints": [
                    { "path": "/hello", "auth": "platform", "methods": ["GET"] }
                  ]
                }
                """.formatted(platformUrl, runtimeValue, entrypoint);
    }

    private static String pomXml(String groupId, String artifactId, String functionApiVersion) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                  <modelVersion>4.0.0</modelVersion>

                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>0.1.0-SNAPSHOT</version>
                  <packaging>jar</packaging>

                  <properties>
                    <maven.compiler.release>21</maven.compiler.release>
                    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                  </properties>

                  <!-- fcdev fn init wrote the function API here; it is not in any public repository. -->
                  <repositories>
                    <repository>
                      <id>fcdev-function-api</id>
                      <url>file://${project.basedir}/lib/m2</url>
                    </repository>
                  </repositories>

                  <dependencies>
                    <dependency>
                      <groupId>io.flowcatalyst</groupId>
                      <artifactId>flowcatalyst-function-api</artifactId>
                      <version>%s</version>
                      <scope>provided</scope>
                    </dependency>
                  </dependencies>

                  <build>
                    <finalName>%s</finalName>
                    <plugins>
                      <plugin>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-shade-plugin</artifactId>
                        <version>3.6.2</version>
                        <executions>
                          <execution>
                            <phase>package</phase>
                            <goals>
                              <goal>shade</goal>
                            </goals>
                          </execution>
                        </executions>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """.formatted(groupId, artifactId, functionApiVersion, artifactId);
    }

    /// The function API jar fcdev carries (zipped from function-api's classes at build time,
    /// `fcdev/pom.xml`'s `fn-init-function-api` execution).
    private static byte[] functionApiJar() {
        try (var in = InitCommand.class.getResourceAsStream(FUNCTION_API_JAR_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("fcdev was built without " + FUNCTION_API_JAR_RESOURCE);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static String functionApiPom(String version) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>io.flowcatalyst</groupId>
                  <artifactId>flowcatalyst-function-api</artifactId>
                  <version>%s</version>
                  <packaging>jar</packaging>
                </project>
                """.formatted(version);
    }

    private static String handlerJava(String packageName, String className) {
        return """
                package %s;

                import io.flowcatalyst.function.Function;
                import io.flowcatalyst.function.FunctionContext;
                import io.flowcatalyst.function.Request;
                import io.flowcatalyst.function.Result;

                /// Generated by `fcdev fn init` — replace this with your own logic.
                /// Declared in manifest.json as a `platform`-authenticated endpoint at `/hello`.
                public final class %s implements Function {

                    @Override
                    public void init(FunctionContext ctx) {
                    }

                    @Override
                    public Result handle(Request in, FunctionContext ctx) {
                        return Result.json(200, "{\\"message\\":\\"hello from %s\\"}");
                    }

                    @Override
                    public void stop() {
                    }
                }
                """.formatted(packageName, className, className);
    }

    private static void printNextSteps(PrintWriter out, String langValue, boolean manifestOnlyEffective,
                                        String artifactId) {
        if (langValue.equals("js")) {
            out.println("wrote package.json, tsconfig.json, manifest.json, src/index.ts, README.md, and "
                    + "@flowcatalyst/function under " + JS_LIBRARY_DIR);
            out.println("next steps (needs extism-js, wasm-opt and wasm-merge on PATH — docs/functions.md):");
            out.println("  npm install");
            out.println("  npm run build");
            out.println("  fcdev fn publish dist/function.wasm --manifest manifest.json");
            return;
        }
        if (langValue.equals("rust")) {
            out.println("wrote Cargo.toml, manifest.json, src/lib.rs, README.md, and "
                    + "flowcatalyst-function under " + RUST_LIBRARY_DIR);
            out.println("next steps (needs the wasm32-unknown-unknown target — docs/functions.md):");
            out.println("  rustup target add wasm32-unknown-unknown");
            out.println("  cargo build --release --target wasm32-unknown-unknown");
            out.println("  fcdev fn publish target/wasm32-unknown-unknown/release/" + cargoCrateName(artifactId)
                    + ".wasm --manifest manifest.json");
            return;
        }
        if (manifestOnlyEffective) {
            out.println("wrote manifest.json");
            return;
        }
        out.println("wrote pom.xml, manifest.json, src/main/java/…/Handler.java, and the function API ("
                + Version.current() + ") under " + LOCAL_REPO);
        out.println("next steps:");
        out.println("  mvn package");
        out.println("  fcdev fn publish target/" + artifactId + ".jar --manifest manifest.json");
    }

    // ── --lang js (docs/spec/function-js-guest.md §3) ──────────────────────────────────────────

    private static final String TSCONFIG_JS = """
            {
              "compilerOptions": {
                "target": "ES2020",
                "module": "ES2020",
                "moduleResolution": "Bundler",
                "lib": [],
                "types": ["@extism/js-pdk"],
                "strict": true,
                "skipLibCheck": true,
                "noEmit": true
              },
              "include": ["src/**/*"]
            }
            """;

    private static final String README_JS = """
            # A FlowCatalyst JavaScript function

            Generated by `fcdev fn init --lang js` (`docs/spec/function-js-guest.md` §3). JavaScript
            functions run **through Wasm** (QuickJS via the Extism JS PDK); `manifest.json` declares
            `"runtime": "wasm"`.

            ## Toolchain

            - Node 18+ / npm
            - [`extism-js`](https://github.com/extism/js-pdk) 1.6.x on `PATH`
            - [Binaryen](https://github.com/WebAssembly/binaryen)'s `wasm-opt` and `wasm-merge` on
              `PATH` (`brew install binaryen`)

            ## Build

            ```bash
            npm install
            npm run build
            ```

            This bundles `src/index.ts` to CJS with `esbuild` (`dist/index.js`), then compiles it to
            a Wasm module with `extism-js` against `@flowcatalyst/function`'s shipped interface file
            (`dist/function.wasm`).

            ## Publish

            ```bash
            fcdev fn publish dist/function.wasm --manifest manifest.json
            ```

            See `docs/functions.md` "JavaScript functions" for what works and what does not (no Node
            built-ins; bundle npm packages; QuickJS is an interpreter — fine for glue and webhooks,
            not heavy compute).
            """;

    private static String packageJsonJs(String artifactId) {
        return """
                {
                  "name": "%s",
                  "version": "0.1.0",
                  "private": true,
                  "type": "module",
                  "scripts": {
                    "build": "npm run bundle && npm run compile",
                    "bundle": "esbuild src/index.ts --bundle --format=cjs --target=es2020 --outfile=dist/index.js",
                    "compile": "extism-js dist/index.js -i node_modules/@flowcatalyst/function/interface.d.ts -o dist/function.wasm"
                  },
                  "dependencies": {
                    "@flowcatalyst/function": "file:%s"
                  },
                  "devDependencies": {
                    "@extism/js-pdk": "^1.1.1",
                    "esbuild": "^0.24.0",
                    "typescript": "^5.7.0"
                  }
                }
                """.formatted(artifactId, JS_LIBRARY_DIR);
    }

    private static String indexTs(String artifactId) {
        return """
                import { handler, Result } from "@flowcatalyst/function";

                // Generated by `fcdev fn init --lang js` — replace this with your own logic.
                // Declared in manifest.json as a `platform`-authenticated endpoint at `/hello`.
                export const handle = handler((_req, _ctx) => {
                    return Result.json(200, { message: "hello from %s" });
                });
                """.formatted(artifactId);
    }

    /// `@flowcatalyst/function`'s source, zipped by `fcdev/pom.xml`'s `fn-init-function-js`
    /// execution (the same technique as [#functionApiJar]) and extracted here into
    /// [#JS_LIBRARY_DIR] — fcdev ships the library with the scaffold (owner, 2026-09-24: the
    /// package is not on npm), matching the JVM function API's own local-repository treatment.
    private static Map<Path, byte[]> functionJsLibraryFiles(Path targetDir) {
        Map<Path, byte[]> out = new LinkedHashMap<>();
        try (InputStream in = InitCommand.class.getResourceAsStream(FUNCTION_JS_ZIP_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("fcdev was built without " + FUNCTION_JS_ZIP_RESOURCE);
            }
            try (ZipInputStream zip = new ZipInputStream(in)) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if (entry.isDirectory()) {
                        continue;
                    }
                    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                    zip.transferTo(buffer);
                    out.put(targetDir.resolve(JS_LIBRARY_DIR).resolve(entry.getName()), buffer.toByteArray());
                }
            }
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return out;
    }

    // ── --lang rust (docs/spec/function-rust-guest.md §3) ──────────────────────────────────────

    private static final String README_RUST = """
            # A FlowCatalyst Rust function

            Generated by `fcdev fn init --lang rust` (`docs/spec/function-rust-guest.md` §3). Rust
            functions run **through Wasm** (compiled straight to `wasm32-unknown-unknown` with the
            Extism Rust PDK); `manifest.json` declares `"runtime": "wasm"`.

            ## Toolchain

            - Rust (`rustup`) with the `wasm32-unknown-unknown` target:
              `rustup target add wasm32-unknown-unknown`
            - `cargo`

            ## Build

            ```bash
            cargo build --release --target wasm32-unknown-unknown
            ```

            The built module lands under `target/wasm32-unknown-unknown/release/`.

            ## Publish

            ```bash
            fcdev fn publish target/wasm32-unknown-unknown/release/<crate>.wasm --manifest manifest.json
            ```

            See `docs/functions.md` "Rust functions" for what the `flowcatalyst-function` crate
            offers and its limits.
            """;

    /// A valid Cargo package name from an arbitrary `--name`/directory-derived `artifactId`:
    /// lowercase, `[a-z0-9_-]` only, never starting with something other than a letter (Cargo
    /// itself is stricter than an npm package name, which `--lang js` did not need to sanitise).
    private static String cargoPackageName(String artifactId) {
        String sanitized = artifactId.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "-");
        if (sanitized.isEmpty() || !Character.isLetter(sanitized.charAt(0))) {
            sanitized = "fn-" + sanitized;
        }
        return sanitized;
    }

    /// The `.wasm` filename `cargo build` writes for [#cargoPackageName]'s crate name — Cargo
    /// replaces `-` with `_` in the compiled artifact's file name.
    private static String cargoCrateName(String artifactId) {
        return cargoPackageName(artifactId).replace('-', '_');
    }

    private static String cargoTomlRust(String artifactId) {
        return """
                [package]
                name = "%s"
                version = "0.1.0"
                edition = "2021"
                publish = false

                [lib]
                crate-type = ["cdylib"]
                path = "src/lib.rs"

                [dependencies]
                flowcatalyst-function = { path = "%s" }
                extism-pdk = "=1.4.1"
                serde_json = "1"

                [profile.release]
                opt-level = "z"
                lto = true
                codegen-units = 1
                panic = "abort"
                """.formatted(cargoPackageName(artifactId), RUST_LIBRARY_DIR);
    }

    private static String libRs(String artifactId) {
        return """
                use extism_pdk::plugin_fn;
                use flowcatalyst_function::{handler, FunctionResult};

                // Generated by `fcdev fn init --lang rust` — replace this with your own logic.
                // Declared in manifest.json as a `platform`-authenticated endpoint at `/hello`.
                #[plugin_fn]
                pub fn handle(_input: String) -> extism_pdk::FnResult<String> {
                    handler(|_req, _ctx| -> Result<FunctionResult, String> {
                        FunctionResult::json(200, &serde_json::json!({"message": "hello from %s"}))
                            .map_err(|e| e.to_string())
                    })
                }
                """.formatted(artifactId);
    }

    /// `flowcatalyst-function`'s (Rust) source, zipped by `fcdev/pom.xml`'s `fn-init-function-rust`
    /// execution (the same technique as [#functionJsLibraryFiles]) and extracted here into
    /// [#RUST_LIBRARY_DIR] — fcdev ships the crate with the scaffold (the crate is not on
    /// crates.io), matching the JS library's and the JVM function API's own local-copy treatment.
    private static Map<Path, byte[]> functionRustLibraryFiles(Path targetDir) {
        Map<Path, byte[]> out = new LinkedHashMap<>();
        try (InputStream in = InitCommand.class.getResourceAsStream(FUNCTION_RUST_ZIP_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("fcdev was built without " + FUNCTION_RUST_ZIP_RESOURCE);
            }
            try (ZipInputStream zip = new ZipInputStream(in)) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if (entry.isDirectory()) {
                        continue;
                    }
                    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                    zip.transferTo(buffer);
                    out.put(targetDir.resolve(RUST_LIBRARY_DIR).resolve(entry.getName()), buffer.toByteArray());
                }
            }
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return out;
    }
}
