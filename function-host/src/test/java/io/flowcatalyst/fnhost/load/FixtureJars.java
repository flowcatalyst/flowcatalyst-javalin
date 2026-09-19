package io.flowcatalyst.fnhost.load;

import io.flowcatalyst.function.Function;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

/// Test support: builds real fixture jars at test time, so a jar-isolation
/// test can never rot and nothing binary is committed
/// (`docs/spec/function-host-core.md` §3). Sources compile against the API
/// jar's own compiled classes only — found through `Function.class`'s own
/// code source, since the reactor resolves `function-api` from
/// `target/classes`, not a packaged jar (CLAUDE.md build hygiene: never
/// `mvn install` from a worktree).
final class FixtureJars {

    private FixtureJars() {
    }

    static Builder builder() {
        return new Builder();
    }

    /// Where `function-api`'s compiled classes live.
    static Path apiClassesDir() {
        try {
            URI location = Function.class
                    .getProtectionDomain().getCodeSource().getLocation().toURI();
            return Paths.get(location);
        } catch (URISyntaxException e) {
            throw new IllegalStateException("could not resolve function-api's classes directory", e);
        }
    }

    static final class Builder {

        private final List<SourceFile> sources = new ArrayList<>();
        private final Map<String, byte[]> rawEntries = new LinkedHashMap<>();
        private final List<Path> extraClasspath = new ArrayList<>();

        private Builder() {
        }

        /// One `.java` source to compile into the fixture jar.
        Builder source(String className, String code) {
            sources.add(new SourceFile(className, code));
            return this;
        }

        /// A raw zip entry, written byte-for-byte — a fake native library,
        /// a poisoned `META-INF/services/*` registration, or a bogus class
        /// under the API's own package (the `BUNDLES_API` fixtures). Never
        /// compiled, and never checked for validity: the refusal scan
        /// looks only at entry names.
        Builder entry(String name, byte[] content) {
            rawEntries.put(name, content);
            return this;
        }

        Builder entry(String name, String utf8Content) {
            return entry(name, utf8Content.getBytes(StandardCharsets.UTF_8));
        }

        /// An extra compile classpath entry, ahead of the API classes —
        /// for a fixture whose source needs to see another fixture's
        /// already-compiled classes.
        Builder classpath(Path dir) {
            extraClasspath.add(dir);
            return this;
        }

        Path build(Path targetJar) {
            try {
                Path classesDir = Files.createTempDirectory("fixture-classes-");
                if (!sources.isEmpty()) {
                    compile(sources, classesDir, extraClasspath);
                }
                writeJar(targetJar, classesDir, rawEntries);
                return targetJar;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private record SourceFile(String className, String code) {
    }

    private static void compile(List<SourceFile> sources, Path outputDir, List<Path> extraClasspath)
            throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("no system Java compiler available — running on a JRE, not a JDK?");
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager =
                compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            fileManager.setLocation(StandardLocation.CLASS_OUTPUT, List.of(outputDir.toFile()));

            List<Path> classpath = new ArrayList<>();
            classpath.add(apiClassesDir());
            classpath.addAll(extraClasspath);
            fileManager.setLocation(StandardLocation.CLASS_PATH, classpath.stream().map(Path::toFile).toList());

            List<JavaFileObject> units = sources.stream()
                    .<JavaFileObject>map(s -> new StringSource(s.className(), s.code()))
                    .toList();

            boolean ok = compiler.getTask(null, fileManager, diagnostics, List.of("-proc:none"), null, units).call();
            if (!ok) {
                StringBuilder message = new StringBuilder("fixture compilation failed:\n");
                for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
                    message.append(diagnostic).append('\n');
                }
                throw new IllegalStateException(message.toString());
            }
        }
    }

    private static void writeJar(Path targetJar, Path classesDir, Map<String, byte[]> rawEntries)
            throws IOException {
        if (targetJar.getParent() != null) {
            Files.createDirectories(targetJar.getParent());
        }
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(targetJar), manifest)) {
            if (Files.isDirectory(classesDir)) {
                try (Stream<Path> walk = Files.walk(classesDir)) {
                    List<Path> classFiles = walk.filter(Files::isRegularFile).sorted().toList();
                    for (Path classFile : classFiles) {
                        String entryName = classesDir.relativize(classFile).toString().replace('\\', '/');
                        jar.putNextEntry(new JarEntry(entryName));
                        Files.copy(classFile, jar);
                        jar.closeEntry();
                    }
                }
            }
            for (Map.Entry<String, byte[]> raw : rawEntries.entrySet()) {
                jar.putNextEntry(new JarEntry(raw.getKey()));
                jar.write(raw.getValue());
                jar.closeEntry();
            }
        }
    }

    private static final class StringSource extends SimpleJavaFileObject {

        private final String code;

        StringSource(String className, String code) {
            super(URI.create("string:///" + className.replace('.', '/') + Kind.SOURCE.extension), Kind.SOURCE);
            this.code = code;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return code;
        }
    }
}
