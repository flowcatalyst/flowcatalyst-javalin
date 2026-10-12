package io.flowcatalyst.platform.ingest;

import io.flowcatalyst.platform.shared.json.Json;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;

/// Undoes one level of the SDK outbox writers' JSON encoding of a structured
/// batch member (owner ruling 2026-10-12, Go `sdk.UnwrapEncodedJSON`, commit
/// `8bf67d1`: the platform unwraps at ingest). The Java, TypeScript, Laravel,
/// Rust and framework writers put an event's `data` and an audit entry's
/// `operationData` into the batch item as a JSON string holding the
/// serialised value (`"data":"{\"a\":1}"`); stored as sent, subscribers got a
/// string, a `dataOnly` delivery body was a quoted string, and the audit
/// redaction backstop could not see inside it.
///
/// The rule: `node` is replaced by the value its text holds only when
///
///   - `node` is a JSON string (anything else, an object, array, number,
///     boolean, JSON `null` or an absent member, is returned unchanged, so a
///     writer sending the value itself is never touched), and
///   - that text, less surrounding JSON whitespace (space, tab, line feed,
///     carriage return; RFC 8259 §2), starts with `{` or `[` and is exactly
///     one valid JSON value, i.e. an encoded object or array.
///
/// Every other string is a value meant as a string and is kept: `"hello"`,
/// `""`, `"42"`, `"true"`, `"null"` (what Java writes for a null map:
/// unwrapping it would make the event `data is required`), an encoded string
/// (`"\"x\""`), and text that only looks like JSON (`"{not json"`, `"[1,"`,
/// `"{} trailing"`, `"{}{}"`). At most one level is removed: the result is an
/// object or array, never a string, so nothing is unwrapped twice, and a value
/// encoded twice is kept as sent.
///
/// The accepted cost (Go's, the same): a writer that means a string whose
/// text is a JSON object or array gets the object; it has to wrap such a
/// value in an object to have it arrive as text.
///
/// The text is parsed by the platform mapper, the same parse a value sent as
/// itself gets, with trailing tokens refused (`docs/wire-contract.md`,
/// "Encoded `data` and `operationData` at batch ingest", for where Java's
/// parse differs from Go's byte-for-byte keep).
public final class EncodedJson {

    private static final ObjectReader STRICT = Json.MAPPER.readerFor(JsonNode.class)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private EncodedJson() {
    }

    /// The value `node` holds per the rule above, or `node` itself.
    public static JsonNode unwrap(JsonNode node) {
        if (node == null || !node.isString()) return node;
        String inner = trimJsonSpace(node.asString());
        if (inner.isEmpty() || (inner.charAt(0) != '{' && inner.charAt(0) != '[')) return node;
        try {
            JsonNode value = STRICT.readValue(inner);
            return value != null && (value.isObject() || value.isArray()) ? value : node;
        } catch (JacksonException e) {
            return node;
        }
    }

    /// `s` less leading and trailing JSON whitespace only (not
    /// [String#strip], which also removes vertical tab, form feed and the
    /// other Unicode spaces Go's rule keeps).
    private static String trimJsonSpace(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && isJsonSpace(s.charAt(start))) start++;
        while (end > start && isJsonSpace(s.charAt(end - 1))) end--;
        return s.substring(start, end);
    }

    private static boolean isJsonSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }
}
