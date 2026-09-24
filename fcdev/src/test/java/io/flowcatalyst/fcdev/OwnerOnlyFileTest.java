package io.flowcatalyst.fcdev;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.assertj.core.api.Assertions.assertThat;

/// The final state only: the create-0600-then-move ordering (no window where
/// the umask's mode applies) cannot be observed cheaply from a test.
class OwnerOnlyFileTest {

    @Test
    void anExistingWorldReadableFileIsReplacedByAnOwnerOnlyOneAndNoTempFileRemains(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("state/app.key");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "old");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));

        OwnerOnlyFile.write(file, "s3cr3t");

        assertThat(Files.readString(file)).isEqualTo("s3cr3t");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-------");
        try (var entries = Files.list(file.getParent())) {
            assertThat(entries.map(p -> p.getFileName().toString())).containsExactly("app.key");
        }
    }
}
