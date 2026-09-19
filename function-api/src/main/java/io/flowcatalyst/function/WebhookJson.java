package io.flowcatalyst.function;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// A minimal, strict, recursive-descent JSON reader (RFC 8259) for exactly
/// the two envelope shapes [Webhook] parses — this jar carries no JSON
/// library, so this is the whole reader, not a general-purpose one.
/// Package-private: [Webhook] is the only caller.
///
/// Two policy choices a JSON reader is free to make either way, pinned here
/// and pinned again by `WebhookJsonTest`'s rule-labelled table:
///
/// - Nesting deeper than [#MAX_DEPTH] is rejected (`WebhookFormatException`)
///   rather than risking a `StackOverflowError` on a hostile or malformed
///   body — this parser is recursive, one Java stack frame per JSON
///   container level.
/// - A duplicate object key is **rejected**, never "last wins" — the two
///   envelopes this parser reads are platform-generated and never
///   legitimately duplicate a key, so a duplicate is a signal something is
///   wrong, not a style choice to paper over silently.
final class WebhookJson {

    static final int MAX_DEPTH = 64;

    private WebhookJson() {
    }

    static JsonValue parse(String text) {
        Parser p = new Parser(text);
        p.skipWhitespace();
        JsonValue value = p.parseValue(0);
        p.skipWhitespace();
        if (!p.atEnd()) {
            throw new WebhookFormatException("trailing garbage after JSON value at offset " + p.pos);
        }
        return value;
    }

    private static final class Parser {
        private final String s;
        private final int len;
        private int pos;

        Parser(String s) {
            this.s = s;
            this.len = s.length();
        }

        boolean atEnd() {
            return pos >= len;
        }

        void skipWhitespace() {
            while (pos < len) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        JsonValue parseValue(int depth) {
            if (depth > MAX_DEPTH) {
                throw new WebhookFormatException("JSON nesting exceeds max depth " + MAX_DEPTH);
            }
            char c = peek();
            return switch (c) {
                case '{' -> parseObject(depth);
                case '[' -> parseArray(depth);
                case '"' -> parseString();
                case 't' -> parseLiteral("true", JsonValue.Bool.TRUE);
                case 'f' -> parseLiteral("false", JsonValue.Bool.FALSE);
                case 'n' -> parseLiteral("null", JsonValue.Null.INSTANCE);
                default -> {
                    if (c == '-' || isDigit(c)) {
                        yield parseNumber();
                    }
                    throw new WebhookFormatException("unexpected character '" + c + "' at offset " + pos);
                }
            };
        }

        JsonValue parseObject(int depth) {
            int start = pos;
            expect('{');
            Map<String, JsonValue> members = new LinkedHashMap<>();
            skipWhitespace();
            if (!atEnd() && peek() == '}') {
                pos++;
                return new JsonValue.Obj(members, s.substring(start, pos));
            }
            while (true) {
                skipWhitespace();
                if (atEnd() || peek() != '"') {
                    throw new WebhookFormatException("expected string key at offset " + pos);
                }
                String key = parseString().value();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                JsonValue value = parseValue(depth + 1);
                if (members.containsKey(key)) {
                    throw new WebhookFormatException("duplicate key '" + key + "' at offset " + pos);
                }
                members.put(key, value);
                skipWhitespace();
                char n = peek();
                if (n == ',') {
                    pos++;
                } else if (n == '}') {
                    pos++;
                    break;
                } else {
                    throw new WebhookFormatException("expected ',' or '}' at offset " + pos);
                }
            }
            return new JsonValue.Obj(members, s.substring(start, pos));
        }

        JsonValue parseArray(int depth) {
            int start = pos;
            expect('[');
            List<JsonValue> items = new ArrayList<>();
            skipWhitespace();
            if (!atEnd() && peek() == ']') {
                pos++;
                return new JsonValue.Arr(items, s.substring(start, pos));
            }
            while (true) {
                skipWhitespace();
                items.add(parseValue(depth + 1));
                skipWhitespace();
                char n = peek();
                if (n == ',') {
                    pos++;
                } else if (n == ']') {
                    pos++;
                    break;
                } else {
                    throw new WebhookFormatException("expected ',' or ']' at offset " + pos);
                }
            }
            return new JsonValue.Arr(items, s.substring(start, pos));
        }

        JsonValue.Str parseString() {
            int start = pos;
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (atEnd()) {
                    throw new WebhookFormatException("unterminated string starting at offset " + start);
                }
                char c = s.charAt(pos++);
                if (c == '"') {
                    break;
                }
                if (c == '\\') {
                    if (atEnd()) {
                        throw new WebhookFormatException("unterminated escape at offset " + pos);
                    }
                    char e = s.charAt(pos++);
                    switch (e) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> sb.append(parseUnicodeEscape());
                        default -> throw new WebhookFormatException(
                                "invalid escape '\\" + e + "' at offset " + (pos - 1));
                    }
                } else if (c < 0x20) {
                    throw new WebhookFormatException("unescaped control character at offset " + (pos - 1));
                } else {
                    sb.append(c);
                }
            }
            return new JsonValue.Str(sb.toString(), s.substring(start, pos));
        }

        char parseUnicodeEscape() {
            if (pos + 4 > len) {
                throw new WebhookFormatException("truncated \\u escape at offset " + pos);
            }
            String hex = s.substring(pos, pos + 4);
            for (int i = 0; i < 4; i++) {
                if (Character.digit(hex.charAt(i), 16) < 0) {
                    throw new WebhookFormatException("invalid \\u escape '" + hex + "' at offset " + pos);
                }
            }
            pos += 4;
            return (char) Integer.parseInt(hex, 16);
        }

        JsonValue parseNumber() {
            int start = pos;
            if (peek() == '-') {
                pos++;
            }
            if (atEnd() || !isDigit(peek())) {
                throw new WebhookFormatException("invalid number at offset " + start);
            }
            if (peek() == '0') {
                pos++;
                if (!atEnd() && isDigit(peek())) {
                    throw new WebhookFormatException("leading zero in number at offset " + start);
                }
            } else {
                while (!atEnd() && isDigit(peek())) {
                    pos++;
                }
            }
            if (!atEnd() && peek() == '.') {
                pos++;
                if (atEnd() || !isDigit(peek())) {
                    throw new WebhookFormatException("invalid fraction at offset " + start);
                }
                while (!atEnd() && isDigit(peek())) {
                    pos++;
                }
            }
            if (!atEnd() && (peek() == 'e' || peek() == 'E')) {
                pos++;
                if (!atEnd() && (peek() == '+' || peek() == '-')) {
                    pos++;
                }
                if (atEnd() || !isDigit(peek())) {
                    throw new WebhookFormatException("invalid exponent at offset " + start);
                }
                while (!atEnd() && isDigit(peek())) {
                    pos++;
                }
            }
            return new JsonValue.Num(s.substring(start, pos));
        }

        JsonValue parseLiteral(String literal, JsonValue value) {
            if (pos + literal.length() > len || !s.regionMatches(pos, literal, 0, literal.length())) {
                throw new WebhookFormatException("invalid literal at offset " + pos);
            }
            pos += literal.length();
            return value;
        }

        void expect(char c) {
            if (atEnd() || s.charAt(pos) != c) {
                throw new WebhookFormatException("expected '" + c + "' at offset " + pos);
            }
            pos++;
        }

        char peek() {
            if (atEnd()) {
                throw new WebhookFormatException("unexpected end of input at offset " + pos);
            }
            return s.charAt(pos);
        }

        static boolean isDigit(char c) {
            return c >= '0' && c <= '9';
        }
    }
}
