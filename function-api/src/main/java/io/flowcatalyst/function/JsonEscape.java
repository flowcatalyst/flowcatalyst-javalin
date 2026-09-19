package io.flowcatalyst.function;

/// The one JSON string-escaping rule this module needs — [Result#fail]'s
/// `{"error": "<reason>"}` body. Not a general JSON writer: escapes `"`,
/// `\\`, the C0 control range and every non-ASCII code unit as a
/// backslash-u four-hex-digit escape (`ensureAscii`-style), which is
/// always valid JSON and never depends on the recipient's own charset
/// handling. Not public — plumbing for [Result], not part of the surface
/// [FunctionSignatureTest] walks.
final class JsonEscape {

    private JsonEscape() {
    }

    static String escape(String raw) {
        StringBuilder out = new StringBuilder(raw.length() + 8);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20 || c > 0x7E) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
