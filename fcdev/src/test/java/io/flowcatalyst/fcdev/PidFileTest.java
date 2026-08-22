package io.flowcatalyst.fcdev;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PidFileTest {

    @TempDir
    Path dir;

    @Test
    void writeCreatesParentsAndReadRoundTrips() throws Exception {
        var p = dir.resolve("nested/deeper/fcdev.pid");
        PidFile.write(p, 4242);
        assertThat(Files.readString(p)).isEqualTo("4242\n");
        assertThat(PidFile.read(p)).hasValue(4242);
    }

    @Test
    void missingFileReadsEmpty() throws Exception {
        assertThat(PidFile.read(dir.resolve("nope.pid"))).isEmpty();
    }

    @Test
    void malformedFileIsAnError() throws Exception {
        var p = dir.resolve("bad.pid");
        Files.writeString(p, "not-a-pid\n");
        assertThatThrownBy(() -> PidFile.read(p)).isInstanceOf(IllegalStateException.class).hasMessageContaining("malformed pid file");
    }

    @Test
    void removeIfOwnedOnlyDeletesItsOwnPid() throws Exception {
        var p = dir.resolve("fcdev.pid");
        PidFile.write(p, 100);
        PidFile.removeIfOwned(p, 200);           // a newer instance's file: left alone
        assertThat(p).exists();
        PidFile.removeIfOwned(p, 100);
        assertThat(p).doesNotExist();
        PidFile.removeIfOwned(p, 100);           // idempotent
        Files.writeString(p, "garbage");
        PidFile.removeIfOwned(p, 100);           // unreadable: not ours to remove
        assertThat(p).exists();
    }

    @Test
    void writeOverwritesAStaleFile() throws Exception {
        var p = dir.resolve("fcdev.pid");
        PidFile.write(p, 1);
        PidFile.write(p, 2);
        assertThat(PidFile.read(p)).hasValue(2);
    }

    @Test
    void processAlive() {
        assertThat(PidFile.processAlive(ProcessHandle.current().pid())).isTrue();
        assertThat(PidFile.processAlive(0)).isFalse();
        assertThat(PidFile.processAlive(-5)).isFalse();
        assertThat(PidFile.processAlive(Integer.MAX_VALUE - 7L)).isFalse();
    }
}
