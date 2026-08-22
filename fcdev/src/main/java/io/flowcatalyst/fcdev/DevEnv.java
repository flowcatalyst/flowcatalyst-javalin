package io.flowcatalyst.fcdev;

import io.flowcatalyst.server.EnvReader;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/// The environment fcdev was started with, plus the Go `helpers.go` lookups
/// that seed flag defaults from it (`envStrDefault`, `envIntDefault`,
/// `envBoolDefault`, `setEnvDefault`).
///
/// Java cannot mutate its own process environment, so where Go calls
/// `os.Setenv` fcdev builds a map — this one, copied and extended — and hands
/// it to `io.flowcatalyst.server.Env#load(Map)` and the seeder. Every command
/// takes a `DevEnv` so tests can drive it from a plain map.
public record DevEnv(Map<String, String> vars) {

    public DevEnv {
        vars = Map.copyOf(Objects.requireNonNull(vars, "vars"));
    }

    /// The process environment.
    public static DevEnv system() {
        return new DevEnv(System.getenv());
    }

    public static DevEnv of(Map<String, String> vars) {
        return new DevEnv(vars);
    }

    /// `os.Getenv`: the value, or `""` when unset.
    public String get(String key) {
        var v = vars.get(key);
        return v == null ? "" : v;
    }

    /// `envStrDefault`: the value, or `def` when unset or empty.
    public String str(String key, String def) {
        return reader().or(key, def);
    }

    /// `envIntDefault`: the value when set and it parses, else `def`.
    public int integer(String key, int def) {
        return reader().integer(key, def);
    }

    /// `envBoolDefault`: `1/true/yes/on` / `0/false/no/off` (case-insensitive,
    /// trimmed), anything else — including unset — is `def`.
    public boolean bool(String key, boolean def) {
        return reader().bool(key, def);
    }

    private EnvReader reader() {
        return new EnvReader(vars);
    }

    /// A mutable copy — the working set a command extends (`setEnvDefault`,
    /// explicit overrides) before building the server `Env`.
    public Mutable mutable() {
        return new Mutable(vars);
    }

    /// The in-process stand-in for `os.Setenv` / `setEnvDefault`.
    public static final class Mutable {
        private final LinkedHashMap<String, String> map;

        private Mutable(Map<String, String> initial) {
            this.map = new LinkedHashMap<>(initial);
        }

        /// `os.Setenv`: always wins.
        public Mutable set(String key, String value) {
            map.put(key, Objects.requireNonNull(value, key));
            return this;
        }

        /// `setEnvDefault`: only when the variable is unset or empty — an
        /// explicit operator override is never trampled.
        public Mutable setDefault(String key, String value) {
            var cur = map.get(key);
            if (cur == null || cur.isEmpty()) map.put(key, value);
            return this;
        }

        public String get(String key) {
            var v = map.get(key);
            return v == null ? "" : v;
        }

        public Map<String, String> toMap() {
            return Map.copyOf(map);
        }

        public DevEnv freeze() {
            return new DevEnv(map);
        }
    }
}
