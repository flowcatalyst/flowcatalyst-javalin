package io.flowcatalyst.fcdev;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/// The one way fcdev writes a private state file (PID file, app key): parent
/// directories created, content written as UTF-8, then mode 0600 where the
/// file system has POSIX permissions (a no-op elsewhere — Windows). The JWT
/// key file is written by the server's `SigningKeys` with the same rule.
final class OwnerOnlyFile {

    private static final Set<PosixFilePermission> OWNER_RW = Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private OwnerOnlyFile() {
    }

    /// The secret never sits in a file other users can read: on POSIX it is
    /// written to a sibling temp file created 0600, then moved over `path`
    /// atomically (writing first and tightening after left a window where the
    /// umask's permissions applied — and replaced an existing file's mode only
    /// after the new content was already in it).
    static void write(Path path, String content) throws IOException {
        var dir = path.toAbsolutePath().getParent();
        if (dir != null) Files.createDirectories(dir);
        if (!path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Files.writeString(path, content, StandardCharsets.UTF_8);
            return;
        }
        Path tmp = Files.createTempFile(dir, "." + path.getFileName() + ".", ".tmp",
                PosixFilePermissions.asFileAttribute(OWNER_RW));
        try {
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
