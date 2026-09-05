package io.flowcatalyst.fcdev;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// Reads `KEY=VALUE` pairs from a dotenv file WITHOUT overriding variables
/// already set (Go `loadDotEnv`, `cmd/fcdev/helpers.go`): an explicit
/// environment variable always wins over the file. A missing/unreadable file
/// is a no-op — it's a local-dev convenience, not a requirement.
///
/// Supports `#` comments, blank lines, an optional leading `export `, and
/// single/double-quoted values.
final class DotEnv {

    private DotEnv() {
    }

    /// The [DevEnv] `env` would have if `path` were loaded first — i.e. the
    /// file's pairs layered UNDER `env`'s own, so anything `env` already has
    /// is untouched. Used instead of Go's `os.Setenv` because Java cannot
    /// mutate its own process environment (CONVENTIONS: "reads the value
    /// from the `Env` record", never `EnvReader.system()`).
    static DevEnv loadOver(DevEnv env, String path) {
        if (path == null || path.isBlank()) {
            return env;
        }
        Map<String, String> fromFile = parse(Path.of(path));
        if (fromFile.isEmpty()) {
            return env;
        }
        var merged = new LinkedHashMap<>(fromFile);
        merged.putAll(env.vars()); // an explicit env var always wins over the dotenv file
        return DevEnv.of(merged);
    }

    /// Parses `path` into a map; empty (never throws) when the file is
    /// missing, unreadable, or has no valid `KEY=VALUE` lines.
    static Map<String, String> parse(Path path) {
        var map = new LinkedHashMap<String, String>();
        List<String> lines;
        try {
            lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return map;
        }
        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("export ")) {
                line = line.substring("export ".length());
            }
            int eq = line.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = line.substring(0, eq).strip();
            String val = line.substring(eq + 1).strip();
            if (val.length() >= 2) {
                char first = val.charAt(0);
                char last = val.charAt(val.length() - 1);
                if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                    val = val.substring(1, val.length() - 1);
                }
            }
            if (key.isEmpty()) {
                continue;
            }
            map.put(key, val);
        }
        return map;
    }
}
