package io.flowcatalyst.fnhost.http;

import java.nio.charset.StandardCharsets;

/// Every host-generated error body is `{"error": CODE, "message": …}`
/// (spec `function-host-listener.md` §2: "never a stack trace or an
/// exception message from function code") — a small hand-escaped writer, the
/// same reasoning `io.flowcatalyst.function.Result#fail` uses in the API jar:
/// this package has no JSON library dependency of its own to reach for.
final class ErrorBody {

    private ErrorBody() {
    }

    static byte[] json(String code, String message) {
        String body = "{\"error\":\"" + escape(code) + "\",\"message\":\"" + escape(message) + "\"}";
        return body.getBytes(StandardCharsets.UTF_8);
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
