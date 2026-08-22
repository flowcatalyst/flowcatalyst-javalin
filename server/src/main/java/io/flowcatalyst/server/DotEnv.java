package io.flowcatalyst.server;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/// A dotenv loader with the semantics of `loadDotEnv` in `cmd/fcdev/helpers.go`:
/// `KEY=VALUE` lines, blank lines and `#` comments, an optional leading
/// `export `, single- or double-quoted values (quotes stripped, no escape
/// processing, no interpolation), and — the load-bearing rule — **a variable
/// already present in the environment is never overridden**, not even when it
/// is set to the empty string (Go checks `os.LookupEnv`, not `!= ""`).
///
/// The JVM cannot mutate its own process environment, so instead of
/// `os.Setenv` the loader returns a merged map: the real environment plus the
/// file's values for keys the environment lacked. Feed it to
/// [Env#load(Map)]:
///
/// ```java
/// var merged = DotEnv.apply(Path.of(".env"), System.getenv());
/// var env = Env.load(merged);
/// ```
///
/// A missing file is a no-op (it is a local-dev convenience), exactly as in Go.
public final class DotEnv {

    private DotEnv() {
    }

    /// Parse a dotenv file into an insertion-ordered map. Missing file → empty map.
    public static Map<String, String> parse(Path file) {
        if (file == null || !Files.isRegularFile(file)) return Map.of();
        try {
            return parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("read dotenv " + file, e);
        }
    }

    /// Parse dotenv content. Later duplicates of a key win within the file
    /// (the Go loader would `Setenv` each in turn, so the last one sticks).
    public static Map<String, String> parse(CharSequence content) {
        var out = new LinkedHashMap<String, String>();
        content.toString().lines().forEach(raw -> {
            var line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) return;
            if (line.startsWith("export ")) line = line.substring("export ".length());
            var eq = line.indexOf('=');
            if (eq < 0) return;
            var key = line.substring(0, eq).strip();
            var val = line.substring(eq + 1).strip();
            var n = val.length();
            if (n >= 2) {
                var first = val.charAt(0);
                var last = val.charAt(n - 1);
                if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                    val = val.substring(1, n - 1);
                }
            }
            if (key.isEmpty()) return;
            out.put(key, val);
        });
        return out;
    }

    /// Merge the file's values *under* `environment`: a copy of `environment`
    /// with every file key that the environment does not already contain.
    /// Missing file → an unchanged copy.
    public static Map<String, String> apply(Path file, Map<String, String> environment) {
        return apply(parse(file), environment);
    }

    /// Same as [#apply(Path, Map)] for already-parsed values.
    public static Map<String, String> apply(Map<String, String> fromFile, Map<String, String> environment) {
        var merged = new LinkedHashMap<>(environment);
        fromFile.forEach((k, v) -> {
            if (!merged.containsKey(k)) merged.put(k, v); // never override an explicit env var
        });
        return merged;
    }
}
