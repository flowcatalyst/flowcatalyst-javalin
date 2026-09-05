package io.flowcatalyst.parity;

/// Thrown for an undefined `${name}` — a scenario error, never resolved to
/// an empty string (parity-harness spec §3).
public final class SubstitutionException extends RuntimeException {
    public SubstitutionException(String name) {
        super("undefined substitution: ${" + name + "}");
    }
}
