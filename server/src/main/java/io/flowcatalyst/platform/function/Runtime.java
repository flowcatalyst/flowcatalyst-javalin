package io.flowcatalyst.platform.function;

import io.flowcatalyst.sdk.usecase.UseCaseException;

import java.util.Locale;
import java.util.Optional;

/// The runtime a function executes in (spec `function-registry.md` §4.1,
/// §4.4). Stored as the constant name (`JVM` / `WASM`) in a plain column per
/// `CONVENTIONS.md` §2; written lower-case wherever the manifest's own JSON
/// shape appears (wire and stored alike — spec §4.4).
public enum Runtime {
    JVM, WASM;

    /// Stored reader for a plain column — exact constant name.
    ///
    /// @throws IllegalArgumentException `raw` is not `JVM` or `WASM`
    public static Runtime parse(String raw) {
        return switch (raw) {
            case "JVM" -> JVM;
            case "WASM" -> WASM;
            case null, default -> throw new IllegalArgumentException("unrecognised runtime: " + raw);
        };
    }

    /// The message [#parseStrict] throws on an unrecognised value, exposed
    /// so [Manifest]'s collecting parser can reuse it via [#tryParseStrict]
    /// without catching this class's exception (`CONVENTIONS.md` §8).
    public static final String INVALID_MESSAGE = "runtime is required and must be jvm or wasm";

    /// Non-throwing companion of [#parseStrict]: empty when `raw` is not
    /// (case-insensitively) `jvm` or `wasm`, never throws.
    public static Optional<Runtime> tryParseStrict(String raw) {
        String lower = raw == null ? "" : raw.toLowerCase(Locale.ROOT);
        return switch (lower) {
            case "jvm" -> Optional.of(JVM);
            case "wasm" -> Optional.of(WASM);
            default -> Optional.empty();
        };
    }

    /// Wire reader — case-insensitive (spec §4.4).
    ///
    /// @throws UseCaseException validation `RUNTIME_INVALID`
    public static Runtime parseStrict(String raw) {
        return tryParseStrict(raw)
                .orElseThrow(() -> UseCaseException.validation("RUNTIME_INVALID", INVALID_MESSAGE));
    }

    /// The lower-case spelling used in the manifest's own JSON shape (spec §4.4).
    public String wireValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
