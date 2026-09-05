package io.flowcatalyst.fcdev;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/// The `.env` merge `fcdev init` uses (spec `docs/spec/fcdev-commands.md`
/// §1.6, Go `writeEnvUpdates`): existing keys are rewritten **in place** —
/// same line, same position — new keys are appended, sorted, under a header
/// comment. [#merge] is the pure function ([EnvFileWriterTest] drives it
/// directly); [#write] is the file-writing wrapper (0600, skip when
/// unchanged).
public final class EnvFileWriter {

    static final String HEADER = "# FlowCatalyst (added by `fcdev init`)";

    /// Characters that force single-quoting (Go `strings.ContainsAny(v, " \t\n#'\"`$")`).
    private static final String SPECIAL_CHARS = " \t\n#'\"`$";

    private EnvFileWriter() {
    }

    /// One `KEY=value` update to merge in.
    public record Update(String key, String value) {
        public Update {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(value, "value");
        }
    }

    /// What happened to the file: unchanged content is never rewritten.
    public enum Outcome {CREATED, UPDATED, UNCHANGED}

    /// Reads `path` (missing file = empty content), merges `updates`, and
    /// writes the result back (mode 0600, [OwnerOnlyFile]) only when it
    /// differs from what was read.
    public static Outcome write(Path path, List<Update> updates) throws IOException {
        String existing = Files.isRegularFile(path) ? Files.readString(path, StandardCharsets.UTF_8) : "";
        String next = merge(existing, updates);
        if (next.equals(existing)) {
            return Outcome.UNCHANGED;
        }
        OwnerOnlyFile.write(path, next);
        return existing.isEmpty() ? Outcome.CREATED : Outcome.UPDATED;
    }

    /// The pure merge (Go `writeEnvUpdates`'s in-memory half): a key already
    /// present as `KEY=...` on some line is rewritten on that same line,
    /// preserving its position; every other update is appended, sorted by
    /// key, under [#HEADER] (added once, only when there is something to
    /// append). Returns the full file content, always ending in exactly one
    /// trailing newline.
    public static String merge(String existingContent, List<Update> updates) {
        Objects.requireNonNull(existingContent, "existingContent");
        Objects.requireNonNull(updates, "updates");

        List<String> lines = existingContent.isEmpty()
                ? new ArrayList<>()
                : new ArrayList<>(Arrays.asList(existingContent.split("\n", -1)));

        Set<String> seen = new HashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String key = line.substring(0, eq).strip();
            for (Update u : updates) {
                if (u.key().equals(key)) {
                    lines.set(i, u.key() + "=" + quote(u.value()));
                    seen.add(u.key());
                }
            }
        }

        List<Update> toAppend = updates.stream()
                .filter(u -> !seen.contains(u.key()))
                .sorted(Comparator.comparing(Update::key))
                .toList();
        if (!toAppend.isEmpty()) {
            if (!lines.isEmpty() && !lines.getLast().isEmpty()) {
                lines.add("");
            }
            lines.add(HEADER);
            for (Update u : toAppend) {
                lines.add(u.key() + "=" + quote(u.value()));
            }
        }

        String joined = String.join("\n", lines);
        int end = joined.length();
        while (end > 0 && joined.charAt(end - 1) == '\n') end--;
        return joined.substring(0, end) + "\n";
    }

    /// Blank, or containing whitespace or a shell-special character →
    /// single-quoted with `'` escaped as `'\''`; otherwise bare.
    private static String quote(String v) {
        if (needsQuoting(v)) {
            return "'" + v.replace("'", "'\\''") + "'";
        }
        return v;
    }

    private static boolean needsQuoting(String v) {
        if (v.isEmpty()) return true;
        for (int i = 0; i < v.length(); i++) {
            if (SPECIAL_CHARS.indexOf(v.charAt(i)) >= 0) return true;
        }
        return false;
    }
}
