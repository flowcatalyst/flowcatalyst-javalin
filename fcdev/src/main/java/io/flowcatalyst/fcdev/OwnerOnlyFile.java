package io.flowcatalyst.fcdev;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

/// The one way fcdev writes a private state file (PID file, app key): parent
/// directories created, content written as UTF-8, then mode 0600 where the
/// file system has POSIX permissions (a no-op elsewhere — Windows). The JWT
/// key file is written by the server's `SigningKeys` with the same rule.
final class OwnerOnlyFile {

    private static final Set<PosixFilePermission> OWNER_RW = Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private OwnerOnlyFile() {
    }

    static void write(Path path, String content) throws IOException {
        var dir = path.toAbsolutePath().getParent();
        if (dir != null) Files.createDirectories(dir);
        Files.writeString(path, content, StandardCharsets.UTF_8);
        if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(path, OWNER_RW);
        }
    }
}
