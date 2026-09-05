package io.flowcatalyst.platform.shared.openapi;

import io.flowcatalyst.platform.shared.json.Json;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// [SchemaValidator] and [ValidationMessages] against hand-built schemas —
/// every message-table row from `docs/spec/request-schema-validation.md` §1,
/// the required/properties ordering, `additionalProperties`, nullability and
/// the startup keyword gate. [SchemaValidationRouteTest] proves the same
/// engine wired into a real route through [SchemaValidation].
class SchemaValidationTest {

    /// The real committed lockfile — only used here for [Lockfile#resolveRef],
    /// which every hand-built schema below is self-contained enough never to
    /// need; loading it once also means [#realLockfileKeywordsAreAllRecognised]
    /// exercises the actual document.
    private static final Lockfile LOCKFILE = Lockfile.load(Json.MAPPER);

    private static JsonNode node(String json) {
        return Json.MAPPER.readTree(json);
    }

    private static List<Map<String, Object>> validate(String schemaJson, String valueJson) {
        var errors = new ArrayList<Map<String, Object>>();
        SchemaValidator.validateValue(LOCKFILE, node(schemaJson), "body", node(valueJson), errors);
        return errors;
    }

    // ── §1 message table: one test per row ──────────────────────────────

    @Test
    void missingRequiredProperty() {
        var errors = validate(
                """
                {"type":"object","properties":{"name":{"type":"string"}},"required":["name"],"additionalProperties":true}
                """,
                "{}");
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0))
                .containsEntry("location", "body")
                .containsEntry("message", "expected required property name to be present")
                .containsEntry("value", Map.of());
    }

    @Test
    void unexpectedProperty() {
        var errors = validate(
                """
                {"type":"object","properties":{"name":{"type":"string"}},"required":[],"additionalProperties":false}
                """,
                "{\"name\":\"x\",\"extra\":1}");
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0)).containsEntry("location", "body.extra").containsEntry("message", "unexpected property");
    }

    @Test
    void expectedString() {
        var errors = validate("""
                {"type":"string"}
                """, "5");
        assertThat(errors).extracting("message").containsExactly("expected string");
    }

    @Test
    void expectedInteger() {
        var errors = validate("""
                {"type":"integer"}
                """, "\"x\"");
        assertThat(errors).extracting("message").containsExactly("expected integer");
    }

    @Test
    void expectedIntegerOnFractionalNumber() {
        // A JSON number is a valid "integer" schema value only when it has no
        // fractional part — Go: `num != math.Trunc(num)` (validate.go).
        var errors = validate("""
                {"type":"integer"}
                """, "5.5");
        assertThat(errors).extracting("message").containsExactly("expected integer");
    }

    @Test
    void expectedNumber() {
        var errors = validate("""
                {"type":"number"}
                """, "\"x\"");
        assertThat(errors).extracting("message").containsExactly("expected number");
    }

    @Test
    void expectedBoolean() {
        var errors = validate("""
                {"type":"boolean"}
                """, "\"x\"");
        assertThat(errors).extracting("message").containsExactly("expected boolean");
    }

    @Test
    void expectedArray() {
        var errors = validate("""
                {"type":"array","items":{"type":"string"}}
                """, "\"x\"");
        assertThat(errors).extracting("message").containsExactly("expected array");
    }

    @Test
    void expectedObject() {
        var errors = validate("""
                {"type":"object","properties":{},"required":[],"additionalProperties":false}
                """, "\"x\"");
        assertThat(errors).extracting("message").containsExactly("expected object");
    }

    @Test
    void enumMismatch() {
        var errors = validate("""
                {"type":"string","enum":["a","b","c"]}
                """, "\"z\"");
        assertThat(errors).extracting("message").containsExactly("expected value to be one of \"a, b, c\"");
    }

    @Test
    void minLength() {
        var errors = validate("""
                {"type":"string","minLength":3}
                """, "\"ab\"");
        assertThat(errors).extracting("message").containsExactly("expected length >= 3");
    }

    @Test
    void maxLength() {
        var errors = validate("""
                {"type":"string","maxLength":3}
                """, "\"abcd\"");
        assertThat(errors).extracting("message").containsExactly("expected length <= 3");
    }

    @Test
    void minimum() {
        var errors = validate("""
                {"type":"integer","minimum":3}
                """, "2");
        assertThat(errors).extracting("message").containsExactly("expected number >= 3");
    }

    @Test
    void maximum() {
        var errors = validate("""
                {"type":"number","maximum":0.5}
                """, "0.75");
        assertThat(errors).extracting("message").containsExactly("expected number <= 0.5");
    }

    @Test
    void pattern() {
        var errors = validate("""
                {"type":"string","pattern":"^[a-z]+$"}
                """, "\"ABC\"");
        assertThat(errors).extracting("message").containsExactly("expected string to match pattern ^[a-z]+$");
    }

    @Test
    void formatDateTime() {
        var errors = validate("""
                {"type":"string","format":"date-time"}
                """, "\"not-a-date\"");
        assertThat(errors).extracting("message").containsExactly("expected string to be RFC 3339 date-time");
        assertThat(validate("""
                {"type":"string","format":"date-time"}
                """, "\"2024-01-02T03:04:05Z\"")).isEmpty();
    }

    @Test
    void formatDate() {
        var errors = validate("""
                {"type":"string","format":"date"}
                """, "\"01/02/2024\"");
        assertThat(errors).extracting("message").containsExactly("expected string to be RFC 3339 date");
        assertThat(validate("""
                {"type":"string","format":"date"}
                """, "\"2024-01-02\"")).isEmpty();
    }

    @Test
    void formatTime() {
        var errors = validate("""
                {"type":"string","format":"time"}
                """, "\"25:00:00\"");
        assertThat(errors).extracting("message").containsExactly("expected string to be RFC 3339 time");
        assertThat(validate("""
                {"type":"string","format":"time"}
                """, "\"03:04:05\"")).isEmpty();
    }

    /// A required query/path parameter's own missing-value message (huma
    /// `huma.go` request binding, NOT `validate.go` — spec §2/§3 of
    /// `SchemaValidation`'s class doc corrects the written spec's claim that
    /// this is the generic "expected required property" message). Pinned
    /// directly here because no route in the committed lockfile currently
    /// declares a required query parameter to exercise end to end
    /// (2026-09-05 survey); [SchemaValidationRouteTest] proves the sibling
    /// "invalid integer"/"invalid boolean" coercion messages against a real
    /// route instead.
    @Test
    void requiredParameterMissingMessages() {
        assertThat(ValidationMessages.requiredQueryParameterMissing()).isEqualTo("required query parameter is missing");
        assertThat(ValidationMessages.requiredPathParameterMissing()).isEqualTo("required path parameter is missing");
    }

    @Test
    void formatEmail() {
        var errors = validate("""
                {"type":"string","format":"email"}
                """, "\"not-an-email\"");
        assertThat(errors).hasSize(1);
        assertThat((String) errors.get(0).get("message")).startsWith("expected string to be RFC 5322 email: ");
        assertThat(validate("""
                {"type":"string","format":"email"}
                """, "\"a@b.com\"")).isEmpty();
    }

    @Test
    void formatUri() {
        var errors = validate("""
                {"type":"string","format":"uri"}
                """, "\"a b\"");
        assertThat(errors).hasSize(1);
        assertThat((String) errors.get(0).get("message")).startsWith("expected string to be RFC 3986 uri: ");
        assertThat(validate("""
                {"type":"string","format":"uri"}
                """, "\"https://example.com\"")).isEmpty();
    }

    @Test
    void formatUuid() {
        var errors = validate("""
                {"type":"string","format":"uuid"}
                """, "\"not-a-uuid\"");
        assertThat(errors).hasSize(1);
        assertThat((String) errors.get(0).get("message")).startsWith("expected string to be RFC 4122 uuid: ");
        assertThat(validate("""
                {"type":"string","format":"uuid"}
                """, "\"123e4567-e89b-12d3-a456-426614174000\"")).isEmpty();
    }

    @Test
    void formatHostname() {
        var errors = validate("""
                {"type":"string","format":"hostname"}
                """, "\"invalid_host\"");
        assertThat(errors).extracting("message").containsExactly("expected string to be RFC 5890 hostname");
        assertThat(validate("""
                {"type":"string","format":"hostname"}
                """, "\"example.com\"")).isEmpty();
    }

    @Test
    void formatIpv4() {
        var errors = validate("""
                {"type":"string","format":"ipv4"}
                """, "\"999.1.1.1\"");
        assertThat(errors).extracting("message").containsExactly("expected string to be RFC 2673 ipv4");
        assertThat(validate("""
                {"type":"string","format":"ipv4"}
                """, "\"192.168.1.1\"")).isEmpty();
    }

    @Test
    void formatIpv6() {
        var errors = validate("""
                {"type":"string","format":"ipv6"}
                """, "\"gggg::1\"");
        assertThat(errors).extracting("message").containsExactly("expected string to be RFC 2373 ipv6");
        assertThat(validate("""
                {"type":"string","format":"ipv6"}
                """, "\"::1\"")).isEmpty();
    }

    @Test
    void unrecognisedFormatValueIsSilentlyIgnored() {
        // huma's own validateFormat switch has no default case — an unsupported
        // format string is documentation only. Same authority, same behaviour here.
        assertThat(validate("""
                {"type":"string","format":"not-a-real-format"}
                """, "\"anything\"")).isEmpty();
    }

    /// Rows [SchemaValidator] does not wire into live validation
    /// (`multipleOf` / array `minItems`/`maxItems`/`uniqueItems` /
    /// `minProperties`/`maxProperties` / `dependentRequired` are not in the
    /// implemented-keyword set — see its class doc) still get their message
    /// text pinned directly, so the day a keyword IS added the text is
    /// already proven correct.
    @Test
    void messagesForKeywordsNotYetWiredIntoValidation() {
        assertThat(ValidationMessages.multipleOf(5)).isEqualTo("expected number to be a multiple of 5");
        assertThat(ValidationMessages.minItems(2)).isEqualTo("expected array length >= 2");
        assertThat(ValidationMessages.maxItems(2)).isEqualTo("expected array length <= 2");
        assertThat(ValidationMessages.uniqueItems()).isEqualTo("expected array items to be unique");
        assertThat(ValidationMessages.minProperties(2)).isEqualTo("expected object with at least 2 properties");
        assertThat(ValidationMessages.maxProperties(2)).isEqualTo("expected object with at most 2 properties");
        assertThat(ValidationMessages.dependentRequired("b", "a")).isEqualTo("expected property b to be present when a is present");
    }

    /// Go's `%v` (spec §1's closing paragraph): a whole-number bound prints
    /// without a decimal point, a fractional one prints in full.
    @Test
    void goNumberFormatting() {
        assertThat(GoNumbers.format(3.0)).isEqualTo("3");
        assertThat(GoNumbers.format(0.5)).isEqualTo("0.5");
    }

    // ── ordering ─────────────────────────────────────────────────────────

    /// Corrects the written spec: Go's real order is a SINGLE pass over the
    /// schema's properties (which this lockfile already lists alphabetically,
    /// since it was dumped by Go's `encoding/json`, which sorts map keys) —
    /// missing-required and present-and-wrong-type errors interleave in that
    /// one order, there is no separate "required first" pass (huma
    /// `validate.go` `handleMapString` walks one `propertyNames` list; see
    /// [SchemaValidator]'s class doc for the source citations). This test
    /// pins that: `alpha` (missing) and `gamma` (missing) sandwich `beta`
    /// (present, wrong type) — a "required first" implementation would put
    /// both required errors before the type error instead.
    @Test
    void orderingIsOnePassInSchemaPropertyOrderNotRequiredFirst() {
        var errors = validate(
                """
                {"type":"object","additionalProperties":true,
                 "properties":{"alpha":{"type":"string"},"beta":{"type":"string"},"gamma":{"type":"string"}},
                 "required":["alpha","gamma"]}
                """,
                "{\"beta\":5}");
        assertThat(errors).extracting("message").containsExactly(
                "expected required property alpha to be present",
                "expected string",
                "expected required property gamma to be present");
        assertThat(errors).extracting("location").containsExactly("body", "body.beta", "body");
    }

    // ── additionalProperties: true / false / schema ─────────────────────

    @Test
    void additionalPropertiesTrueAllowsExtraFieldsSilently() {
        // This is the actual shape of every top-level Create/Update request in the
        // committed lockfile (httpcompat.RelaxRequestBodies) — NOT false, contrary
        // to the written spec and the brief's own design-constraints note. See
        // SchemaValidator's class doc for the source citation.
        var errors = validate("""
                {"type":"object","properties":{"name":{"type":"string"}},"required":[],"additionalProperties":true}
                """, "{\"name\":\"x\",\"extra\":1}");
        assertThat(errors).isEmpty();
    }

    @Test
    void additionalPropertiesSchemaValidatesTheExtraValue() {
        var errors = validate("""
                {"type":"object","properties":{},"required":[],"additionalProperties":{"type":"integer"}}
                """, "{\"extra\":\"not-an-integer\"}");
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0)).containsEntry("location", "body.extra").containsEntry("message", "expected integer");
    }

    @Test
    void caseInsensitivePropertyMatchAcceptsAKnownFieldSentWithDifferentCasing() {
        // spec §2: huma matches property names case-insensitively; a case
        // variant of a KNOWN field is accepted (not "unexpected property")
        // and its value is still read and validated under the lockfile spelling.
        var errors = validate("""
                {"type":"object","properties":{"name":{"type":"integer"}},"required":["name"],"additionalProperties":false}
                """, "{\"Name\":\"not-an-integer\"}");
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0)).containsEntry("location", "body.name").containsEntry("message", "expected integer");
    }

    // ── nullable vs explicit null ────────────────────────────────────────

    @Test
    void requiredNonNullablePropertySentAsExplicitNullIsATypeError() {
        var errors = validate("""
                {"type":"object","properties":{"name":{"type":"string"}},"required":["name"],"additionalProperties":true}
                """, "{\"name\":null}");
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0)).containsEntry("location", "body.name").containsEntry("message", "expected string");
        // Go omits `value` only when the offending value is Go `nil` — an
        // explicit JSON null is exactly that.
        assertThat(errors.get(0)).doesNotContainKey("value");
    }

    @Test
    void optionalPropertySentAsExplicitNullIsAccepted() {
        var errors = validate("""
                {"type":"object","properties":{"name":{"type":"string"}},"required":[],"additionalProperties":true}
                """, "{\"name\":null}");
        assertThat(errors).isEmpty();
    }

    @Test
    void requiredNullablePropertySentAsExplicitNullIsAccepted() {
        var errors = validate("""
                {"type":"object","properties":{"name":{"type":"string","nullable":true}},"required":["name"],"additionalProperties":true}
                """, "{\"name\":null}");
        assertThat(errors).isEmpty();
    }

    // ── readOnly ─────────────────────────────────────────────────────────

    @Test
    void readOnlyPropertyIsNotRequiredWhenAbsent() {
        var errors = validate("""
                {"type":"object","properties":{"id":{"type":"string","readOnly":true}},"required":["id"],"additionalProperties":true}
                """, "{}");
        assertThat(errors).isEmpty();
    }

    @Test
    void readOnlyPropertyPresentIsStillTypeChecked() {
        // spec §2: readOnly members present in a request are not rejected for
        // being present, but their VALUE is still validated normally.
        var errors = validate("""
                {"type":"object","properties":{"id":{"type":"string","readOnly":true}},"required":["id"],"additionalProperties":true}
                """, "{\"id\":5}");
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0)).containsEntry("location", "body.id").containsEntry("message", "expected string");
    }

    // ── arrays ───────────────────────────────────────────────────────────

    @Test
    void arrayItemErrorsCarryTheIndexInTheLocation() {
        var errors = validate("""
                {"type":"array","items":{"type":"integer"}}
                """, "[1,\"x\",3,\"y\"]");
        assertThat(errors).extracting("location").containsExactly("body[1]", "body[3]");
        assertThat(errors).extracting("message").containsExactly("expected integer", "expected integer");
    }

    // ── the startup keyword gate ─────────────────────────────────────────

    @Test
    void unrecognisedKeywordFailsAtStartup() {
        var bad = node("""
                {"type":"array","items":{"type":"integer"},"minItems":1}
                """);
        assertThatThrownBy(() -> SchemaValidator.checkKeywords(LOCKFILE, bad, new HashSet<>()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("minItems");
    }

    @Test
    void unrecognisedKeywordNestedInPropertiesFailsAtStartup() {
        var bad = node("""
                {"type":"object","properties":{"code":{"type":"string","exclusiveMinimum":0}},"required":[],"additionalProperties":true}
                """);
        assertThatThrownBy(() -> SchemaValidator.checkKeywords(LOCKFILE, bad, new HashSet<>()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exclusiveMinimum");
    }

    @Test
    void recognisedKeywordsAndAnnotationKeysPassTheStartupCheck() {
        var ok = node("""
                {"type":"object","description":"d","properties":{"name":{"type":"string","description":"n","examples":["a"],"readOnly":true}},
                 "required":["name"],"additionalProperties":false}
                """);
        assertThatCode(() -> SchemaValidator.checkKeywords(LOCKFILE, ok, new HashSet<>())).doesNotThrowAnyException();
    }

    /// The real committed lockfile — every request-body and query/path
    /// parameter schema it carries — must build cleanly: if this ever throws,
    /// a lockfile bump introduced a schema keyword nobody taught the
    /// validator about yet (see [SchemaValidation#build]).
    @Test
    void realLockfileKeywordsAreAllRecognised() {
        assertThatCode(() -> SchemaValidation.build(LOCKFILE)).doesNotThrowAnyException();
    }
}
