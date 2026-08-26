package io.flowcatalyst.platform.openapispecs;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.sdk.usecase.UseCaseException;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/// The incoming OpenAPI document, parsed once (spec §2): a JSON object that
/// carries a top-level `openapi` or `swagger` key. Carries the two facts the
/// sync needs — the declared `info.version` and the canonical-JSON hash that
/// the "unchanged" short-circuit compares.
/// `asText()`/`isTextual()` are deprecated in Jackson 3 in favour of
/// `stringValue()`/`isString()`, which are NOT equivalent — `stringValue()`
/// throws on a non-string node instead of coercing, and returns `null` rather
/// than `""` for JSON `null`. Kept deliberately, suppressed rather than
/// migrated: this record's whole job is tolerating whatever shape an
/// incoming OpenAPI document has.
@SuppressWarnings("deprecation")
public record OpenApiDocument(JsonNode root) {

    public static final String INVALID = "INVALID_OPENAPI_SPEC";

    public OpenApiDocument {
        Objects.requireNonNull(root, "root");
    }

    /// The one parser: every rejection is 400 `INVALID_OPENAPI_SPEC`.
    public static OpenApiDocument parse(JsonNode spec) {
        if (spec == null || !spec.isObject()) {
            throw UseCaseException.validation(INVALID, "OpenAPI spec must be a JSON object");
        }
        if (!spec.has("openapi") && !spec.has("swagger")) {
            throw UseCaseException.validation(INVALID, "Spec is missing the top-level `openapi` (or `swagger`) field");
        }
        return new OpenApiDocument(spec);
    }

    /// `info.version` when it is a non-blank string.
    public Optional<String> infoVersion() {
        JsonNode info = root.get("info");
        if (info == null || !info.isObject()) return Optional.empty();
        JsonNode v = info.get("version");
        if (v == null || !v.isTextual() || v.asText().isBlank()) return Optional.empty();
        return Optional.of(v.asText());
    }

    /// SHA-256 hex of the canonical JSON (object keys sorted at every level,
    /// no whitespace) — key order and formatting do not change the hash.
    public String hash() {
        try {
            byte[] canonical = Json.MAPPER.writeValueAsBytes(canonicalise(root));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (JacksonException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("canonical JSON hash", e);
        }
    }

    /// Objects become sorted maps; scalars stay `JsonNode`s so Jackson writes
    /// them as it parsed them.
    private static Object canonicalise(JsonNode node) {
        if (node.isObject()) {
            var sorted = new TreeMap<String, Object>();
            node.properties().forEach(e -> sorted.put(e.getKey(), canonicalise(e.getValue())));
            return sorted;
        }
        if (node.isArray()) {
            var items = new Object[node.size()];
            for (int i = 0; i < node.size(); i++) items[i] = canonicalise(node.get(i));
            return items;
        }
        return node;
    }
}
