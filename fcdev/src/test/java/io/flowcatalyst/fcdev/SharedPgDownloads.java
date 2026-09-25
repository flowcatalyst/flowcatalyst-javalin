package io.flowcatalyst.fcdev;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/// Every fcdev boot in a test gets a fresh cache dir (`XDG_CACHE_HOME`), so each
/// one downloaded the embedded-PostgreSQL jar from Maven Central again — a
/// network round trip per boot, and a hang whenever a mirror stalled. This links
/// the cache dir's `flowcatalyst/embedded-pg/downloads` to one directory shared
/// by the whole test run (`target/shared-pg-downloads`): the first boot
/// downloads, every later one finds the jar. Nothing else in the cache is
/// shared. The resolver writes a temp file and moves it within that directory,
/// so the link is safe.
public final class SharedPgDownloads {

    private static final Path SHARED = Path.of("target", "shared-pg-downloads").toAbsolutePath();

    private SharedPgDownloads() {
    }

    /// Links `userCacheDir`'s embedded-pg download dir to the shared one.
    public static void link(Path userCacheDir) {
        try {
            Files.createDirectories(SHARED);
            Path downloads = new DevPaths(userCacheDir, userCacheDir).embeddedPgCacheDir().resolve("downloads");
            if (Files.exists(downloads, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            Files.createDirectories(downloads.getParent());
            Files.createSymbolicLink(downloads, SHARED);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
