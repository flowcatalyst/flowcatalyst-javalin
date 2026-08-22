package io.flowcatalyst.fcdev;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/// The released fcdev version (`src/main/resources/VERSION`, a copy of the Go
/// `cmd/fcdev/VERSION`) and the build metadata Maven stamps into
/// `fcdev-build.properties` (the embedded PostgreSQL version, an optional VCS
/// revision). `fcdev version` / `fcdev --version` print `fcdev <version>[ (<rev>)]`.
public final class Version {

    private static final String CURRENT = readResource("/VERSION").strip();
    private static final Properties BUILD = loadBuild();

    private Version() {
    }

    /// The trimmed semver string, e.g. `0.8.23`.
    public static String current() {
        return CURRENT;
    }

    /// ` (<rev>)` when the build recorded a VCS revision, else `""` (Go
    /// `vcsSuffix`; Java has no `debug.ReadBuildInfo`, so a release build
    /// passes `-Dfcdev.vcs.revision=<sha>`).
    public static String vcsSuffix() {
        var rev = BUILD.getProperty("vcs.revision", "").strip();
        if (rev.isEmpty() || rev.startsWith("${")) return "";
        if (rev.length() > 12) rev = rev.substring(0, 12);
        return " (" + rev + ")";
    }

    /// `fcdev 0.8.23` — the line both `version` and `--version` print.
    public static String line() {
        return "fcdev " + current() + vcsSuffix();
    }

    /// The full embedded PostgreSQL version (`zonky-binaries.version` in the
    /// root POM), e.g. `18.4.0`.
    public static String embeddedPgVersion() {
        var v = BUILD.getProperty("embedded.pg.version", "").strip();
        if (v.isEmpty() || v.startsWith("${")) {
            throw new IllegalStateException("fcdev-build.properties was not filtered: embedded.pg.version=" + v);
        }
        return v;
    }

    private static Properties loadBuild() {
        var p = new Properties();
        try (InputStream in = Version.class.getResourceAsStream("/fcdev-build.properties")) {
            if (in != null) p.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return p;
    }

    private static String readResource(String name) {
        try (InputStream in = Version.class.getResourceAsStream(name)) {
            if (in == null) throw new IllegalStateException("missing classpath resource " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
