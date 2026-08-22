package io.flowcatalyst.platform.subscription;

import java.util.Objects;

/// One free-form key/value a subscription carries to its delivery target (a
/// `msg_subscription_custom_configs` row). Replaced wholesale on every write.
public record ConfigEntry(String key, String value) {

    public ConfigEntry {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
    }
}
