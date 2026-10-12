package io.flowcatalyst.platform.ingest;

import io.flowcatalyst.platform.shared.json.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.NullNode;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// [EncodedJson#unwrap]: Go's `TestUnwrapEncodedJSON` cases (Go `8bf67d1`,
/// `internal/platform/shared/sdk/encoded_json_test.go`), each given as the
/// member's JSON text and the expected stored JSON, plus the Java-specific
/// edges (JSON whitespace only, the mapper's strict parse). Every case is
/// also checked idempotent: a second pass never changes the result.
class EncodedJsonTest {

    static Stream<Arguments> cases() {
        return Stream.of(
                // The SDK writers' encoding of an object or array: unwrapped once.
                Arguments.of("encoded object (Java, TS, Laravel, Rust, framework)",
                        "\"{\\\"orderId\\\":\\\"ord_1\\\",\\\"total\\\":1500}\"", "{\"orderId\":\"ord_1\",\"total\":1500}"),
                Arguments.of("encoded empty object", "\"{}\"", "{}"),
                Arguments.of("encoded empty array (PHP json_encode([]))", "\"[]\"", "[]"),
                Arguments.of("encoded list", "\"[1,{\\\"a\\\":true}]\"", "[1,{\"a\":true}]"),
                Arguments.of("encoded object with surrounding whitespace", " \n\"  {\\\"a\\\":1} \\n\" ", "{\"a\":1}"),
                Arguments.of("encoded nested string members stay strings",
                        "\"{\\\"note\\\":\\\"{\\\\\\\"x\\\\\\\":1}\\\"}\"", "{\"note\":\"{\\\"x\\\":1}\"}"),
                Arguments.of("unicode escapes", "\"{\\\"name\\\":\\\"Zo\\u00eb ✓\\\"}\"", "{\"name\":\"Zoë ✓\"}"),

                // Values sent as themselves: never touched.
                Arguments.of("object", "{\"a\":1}", "{\"a\":1}"),
                Arguments.of("array", "[1,2]", "[1,2]"),
                Arguments.of("number", "42", "42"),
                Arguments.of("boolean", "true", "true"),
                Arguments.of("null", "null", "null"),

                // Strings meant as strings: kept.
                Arguments.of("plain string", "\"hello\"", "\"hello\""),
                Arguments.of("empty string", "\"\"", "\"\""),
                Arguments.of("numeric text", "\"42\"", "\"42\""),
                Arguments.of("boolean text", "\"true\"", "\"true\""),
                Arguments.of("null text (Java's null map)", "\"null\"", "\"null\""),
                Arguments.of("encoded string (two levels) kept as sent",
                        "\"\\\"{\\\\\\\"a\\\\\\\":1}\\\"\"", "\"\\\"{\\\\\\\"a\\\\\\\":1}\\\"\""),
                Arguments.of("looks like an object, is not JSON", "\"{not json\"", "\"{not json\""),
                Arguments.of("truncated array", "\"[1,\"", "\"[1,\""),
                Arguments.of("object followed by more text", "\"{} trailing\"", "\"{} trailing\""),
                Arguments.of("two values", "\"{}{}\"", "\"{}{}\""),
                Arguments.of("brace in the middle", "\"total {a}\"", "\"total {a}\""),

                // Java-specific edges: only JSON whitespace is trimmed (String#strip
                // would also take vertical tab and form feed), and the parse is as
                // strict as Go's json.Valid.
                Arguments.of("vertical tab before the brace", "\"\\u000b{}\"", "\"\\u000b{}\""),
                Arguments.of("form feed after the brace", "\"{}\\f\"", "\"{}\\f\""),
                Arguments.of("trailing comma", "\"[1,]\"", "\"[1,]\""),
                Arguments.of("single quotes", "\"{'a':1}\"", "\"{'a':1}\""),
                Arguments.of("comment", "\"{/*c*/}\"", "\"{/*c*/}\""),
                Arguments.of("NaN", "\"[NaN]\"", "\"[NaN]\""),
                Arguments.of("leading zero", "\"[01]\"", "\"[01]\""),
                Arguments.of("unquoted key", "\"{a:1}\"", "\"{a:1}\""),
                Arguments.of("raw control character in a string", "\"[\\\"a\\tb\\\"]\"", "\"[\\\"a\\tb\\\"]\""));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void unwrapsOnlyAnEncodedObjectOrArray(String name, String in, String want) {
        JsonNode got = EncodedJson.unwrap(Json.MAPPER.readTree(in));
        assertThat(got).as(name).isEqualTo(Json.MAPPER.readTree(want));
        assertThat(EncodedJson.unwrap(got)).as("%s: a second pass changes nothing", name).isEqualTo(got);
    }

    /// An absent member (no `data`) and JSON `null` are returned as they are,
    /// for the handler to refuse as before.
    @Test
    void anAbsentOrNullMemberIsReturnedUnchanged() {
        assertThat(EncodedJson.unwrap(null)).isNull();
        assertThat(EncodedJson.unwrap(NullNode.getInstance())).isSameAs(NullNode.getInstance());
    }

    /// Where Java differs from Go's byte-for-byte keep (`docs/spec/sdk-ingest.md`
    /// §3.3): the unwrapped value is exactly what the platform mapper makes of
    /// the same text sent as itself (a long fraction included), and text past
    /// Jackson's 500-level nesting limit, which that parse refuses, stays a string.
    @Test
    void theUnwrappedValueIsTheMappersOwnParseAndItsLimitsKeepTheString() {
        String precise = "{\"a\":0.10000000000000000555111512312578270211815834045,\"b\":12345678901234567890123}";
        JsonNode unwrapped = EncodedJson.unwrap(Json.MAPPER.getNodeFactory().stringNode(precise));
        assertThat(unwrapped).isEqualTo(Json.MAPPER.readTree(precise));
        assertThat(unwrapped.get("a").isDouble()).as("a fraction is read as a double").isTrue();

        String deep = "[".repeat(501) + "]".repeat(501);
        assertThatThrownBy(() -> Json.MAPPER.readTree(deep)).as("sent as itself, refused").isInstanceOf(JacksonException.class);
        JsonNode encoded = Json.MAPPER.getNodeFactory().stringNode(deep);
        assertThat(EncodedJson.unwrap(encoded)).isSameAs(encoded);
        String limit = "[".repeat(500) + "]".repeat(500);
        assertThat(EncodedJson.unwrap(Json.MAPPER.getNodeFactory().stringNode(limit)).isArray()).isTrue();
    }

    /// A kept string is the very node passed in (nothing re-encoded).
    @Test
    void aKeptStringIsTheSameNode() {
        JsonNode s = Json.MAPPER.readTree("\"{not json\"");
        assertThat(EncodedJson.unwrap(s)).isSameAs(s);
    }
}
