package io.flowcatalyst.server;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// Pins the structured-logging convention: an operational log line carries its
/// values as **fields**, not baked into the message text.
///
/// [Logging] already emits JSON with `withKVPList(true)`, and the field names
/// on the MDC are a contract shared with the Go platform. None of that buys
/// anything if the call site writes
/// `LOG.warn("dispatch failed for group {}", group)` — the group then lives
/// inside one opaque `formattedMessage` string, so a log pipeline can only
/// regex over prose instead of filtering on `group`. The fluent form
/// `LOG.atWarn().setMessage("dispatch failed").addKeyValue("group", group).log()`
/// puts it in `kvp` where it is queryable.
///
/// `debug` and `trace` are deliberately exempt: they are development prose,
/// they are off in production, and the fluent builder is not free.
///
/// Scans the source tree rather than a hand-maintained list, so a new file
/// that reaches for interpolated logging is caught the day it is added.
///
/// Mutant (run by hand, not by CI): change any converted call back to
/// `LOG.warn("… {}", value)` and this test fails, naming file and line;
/// restoring the fluent form makes it pass.
class StructuredLoggingTest {

    /// A logger call at info/warn/error whose message literal contains `{}`.
    /// Deliberately narrow: only a `"…{}…"` literal on the same line as the
    /// call counts, so a message built elsewhere is never guessed at.
    private static final Pattern INTERPOLATED =
            Pattern.compile("\\b(?:LOG|log|LOGGER)\\.(?:info|warn|error)\\(\\s*\"[^\"]*\\{}");

    /// Source roots this convention covers, relative to the `server` module.
    private static final List<Path> ROOTS =
            List.of(Path.of("src/main/java"), Path.of("../fcdev/src/main/java"));

    /// Files allowed to keep an interpolated value, with the reason. Add here
    /// only with a reason a reader can check.
    private static final List<String> ALLOWED = List.of(
            // "set {} + {} to create one" — two placeholders joined mid-sentence
            // by a literal '+', so the message does not survive having them
            // removed. Needs a rewrite, not a mechanical conversion.
            "io/flowcatalyst/platform/seed/Seeder.java");

    @Test
    void operationalLogsCarryValuesAsFieldsNotInsideTheMessage() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path root : ROOTS) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> files = Files.walk(root)) {
                files.filter(p -> p.toString().endsWith(".java")).sorted().forEach(p -> {
                    String rel = root.relativize(p).toString().replace('\\', '/');
                    if (ALLOWED.contains(rel)) return;
                    String content;
                    try {
                        content = Files.readString(p);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    Matcher m = INTERPOLATED.matcher(content);
                    while (m.find()) {
                        long line = content.chars().limit(m.start()).filter(c -> c == '\n').count() + 1;
                        offenders.add(rel + ":" + line);
                    }
                });
            }
        }
        assertThat(offenders)
                .as("info/warn/error logs interpolating a value into the message instead of "
                        + "addKeyValue(...) — see io.flowcatalyst.server.Logging")
                .isEmpty();
    }

    /// The guard is only worth having if it can actually see a violation:
    /// the pattern must match the shape it is meant to ban, and must leave
    /// the fluent form and `debug` alone.
    @Test
    void theGuardMatchesInterpolatedCallsAndNothingElse() {
        assertThat(INTERPOLATED.matcher("LOG.warn(\"dispatch failed for group {}\", group);").find()).isTrue();
        assertThat(INTERPOLATED.matcher("LOG.error(\"lookup failed client_id={}\", id, e);").find()).isTrue();
        assertThat(INTERPOLATED.matcher("log.info(\"recovered {} item(s)\", n);").find()).isTrue();

        assertThat(INTERPOLATED.matcher("LOG.debug(\"cache miss for {}\", key);").find()).isFalse();
        assertThat(INTERPOLATED.matcher("LOG.warn(\"outbox claim rollback failed\", e);").find()).isFalse();
        assertThat(INTERPOLATED.matcher(
                "LOG.atWarn().setMessage(\"dispatch failed\").addKeyValue(\"group\", g).log();").find()).isFalse();
    }
}
