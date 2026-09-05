package io.flowcatalyst.platform.shared.json;

import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/// Renders a JSON value the way PostgreSQL's `jsonb` output does
/// (`jsonb_out`): object members in jsonb's own order — shorter keys first,
/// then bytewise — with `": "` and `", "` separators, arrays `[a, b]`,
/// scalars as JSON. Go reads a `jsonb` column as text and echoes exactly
/// this, so a stored schema surfaces on the wire in this shape; Java holds
/// the parsed tree and re-serialised it compactly, which the parity harness
/// (S3) saw on every `specVersions[].schema`. Numbers render as Jackson
/// parsed them (`1`, `1.5`), which matches jsonb's numeric text for the
/// values a JSON schema carries.
public final class PgJsonb {

    private PgJsonb() {
    }

    public static String render(JsonNode node) {
        StringBuilder sb = new StringBuilder();
        write(node, sb);
        return sb.toString();
    }

    private static void write(JsonNode node, StringBuilder sb) {
        if (node == null || node.isNull()) {
            sb.append("null");
        } else if (node.isObject()) {
            List<Map.Entry<String, JsonNode>> members = new ArrayList<>();
            node.properties().forEach(members::add);
            members.sort((a, b) -> compareKeys(a.getKey(), b.getKey()));
            sb.append('{');
            boolean first = true;
            for (var m : members) {
                if (!first) sb.append(", ");
                first = false;
                quote(m.getKey(), sb);
                sb.append(": ");
                write(m.getValue(), sb);
            }
            sb.append('}');
        } else if (node.isArray()) {
            sb.append('[');
            boolean first = true;
            for (JsonNode child : node) {
                if (!first) sb.append(", ");
                first = false;
                write(child, sb);
            }
            sb.append(']');
        } else if (node.isString()) {
            quote(node.asString(), sb);
        } else {
            sb.append(Json.write(node));
        }
    }

    /// jsonb key order: by length, then by bytes (`JsonbType`'s `lengthCompareJsonbStringValue`).
    static int compareKeys(String a, String b) {
        byte[] ab = a.getBytes(StandardCharsets.UTF_8);
        byte[] bb = b.getBytes(StandardCharsets.UTF_8);
        if (ab.length != bb.length) return Integer.compare(ab.length, bb.length);
        return Arrays.compareUnsigned(ab, bb);
    }

    private static void quote(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }
}
