package io.flowcatalyst.fnhost.context;

import io.flowcatalyst.function.Config;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/// [Config] over the desired-state entry's own `config` map (spec
/// `function-context.md` §2) — already restricted, by the platform, to the
/// keys this version's manifest declares (D4a). `require` on an undeclared
/// or unset key throws [IllegalStateException] naming the key (spec §2: "`get`
/// ⇒ `Optional`; `require` on an undeclared or unset key ⇒
/// `IllegalStateException` naming the key"), overriding [Config#require]'s
/// own default (`NoSuchElementException`) — the host's contract is stricter
/// than the API jar's generic fallback.
public final class MapConfig implements Config {

    private final Map<String, String> values;

    public MapConfig(Map<String, String> values) {
        this.values = Map.copyOf(Objects.requireNonNull(values, "values"));
    }

    @Override
    public Optional<String> get(String key) {
        return Optional.ofNullable(values.get(key));
    }

    @Override
    public String require(String key) {
        String value = values.get(key);
        if (value == null) {
            throw new IllegalStateException("config key not declared or not set: " + key);
        }
        return value;
    }

    @Override
    public String toString() {
        return "Config[keys=" + values.keySet() + "]";
    }
}
