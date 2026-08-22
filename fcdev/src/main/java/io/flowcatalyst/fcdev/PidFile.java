package io.flowcatalyst.fcdev;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.OptionalLong;

/// The PID file `fcdev start` writes and `fcdev stop` reads (Go `pidfile.go`).
/// Plain text `<pid>\n`, mode 0600. Removal is ownership-checked so an
/// instance's cleanup never deletes a newer instance's file.
public final class PidFile {

    private PidFile() {
    }

    /// `writePIDFile`: record `pid` at `path`, creating parent directories,
    /// overwriting a stale file (a crashed instance must not block a fresh start).
    public static void write(Path path, long pid) throws IOException {
        OwnerOnlyFile.write(path, pid + "\n");
    }

    /// `readPIDFile`: the recorded PID; empty when the file does not exist.
    ///
    /// @throws IllegalStateException when the file exists but is not a number
    /// @throws IOException           on any other read failure
    public static OptionalLong read(Path path) throws IOException {
        String raw;
        try {
            raw = Files.readString(path, StandardCharsets.UTF_8);
        } catch (NoSuchFileException _) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(Long.parseLong(raw.strip()));
        } catch (NumberFormatException e) {
            throw new IllegalStateException("malformed pid file " + path + ": " + e.getMessage(), e);
        }
    }

    /// `removePIDFileIfOwned`: delete `path` only while it still records `pid`.
    public static void removeIfOwned(Path path, long pid) {
        try {
            var cur = read(path);
            if (cur.isPresent() && cur.getAsLong() == pid) Files.deleteIfExists(path);
        } catch (IOException | IllegalStateException _) {
            // best effort — a file we cannot read is not ours to remove
        }
    }

    /// `processAlive`: does a process with `pid` exist? (Go also counts EPERM
    /// as alive; `ProcessHandle` reports foreign-user processes as alive too.)
    public static boolean processAlive(long pid) {
        if (pid <= 0) return false;
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }
}
