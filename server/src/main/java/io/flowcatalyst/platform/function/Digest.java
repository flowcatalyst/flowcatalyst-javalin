package io.flowcatalyst.platform.function;

import io.flowcatalyst.sdk.usecase.UseCaseException;

import java.util.Objects;
import java.util.regex.Pattern;

/// A published version's artifact digest (spec `function-registry.md` §6.2):
/// `sha256:` followed by 64 lower-case hex characters. No normalisation — an
/// upper-case digest is rejected, not folded.
///
/// @param value the digest, unchanged from the input
public record Digest(String value) {

    private static final Pattern PATTERN = Pattern.compile("^sha256:[0-9a-f]{64}$");

    public Digest {
        Objects.requireNonNull(value, "value");
    }

    /// @throws UseCaseException validation `DIGEST_INVALID`
    public static Digest parse(String raw) {
        if (raw == null || !PATTERN.matcher(raw).matches()) {
            throw UseCaseException.validation("DIGEST_INVALID",
                    "digest must be sha256: followed by 64 lower-case hex characters");
        }
        return new Digest(raw);
    }
}
