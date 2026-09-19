package io.flowcatalyst.function;

import java.util.NoSuchElementException;
import java.util.Optional;

/// Manifest-declared secret references, resolved by the host. Same shape as
/// [Config]; kept as a separate type so a function's declared secrets and
/// its plain config can never be confused at a call site, and so an
/// implementation can apply different logging/redaction rules to each.
public interface Secrets {

    /// The value for `key`, or empty if it was not declared. Never logged by
    /// an implementation of this interface.
    Optional<String> get(String key);

    /// The value for `key`.
    ///
    /// @throws NoSuchElementException if `key` was not declared
    default String require(String key) {
        return get(key).orElseThrow(() -> new NoSuchElementException("secret key not declared: " + key));
    }
}
