package io.flowcatalyst.router.wire;

/// Go `encoding/json` string quoting, reproduced exactly.
///
/// The delivered body is the only JSON the router constructs itself, and it
/// is the input to the HMAC — so its bytes are pinned by the golden vector
/// (`docs/spec/router.md` §6.4) and cannot be left to a mapper's discretion.
/// Go escapes more than the JSON grammar requires: `<`, `>` and `&` become
/// `<`, `>`, `&` because `encoding/json` HTML-escapes by
/// default, and U+2028 / U+2029 are escaped so the output is safe to embed in
/// a script. A message id never contains any of them, but the signature must
/// not depend on that staying true.
final class JsonStrings {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    /// U+2028 LINE SEPARATOR and U+2029 PARAGRAPH SEPARATOR, as ints.
    /// Deliberately not written as `char` literals: Java expands unicode
    /// escapes before lexing, so a literal for either would terminate the
    /// source line and fail to compile.
    private static final int LINE_SEPARATOR = 0x2028;
    private static final int PARAGRAPH_SEPARATOR = 0x2029;

    private JsonStrings() {
    }

    /// Quotes and escapes `s` as Go's `encoding/json` would, surrounding
    /// quotes included.
    static String quote(String s) {
        var out = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                // HTML escaping: on by default in encoding/json.
                case '<' -> out.append("\\u003c");
                case '>' -> out.append("\\u003e");
                case '&' -> out.append("\\u0026");
                default -> {
                    if (c < 0x20) {
                        out.append("\\u00").append(HEX[(c >> 4) & 0xF]).append(HEX[c & 0xF]);
                    } else if (c == LINE_SEPARATOR || c == PARAGRAPH_SEPARATOR) {
                        out.append("\\u202").append(c == LINE_SEPARATOR ? '8' : '9');
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
