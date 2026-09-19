package io.flowcatalyst.function;

import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// `WebhookJson`'s accept/reject table, one row per parsing rule
/// (`docs/spec/function-invocation.md` §7). Grouped by rule so a change to
/// one rule edits a named row, not a comment (CONVENTIONS.md §8 "a parser
/// another subsystem will match against is pinned like a matcher").
class WebhookJsonTest {

    @ParameterizedTest(name = "[{index}] {0}: {1}")
    @MethodSource("rows")
    void row(String rule, String json, boolean accepted) {
        if (accepted) {
            assertThat(WebhookJson.parse(json)).as(rule).isNotNull();
        } else {
            assertThatThrownBy(() -> WebhookJson.parse(json)).as(rule).isInstanceOf(WebhookFormatException.class);
        }
    }

    @org.junit.jupiter.api.Test
    void escapedQuoteRoundTripsToTheLiteralCharacter() {
        JsonValue.Str str = (JsonValue.Str) WebhookJson.parse("\"say \\\"hi\\\"\"");
        assertThat(str.value()).isEqualTo("say \"hi\"");
    }

    @org.junit.jupiter.api.Test
    void basicUnicodeEscapeResolvesToItsCharacter() {
        JsonValue.Str str = (JsonValue.Str) WebhookJson.parse("\"\\u0041\"");
        assertThat(str.value()).isEqualTo("A");
    }

    @org.junit.jupiter.api.Test
    void surrogatePairResolvesToOneCodePoint() {
        JsonValue.Str str = (JsonValue.Str) WebhookJson.parse("\"\\ud83d\\ude00\"");
        assertThat(str.value().codePointCount(0, str.value().length())).isEqualTo(1);
        assertThat(str.value().codePointAt(0)).isEqualTo(0x1F600);
    }

    @org.junit.jupiter.api.Test
    void duplicateKeyIsRejectedNeverLastWins() {
        assertThatThrownBy(() -> WebhookJson.parse("{\"a\":1,\"a\":2}"))
                .isInstanceOf(WebhookFormatException.class)
                .hasMessageContaining("duplicate");
    }

    @org.junit.jupiter.api.Test
    void rawCapturesTheExactSourceSubstringOfAMemberValue() {
        JsonValue.Obj obj = (JsonValue.Obj) WebhookJson.parse("{\"data\":{\"x\": 1,  \"y\":[1,2]}}");
        assertThat(obj.members().get("data").raw()).isEqualTo("{\"x\": 1,  \"y\":[1,2]}");
    }

    static Stream<Arguments> rows() {
        return Stream.of(
                // -- escapes --
                Arguments.of("escape: quote", "\"a\\\"b\"", true),
                Arguments.of("escape: backslash", "\"a\\\\b\"", true),
                Arguments.of("escape: solidus", "\"a\\/b\"", true),
                Arguments.of("escape: backspace", "\"a\\bb\"", true),
                Arguments.of("escape: formfeed", "\"a\\fb\"", true),
                Arguments.of("escape: newline", "\"a\\nb\"", true),
                Arguments.of("escape: carriage-return", "\"a\\rb\"", true),
                Arguments.of("escape: tab", "\"a\\tb\"", true),
                Arguments.of("escape: unicode basic", "\"\\u0041\"", true),
                Arguments.of("escape: unicode surrogate pair", "\"\\ud83d\\ude00\"", true),
                Arguments.of("escape: unrecognised letter", "\"a\\xb\"", false),
                Arguments.of("escape: truncated \\u (too few hex digits before quote)", "\"\\u12\"", false),
                Arguments.of("escape: \\u with non-hex digits", "\"\\u12zz\"", false),
                Arguments.of("escape: unescaped control character in string", "\"a\u0007b\"", false),
                // -- numbers --
                Arguments.of("number: leading zero", "01", false),
                Arguments.of("number: zero alone is fine", "0", true),
                Arguments.of("number: zero then fraction is fine", "0.5", true),
                Arguments.of("number: bare minus", "-", false),
                Arguments.of("number: minus then non-digit", "-a", false),
                Arguments.of("number: negative integer", "-5", true),
                Arguments.of("number: fraction with no digit after dot", "1.", false),
                Arguments.of("number: incomplete exponent (1e)", "1e", false),
                Arguments.of("number: incomplete exponent with sign (1e+)", "1e+", false),
                Arguments.of("number: valid exponent", "1e10", true),
                Arguments.of("number: valid negative exponent", "1E-10", true),
                // -- strings --
                Arguments.of("string: unterminated", "\"abc", false),
                Arguments.of("string: empty is fine", "\"\"", true),
                // -- depth --
                Arguments.of("depth: 64 nested arrays accepted", nestedArrays(64), true),
                Arguments.of("depth: 65 nested arrays rejected", nestedArrays(65), false),
                // -- trailing garbage --
                Arguments.of("trailing garbage after object", "{} x", false),
                Arguments.of("trailing garbage: extra closing brace", "{}}", false),
                Arguments.of("no trailing garbage is fine", "{}", true),
                // -- object/array structure --
                Arguments.of("object: duplicate key rejected", "{\"a\":1,\"a\":2}", false),
                Arguments.of("object: missing colon", "{\"a\" 1}", false),
                Arguments.of("object: missing comma", "{\"a\":1 \"b\":2}", false),
                Arguments.of("object: non-string key", "{1:2}", false),
                Arguments.of("object: empty", "{}", true),
                Arguments.of("object: well-formed", "{\"a\":1,\"b\":2}", true),
                Arguments.of("array: missing comma", "[1 2]", false),
                Arguments.of("array: empty", "[]", true),
                Arguments.of("array: well-formed", "[1,2,3]", true),
                // -- literals --
                Arguments.of("literal: true", "true", true),
                Arguments.of("literal: false", "false", true),
                Arguments.of("literal: null", "null", true),
                Arguments.of("literal: malformed (tru)", "tru", false),
                // -- whole-document edge cases --
                Arguments.of("empty input", "", false),
                Arguments.of("whitespace-only input", "   ", false));
    }

    private static String nestedArrays(int depth) {
        StringBuilder sb = new StringBuilder();
        sb.append("[".repeat(depth)).append("0").append("]".repeat(depth));
        return sb.toString();
    }
}
