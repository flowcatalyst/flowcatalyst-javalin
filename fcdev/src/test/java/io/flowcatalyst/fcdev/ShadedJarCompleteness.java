package io.flowcatalyst.fcdev;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.spi.ToolProvider;

/// Build-time gate on the shaded fcdev jar: every `io.flowcatalyst` class in it must find every
/// class it references inside the jar (or the JDK). Run by `fcdev/pom.xml`'s
/// `shaded-jar-completeness` execution in the `package` phase, right after the shade plugin, so it
/// guards `-DskipTests` builds too (CI, the release workflow, the e2e runner).
///
/// Why it exists: from 70e7d1a3 (Tsid moved into `sdk/`) to 2026-10-14 the fcdev jar shipped
/// without the SDK. fcdev declared `flowcatalyst-sdk` with `test` scope, a direct declaration's
/// scope beats the transitive compile scope (server -> usecase -> sdk), so the SDK was off the
/// runtime classpath and the shade plugin left it out. The build was green; `fcdev start` died in
/// the seeder with `NoClassDefFoundError: io/flowcatalyst/sdk/tsid/Tsid`. A missing class is
/// invisible until the code path that loads it runs, so this asks `jdeps --missing-deps` instead
/// of hoping a boot reaches every path.
///
/// Test scope on purpose (as `io.flowcatalyst.tools.NativeReflectConfig` is for the native
/// profile): it never ships in the jar it inspects.
public final class ShadedJarCompleteness {

    /// Referenced but deliberately absent: annotation types only. The SDK's generated OpenAPI
    /// models carry jsr305 `@Nonnull` / `@Nullable`, which the SDK does not ship; a missing
    /// annotation class is skipped by the JVM rather than failing a load.
    static final List<String> IGNORED_PREFIXES = List.of("javax.annotation.");

    /// One `jdeps --missing-deps` row: `   <from> -> <to>   not found`.
    private static final Pattern ROW = Pattern.compile("^\\s+(\\S+)\\s+->\\s+(\\S+)\\s+not found\\s*$");

    record Missing(String from, String to) {
        @Override
        public String toString() {
            return from + " -> " + to;
        }
    }

    private ShadedJarCompleteness() {}

    public static void main(String[] args) {
        if (args.length != 1) {
            throw new IllegalArgumentException("usage: ShadedJarCompleteness <shaded jar>");
        }
        Path jar = Path.of(args[0]);
        List<Missing> missing = check(jar);
        if (!missing.isEmpty()) {
            StringBuilder msg = new StringBuilder()
                    .append(jar.getFileName()).append(": ").append(missing.size())
                    .append(" reference(s) from io.flowcatalyst classes to classes the jar does not contain")
                    .append(" (a module dependency demoted to test/provided scope?):");
            missing.forEach(m -> msg.append("\n  ").append(m));
            throw new IllegalStateException(msg.toString());
        }
        System.out.println("[shaded-jar-completeness] " + jar.getFileName()
                + ": every io.flowcatalyst class resolves its references");
    }

    /// The unresolved references from `io.flowcatalyst` classes in `jar`, minus [#IGNORED_PREFIXES].
    static List<Missing> check(Path jar) {
        if (!Files.isRegularFile(jar)) {
            throw new IllegalStateException("no jar at " + jar);
        }
        ToolProvider jdeps = ToolProvider.findFirst("jdeps")
                .orElseThrow(() -> new IllegalStateException("jdeps is not available in this JDK"));
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int rc;
        try (PrintWriter o = new PrintWriter(out); PrintWriter e = new PrintWriter(err)) {
            rc = jdeps.run(o, e,
                    "--multi-release", String.valueOf(Runtime.version().feature()),
                    "--missing-deps",
                    // jdeps drops same-package dependences by default (-filter:package), so a
                    // class missing from a package the jar otherwise has would go unreported.
                    "-filter:none",
                    "-include", "io\\.flowcatalyst\\..*",
                    jar.toString());
        }
        if (rc != 0) {
            throw new IllegalStateException("jdeps exited " + rc + ": " + err + out);
        }
        return parse(out.toString());
    }

    static List<Missing> parse(String jdepsOutput) {
        return jdepsOutput.lines()
                .map(ROW::matcher)
                .filter(Matcher::matches)
                .map(m -> new Missing(m.group(1), m.group(2)))
                .filter(m -> IGNORED_PREFIXES.stream().noneMatch(m.to()::startsWith))
                .toList();
    }
}
