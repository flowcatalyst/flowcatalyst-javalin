package io.flowcatalyst.fcdev;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/// `EnvFileWriter` (spec `docs/spec/fcdev-commands.md` §1.6): in-place
/// rewrite of existing keys, sorted-and-headered append of new ones, the
/// quoting rule, and idempotence.
class EnvFileWriterTest {

    @TempDir
    Path dir;

    /// Pins the exact defect the mutant table names: rewriting `A` in place
    /// must replace the *existing* line, not leave it and append a second
    /// `A=` line under the header. An append-only implementation produces
    /// two `A=` lines and a different final string, so this fails loudly.
    @Test
    void rewritesAnExistingKeyInPlaceRatherThanAppending() {
        String existing = "A=1\nB=2\n";
        String result = EnvFileWriter.merge(existing, List.of(new EnvFileWriter.Update("A", "9")));
        assertThat(result).isEqualTo("A=9\nB=2\n");
        assertThat(result.lines().filter(l -> l.startsWith("A=")).count()).isEqualTo(1);
    }

    @Test
    void appendsNewKeysSortedUnderTheHeader() {
        String existing = "A=1\n";
        String result = EnvFileWriter.merge(existing,
                List.of(new EnvFileWriter.Update("C", "3"), new EnvFileWriter.Update("B", "2")));
        assertThat(result).isEqualTo("""
                A=1

                # FlowCatalyst (added by `fcdev init`)
                B=2
                C=3
                """);
    }

    @Test
    void appendsToAnEmptyFileWithNoLeadingBlankLine() {
        String result = EnvFileWriter.merge("", List.of(new EnvFileWriter.Update("A", "1")));
        assertThat(result).isEqualTo("""
                # FlowCatalyst (added by `fcdev init`)
                A=1
                """);
    }

    @Test
    void mixesInPlaceRewriteAndAppendInOneMerge() {
        String existing = "A=1\nB=2\n";
        String result = EnvFileWriter.merge(existing,
                List.of(new EnvFileWriter.Update("A", "9"), new EnvFileWriter.Update("C", "3")));
        assertThat(result).isEqualTo("""
                A=9
                B=2

                # FlowCatalyst (added by `fcdev init`)
                C=3
                """);
    }

    @Test
    void quotesValuesWithWhitespaceOrShellSpecialCharacters() {
        String result = EnvFileWriter.merge("", List.of(
                new EnvFileWriter.Update("PLAIN", "abc123"),
                new EnvFileWriter.Update("SPACED", "has space"),
                new EnvFileWriter.Update("QUOTED", "it's here"),
                new EnvFileWriter.Update("EMPTY", ""),
                new EnvFileWriter.Update("HASH", "a#b")));
        assertThat(result).contains("PLAIN=abc123");
        assertThat(result).contains("SPACED='has space'");
        assertThat(result).contains("QUOTED='it'\\''s here'");
        assertThat(result).contains("EMPTY=''");
        assertThat(result).contains("HASH='a#b'");
    }

    @Test
    void mergingTheSameUpdatesTwiceIsIdempotent() {
        List<EnvFileWriter.Update> updates = List.of(
                new EnvFileWriter.Update("FLOWCATALYST_BASE_URL", "http://localhost:8080"),
                new EnvFileWriter.Update("FLOWCATALYST_APP_CODE", "orders"),
                new EnvFileWriter.Update("FLOWCATALYST_APP_KEY", "abcdefg=="));
        String once = EnvFileWriter.merge("", updates);
        String twice = EnvFileWriter.merge(once, updates);
        assertThat(twice).isEqualTo(once);
    }

    @Test
    void writeCreatesTheFileMode0600AndSkipsAnUnchangedRewrite() throws Exception {
        Path path = dir.resolve(".env");
        List<EnvFileWriter.Update> updates = List.of(new EnvFileWriter.Update("A", "1"));

        var created = EnvFileWriter.write(path, updates);
        assertThat(created).isEqualTo(EnvFileWriter.Outcome.CREATED);
        assertThat(Files.readString(path)).isEqualTo("# FlowCatalyst (added by `fcdev init`)\nA=1\n");
        if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertThat(Files.getPosixFilePermissions(path))
                    .isEqualTo(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        }

        var unchanged = EnvFileWriter.write(path, updates);
        assertThat(unchanged).isEqualTo(EnvFileWriter.Outcome.UNCHANGED);

        var updated = EnvFileWriter.write(path, List.of(new EnvFileWriter.Update("A", "2")));
        assertThat(updated).isEqualTo(EnvFileWriter.Outcome.UPDATED);
        assertThat(Files.readString(path)).isEqualTo("# FlowCatalyst (added by `fcdev init`)\nA=2\n");
    }
}
