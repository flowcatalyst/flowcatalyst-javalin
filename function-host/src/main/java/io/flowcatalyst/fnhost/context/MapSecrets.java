package io.flowcatalyst.fnhost.context;

import io.flowcatalyst.function.Secrets;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/// [Secrets] over the desired-state entry's own `secrets` map (spec
/// `function-context.md` §2), same shape as [MapConfig] and the same
/// stricter `require` (`IllegalStateException` naming the key). `toString`
/// prints keys only (spec §2: "`Secrets.toString` prints keys") — never a
/// value, so an accidental `LOG.info("{}", ctx.secrets())` cannot leak one.
public final class MapSecrets implements Secrets {

    private final Map<String, String> values;

    public MapSecrets(Map<String, String> values) {
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
            throw new IllegalStateException("secret key not declared or not set: " + key);
        }
        return value;
    }

    @Override
    public String toString() {
        return "Secrets[keys=" + values.keySet() + "]";
    }
}
