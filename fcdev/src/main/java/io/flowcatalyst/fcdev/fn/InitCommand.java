package io.flowcatalyst.fcdev.fn;

import io.flowcatalyst.fcdev.Version;
import io.flowcatalyst.platform.shared.json.Json;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
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
    private static final String DEFAULT_PLATFORM_URL = "http://localhost:8080";
    private static final String DEFAULT_PACKAGE = "com.example.fn";
    private static final String HANDLER_CLASS_NAME = "Handler";

    @Option(names = {"-h", "--help"}, usageHelp = true, description = "show this help and exit")
    boolean help;

    @Parameters(index = "0", paramLabel = "<dir>", description = "directory to write the project into")
    String dir;

    @Option(names = "--runtime", paramLabel = "<runtime>", defaultValue = "jvm",
            description = "jvm or wasm (default: ${DEFAULT-VALUE}). wasm has no project template — "
                    + "writes manifest.json only, same as --manifest-only")
    String runtime;

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

        String runtimeValue = (runtime == null || runtime.isBlank()) ? "jvm" : runtime.toLowerCase(Locale.ROOT);
        if (!runtimeValue.equals("jvm") && !runtimeValue.equals("wasm")) {
            err.println("--runtime must be jvm or wasm");
            return 1;
        }
        boolean manifestOnlyEffective = manifestOnly || runtimeValue.equals("wasm");

        Path targetDir = Path.of(dir);
        String packageName = (pkg == null || pkg.isBlank()) ? DEFAULT_PACKAGE : pkg;
        String artifactId = (name == null || name.isBlank()) ? defaultArtifactId(targetDir) : name;
        String entrypoint = runtimeValue.equals("wasm") ? "handle" : packageName + "." + HANDLER_CLASS_NAME;
        String platformUrl = resolvePlatformUrl(root);

        Map<Path, byte[]> files = new LinkedHashMap<>();
        files.put(targetDir.resolve("manifest.json"),
                manifestJson(runtimeValue, entrypoint, platformUrl).getBytes(StandardCharsets.UTF_8));
        if (!manifestOnlyEffective) {
            files.put(targetDir.resolve("pom.xml"),
                    pomXml(packageName, artifactId, Version.functionApiVersion()).getBytes(StandardCharsets.UTF_8));
            Path handlerPath = targetDir.resolve("src/main/java")
                    .resolve(packageName.replace('.', '/'))
                    .resolve(HANDLER_CLASS_NAME + ".java");
            files.put(handlerPath, handlerJava(packageName, HANDLER_CLASS_NAME).getBytes(StandardCharsets.UTF_8));
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

        printNextSteps(out, manifestOnlyEffective, artifactId);
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

    private static void printNextSteps(PrintWriter out, boolean manifestOnlyEffective, String artifactId) {
        if (manifestOnlyEffective) {
            out.println("wrote manifest.json");
            return;
        }
        out.println("wrote pom.xml, manifest.json, src/main/java/…/Handler.java");
        out.println("next steps:");
        // Not yet published to any Maven repository (function-developer-surface.md): say so,
        // rather than let the author's first `mvn package` fail on an unresolvable dependency.
        out.println("  flowcatalyst-function-api " + Version.functionApiVersion()
                + " is not published to a Maven repository yet; install it once from a platform checkout:");
        out.println("    mvn -pl function-api install");
        out.println("  mvn package");
        out.println("  fcdev fn publish target/" + artifactId + ".jar --manifest manifest.json");
    }
}
