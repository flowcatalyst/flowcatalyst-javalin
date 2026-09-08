package io.flowcatalyst.fcdev;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/// Provisioning the PostGIS family into whatever Postgres tree this `fcdev`
/// is running. The embedded cluster is a directory shared with the Go
/// `fcdev` (`EmbeddedPg` javadoc), and the owner hand-transplanted PostGIS
/// into the Go tree only — a cluster that already has `CREATE EXTENSION
/// postgis` in it starts fine under the Java binary and then fails the
/// moment a query touches a PostGIS object, because `$libdir/postgis-3`
/// (or the `.control` file) is not there. Postgres loads extension control
/// files and modules lazily, so copying them into a *running* server's own
/// directories is enough — no restart, no need to predict zonky's
/// `PG-<md5>` extraction path.
///
/// Pure filesystem operations, no logging: the caller (`EmbeddedPg`) decides
/// what is worth a log line and owns the fatal/non-fatal distinction.
public final class PgExtensions {

    /// The extension families this fcdev knows how to mirror: PostGIS itself
    /// plus the address-standardizer companion extensions it ships with.
    /// A file "belongs" to the family when its name starts with one of these.
    private static final List<String> FAMILY_PREFIXES = List.of("postgis", "address_standardizer");

    /// Extension names built into the server itself, with no `.control` file
    /// to find — `plpgsql` is created in every database by `initdb` and is
    /// never something a Postgres tree needs to "provide".
    private static final Set<String> NO_CONTROL_FILE_REQUIRED = Set.of("plpgsql");

    private static final Set<String> MODULE_SUFFIXES = Set.of(".dylib", ".so", ".dll");
    private static final Set<String> EXTENSION_SUFFIXES = Set.of(".control", ".sql");

    private PgExtensions() {
    }

    /// One place a Postgres extension's files can be copied FROM: a directory
    /// of loadable modules (`$libdir` — `.dylib`/`.so`/`.dll`) and a directory
    /// of `.control`/`.sql` extension files (`<sharedir>/extension`).
    public record Donor(Path modules, Path extensions) {
    }

    /// Where PostGIS might already be sitting on this machine, in priority
    /// order. The packaged installs come first because they are built for
    /// the exact `pgMajor` this fcdev embeds; the sibling `fcdev` cache tree
    /// — where the Go binary extracts its own copy of the server, `bin/lib`
    /// + `bin/share` under the same `cacheDir` this Java binary uses — comes
    /// last, as the fallback that keeps an already-working machine working
    /// even without Homebrew or PGDG installed.
    public static List<Donor> donorCandidates(String pgMajor, Path cacheDir) {
        var candidates = new ArrayList<Donor>();
        candidates.add(new Donor(
                Path.of("/opt/homebrew/opt/postgis/lib/postgresql@" + pgMajor),
                Path.of("/opt/homebrew/opt/postgis/share/postgresql@" + pgMajor, "extension")));
        candidates.add(new Donor(
                Path.of("/usr/local/opt/postgis/lib/postgresql@" + pgMajor),
                Path.of("/usr/local/opt/postgis/share/postgresql@" + pgMajor, "extension")));
        candidates.add(new Donor(
                Path.of("/usr/lib/postgresql", pgMajor, "lib"),
                Path.of("/usr/share/postgresql", pgMajor, "extension")));
        candidates.add(new Donor(
                cacheDir.resolve("bin").resolve("lib").resolve("postgresql"),
                cacheDir.resolve("bin").resolve("share").resolve("postgresql").resolve("extension")));
        return candidates;
    }

    /// The first candidate that can actually donate: both directories exist
    /// and `postgis.control` is there, which is the cheapest reliable signal
    /// that PostGIS (not just an empty extension dir) lives at this root.
    /// Anything that fails that check is skipped, not treated as an error —
    /// most donor roots on a given machine simply don't exist.
    public static Optional<Donor> firstUsable(List<Donor> candidates) {
        for (Donor donor : candidates) {
            if (Files.isDirectory(donor.modules())
                    && Files.isDirectory(donor.extensions())
                    && Files.isRegularFile(donor.extensions().resolve("postgis.control"))) {
                return Optional.of(donor);
            }
        }
        return Optional.empty();
    }

    /// Copy the PostGIS family from `donor` into this fcdev's own tree.
    /// Never overwrites — a target that already has a file is left exactly
    /// as it is, which makes this idempotent and means a tree that already
    /// carries PostGIS (e.g. re-running against the sibling Go cache) is
    /// touched not at all. Returns the sorted list of filenames actually
    /// copied, for the caller to log.
    public static List<String> mirror(Donor donor, Path targetModules, Path targetExtensions) throws IOException {
        var copied = new ArrayList<String>();
        Files.createDirectories(targetModules);
        Files.createDirectories(targetExtensions);
        copied.addAll(mirrorDirectory(donor.modules(), targetModules, MODULE_SUFFIXES));
        copied.addAll(mirrorDirectory(donor.extensions(), targetExtensions, EXTENSION_SUFFIXES));
        copied.sort(String::compareTo);
        return copied;
    }

    private static List<String> mirrorDirectory(Path sourceDir, Path targetDir, Set<String> suffixes) throws IOException {
        var copied = new ArrayList<String>();
        if (!Files.isDirectory(sourceDir)) return copied;
        try (Stream<Path> entries = Files.list(sourceDir)) {
            for (Path source : entries.toList()) {
                var name = source.getFileName().toString();
                if (!isFamilyFile(name) || !hasSuffix(name, suffixes) || !Files.isRegularFile(source)) continue;
                var target = targetDir.resolve(name);
                if (Files.exists(target)) continue;
                Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
                preserveExecutableBit(source, target);
                copied.add(name);
            }
        }
        return copied;
    }

    private static boolean isFamilyFile(String filename) {
        for (String prefix : FAMILY_PREFIXES) {
            if (filename.startsWith(prefix)) return true;
        }
        return false;
    }

    private static boolean hasSuffix(String filename, Set<String> suffixes) {
        var lower = filename.toLowerCase(Locale.ROOT);
        for (String suffix : suffixes) {
            if (lower.endsWith(suffix)) return true;
        }
        return false;
    }

    /// `COPY_ATTRIBUTES` already carries POSIX permissions across on
    /// platforms that support them; this is a defensive fallback so a
    /// module's executable bit survives even if the attribute copy above
    /// didn't stick on some filesystem.
    private static void preserveExecutableBit(Path source, Path target) throws IOException {
        var sourceView = Files.getFileAttributeView(source, PosixFileAttributeView.class);
        var targetView = Files.getFileAttributeView(target, PosixFileAttributeView.class);
        if (sourceView == null || targetView == null) return;
        Set<PosixFilePermission> perms = sourceView.readAttributes().permissions();
        targetView.setPermissions(perms);
    }

    /// For every extension name actually installed in the cluster
    /// (`pg_extension.extname`), whether this Postgres tree's
    /// `<extensionDir>` has the `.control` file `CREATE EXTENSION` needs to
    /// load it. `plpgsql` is excluded — it is built into the server, not a
    /// loadable extension with a control file of its own. Returns the sorted
    /// names that are missing; empty when the tree can serve everything the
    /// cluster has installed.
    public static List<String> missingControlFiles(Set<String> registered, Path extensionDir) {
        var missing = new ArrayList<String>();
        for (String name : registered) {
            if (NO_CONTROL_FILE_REQUIRED.contains(name)) continue;
            if (!Files.isRegularFile(extensionDir.resolve(name + ".control"))) {
                missing.add(name);
            }
        }
        missing.sort(String::compareTo);
        return missing;
    }
}
