package io.flowcatalyst.server;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/// A read-only view over an environment map with the exact lookup semantics of
/// the Go helpers in `internal/server/envcfg.go` (`envOr`, `envFirst`,
/// `envInt`, `envIntAlias`, `envBool`, `envBoolAlias`) and
/// `internal/envutil` (`Or`, `Int`, `Uint32`, `Uint`).
///
/// Rules that every helper shares:
///
///   - an unset variable and an empty one are the same thing — Go's
///     `os.Getenv` returns `""` for both and every helper tests `!= ""`;
///   - a value that does not parse (bad integer, unknown boolean word) falls
///     back to the default — silently, by design, never on principle;
///   - aliases are consulted in priority order, first non-empty wins.
///
/// Wrap `System.getenv()` for production ([#system()]) or any `Map` for tests.
public record EnvReader(Map<String, String> env) {

    public EnvReader {
        env = Map.copyOf(Objects.requireNonNull(env, "env"));
    }

    /// The process environment.
    public static EnvReader system() {
        return new EnvReader(System.getenv());
    }

    /// `os.Getenv`: the value, or `""` when unset. Never `null`.
    public String get(String key) {
        var v = env.get(key);
        return v == null ? "" : v;
    }

    /// `os.LookupEnv`: present iff the variable is set, even when empty.
    public Optional<String> lookup(String key) {
        return Optional.ofNullable(env.get(key));
    }

    /// `envOr` / `envutil.Or`: the value, or `def` when unset or empty.
    public String or(String key, String def) {
        var v = get(key);
        return v.isEmpty() ? def : v;
    }

    /// `envFirst` without the trailing default: the first non-empty value among
    /// `keys`, in priority order. Empty key names are skipped (Go used `""`
    /// placeholders in a few call sites). Chain `.orElse(def)` for the default.
    public Optional<String> firstSet(String... keys) {
        for (var k : keys) {
            if (k == null || k.isEmpty()) continue;
            var v = get(k);
            if (!v.isEmpty()) return Optional.of(v);
        }
        return Optional.empty();
    }

    /// `envInt` / `envutil.Int`: parse as a base-10 integer (optional sign),
    /// `def` when unset or unparseable.
    public int integer(String key, int def) {
        return parseInt(get(key)).orElse(def);
    }

    /// `envIntAlias`: `key` first, then `alias`; each is only taken when it both
    /// is set and parses — an unparseable primary falls through to the alias.
    public int integerAlias(String key, String alias, int def) {
        var primary = parseInt(get(key));
        if (primary.isPresent()) return primary.get();
        return parseInt(get(alias)).orElse(def);
    }

    /// `envInt`'s `long` counterpart: parse as a base-10 integer (optional
    /// sign), `def` when unset or unparseable.
    public long longValue(String key, long def) {
        return parseLong(get(key)).orElse(def);
    }

    /// `envBool`: `1/true/yes/on` → `true`, `0/false/no/off` → `false`
    /// (case-insensitive, surrounding whitespace ignored); anything else,
    /// including unset, → `def`.
    public boolean bool(String key, boolean def) {
        return parseBool(get(key)).orElse(def);
    }

    /// `envBoolAlias`: when `key` is set (non-empty) it alone decides — even an
    /// unparseable primary yields `def` without consulting `alias`; only an
    /// unset primary falls through to `alias`.
    public boolean boolAlias(String key, String alias, boolean def) {
        if (!get(key).isEmpty()) return bool(key, def);
        return bool(alias, def);
    }

    /// `envutil.Uint32`: parse as an unsigned 32-bit decimal (no sign allowed),
    /// `def` when unset, unparseable or out of range. Returned as a `long`
    /// because Java has no unsigned int.
    public long uint32(String key, long def) {
        var v = get(key);
        if (!isDigits(v)) return def;
        try {
            var n = Long.parseLong(v);
            return n <= 0xFFFF_FFFFL ? n : def;
        } catch (NumberFormatException _) {
            return def;
        }
    }

    /// `envutil.Uint`: ok-form unsigned 64-bit parse — empty when unset or
    /// unparseable. The `long` carries the unsigned bit pattern (read it with
    /// `Long.toUnsignedString` if it may exceed `Long.MAX_VALUE`).
    public OptionalLong uint(String key) {
        var v = get(key);
        if (!isDigits(v)) return OptionalLong.empty();
        try {
            return OptionalLong.of(Long.parseUnsignedLong(v));
        } catch (NumberFormatException _) {
            return OptionalLong.empty();
        }
    }

    // ── parsing primitives (shared with DotEnv/Logging) ────────────────────

    /// `strconv.Atoi` on a non-empty string: optional `+`/`-`, base 10.
    static Optional<Integer> parseInt(String v) {
        if (v.isEmpty()) return Optional.empty();
        try {
            return Optional.of(Integer.parseInt(v));
        } catch (NumberFormatException _) {
            return Optional.empty();
        }
    }

    /// [#parseInt], widened to `long`.
    static Optional<Long> parseLong(String v) {
        if (v.isEmpty()) return Optional.empty();
        try {
            return Optional.of(Long.parseLong(v));
        } catch (NumberFormatException _) {
            return Optional.empty();
        }
    }

    /// The Go boolean vocabulary; empty when the word is not one of them.
    static Optional<Boolean> parseBool(String v) {
        return switch (v.trim().toLowerCase(Locale.ROOT)) {
            case "1", "true", "yes", "on" -> Optional.of(true);
            case "0", "false", "no", "off" -> Optional.of(false);
            default -> Optional.empty();
        };
    }

    private static boolean isDigits(String v) {
        if (v.isEmpty()) return false;
        for (int i = 0; i < v.length(); i++) {
            var c = v.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }
}
