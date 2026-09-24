package io.flowcatalyst.sdk.usecase;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.Locale;
import java.util.Set;

/// The one redaction rule, in Java (`docs/spec/audit-redaction.md`, "The
/// rule (one definition, three languages)"): audit rows never carry a
/// password, secret or token in the clear, wherever it appears in a command
/// document. The TypeScript and Laravel SDKs carry byte-identical rules,
/// tested against the same `docs/spec/audit-redaction-vectors.json` vectors.
public final class AuditRedaction {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final String MASK = "***";

    /// A normalised (lower-cased, `_`/`-` stripped) key ending with one of
    /// these is secret.
    private static final Set<String> SECRET_SUFFIXES = Set.of(
            "password", "passwordhash", "secret", "secretref", "passphrase", "token");

    /// A normalised key equal to one of these is secret.
    private static final Set<String> SECRET_EXACT = Set.of(
            "apikey", "privatekey", "authorization", "cookie");

    private AuditRedaction() {
    }

    /// Redacts `command`, walking objects and arrays; never mutates its
    /// input (a fresh tree is returned; unmasked leaf values are shared).
    /// `masked` names top-level field names to mask unconditionally, in
    /// addition to the name rule (a command's declared
    /// [AuditMasked#auditMaskedFields]).
    public static JsonNode redact(JsonNode command, Set<String> masked) {
        if (command == null) return null;
        Set<String> maskedFields = masked == null ? Set.of() : masked;
        return redactNode(command, maskedFields, true);
    }

    private static JsonNode redactNode(JsonNode node, Set<String> maskedTopLevel, boolean topLevel) {
        if (node.isObject()) {
            ObjectNode out = NODES.objectNode();
            for (var entry : node.properties()) {
                String key = entry.getKey();
                JsonNode value = entry.getValue();
                boolean mask = isSecretKey(key) || (topLevel && maskedTopLevel.contains(key));
                out.set(key, mask ? maskValue(value) : redactNode(value, maskedTopLevel, false));
            }
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = NODES.arrayNode(node.size());
            for (JsonNode element : node) {
                out.add(redactNode(element, maskedTopLevel, false));
            }
            return out;
        }
        // A leaf that isn't behind a secret key: untouched, and immutable, so
        // it is safe to share with the input tree.
        return node;
    }

    /// A secret field's value becomes `"***"` — whatever its type — except
    /// `null` (kept) and booleans (kept).
    private static JsonNode maskValue(JsonNode value) {
        if (value == null || value.isNull() || value.isBoolean()) return value;
        return NODES.stringNode(MASK);
    }

    /// A key is secret when, lower-cased with `_`/`-` removed, it ends with
    /// one of [#SECRET_SUFFIXES] or equals one of [#SECRET_EXACT].
    private static boolean isSecretKey(String key) {
        String normalized = key.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        if (SECRET_EXACT.contains(normalized)) return true;
        for (String suffix : SECRET_SUFFIXES) {
            if (normalized.endsWith(suffix)) return true;
        }
        return false;
    }
}
