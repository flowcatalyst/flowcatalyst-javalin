package io.flowcatalyst.platform.shared.openapi;

import java.util.List;
import java.util.stream.Collectors;

/// One static method per row of `docs/spec/request-schema-validation.md` §1's
/// message table (Go: huma `validation/messages.go` + `schema.go`
/// `PrecomputeMessages`), each returning the exact wire string.
///
/// Every row of the spec table has a method here, including the keywords
/// [SchemaValidation] does not wire into live validation ([#multipleOf],
/// [#minItems], [#maxItems], [#uniqueItems], [#minProperties],
/// [#maxProperties], [#dependentRequired] — `multipleOf` / `minItems` /
/// `maxItems` / `uniqueItems` / `minProperties` / `maxProperties` /
/// `dependentRequired` are not in the implemented-keyword set, so the
/// validator throws `IllegalStateException` at startup if a lockfile bump
/// ever adds one rather than silently skipping it; these methods exist so
/// the message text is pinned by a test the day a keyword *is* added,
/// without waiting on the schema-walker changing first).
final class ValidationMessages {

    private ValidationMessages() {
    }

    // ── object ───────────────────────────────────────────────────────────

    static String requiredProperty(String name) {
        return "expected required property " + name + " to be present";
    }

    static String unexpectedProperty() {
        return "unexpected property";
    }

    static String minProperties(long n) {
        return "expected object with at least " + n + " properties";
    }

    static String maxProperties(long n) {
        return "expected object with at most " + n + " properties";
    }

    static String dependentRequired(String dependent, String trigger) {
        return "expected property " + dependent + " to be present when " + trigger + " is present";
    }

    // ── type ─────────────────────────────────────────────────────────────

    static String expectedString() {
        return "expected string";
    }

    static String expectedInteger() {
        return "expected integer";
    }

    static String expectedNumber() {
        return "expected number";
    }

    static String expectedBoolean() {
        return "expected boolean";
    }

    static String expectedArray() {
        return "expected array";
    }

    static String expectedObject() {
        return "expected object";
    }

    // ── enum ─────────────────────────────────────────────────────────────

    /// `values` rendered the way Go's `fmt.Sprintf("%v", v)` renders each
    /// enum member (bare, no quoting) and joined with `", "`.
    static String expectedOneOf(List<String> values) {
        return "expected value to be one of \"" + values.stream().collect(Collectors.joining(", ")) + "\"";
    }

    // ── string ───────────────────────────────────────────────────────────

    static String minLength(long n) {
        return "expected length >= " + n;
    }

    static String maxLength(long n) {
        return "expected length <= " + n;
    }

    static String matchPattern(String pattern) {
        return "expected string to match pattern " + pattern;
    }

    // ── number ───────────────────────────────────────────────────────────

    static String minimum(double n) {
        return "expected number >= " + GoNumbers.format(n);
    }

    static String maximum(double n) {
        return "expected number <= " + GoNumbers.format(n);
    }

    static String multipleOf(double n) {
        return "expected number to be a multiple of " + GoNumbers.format(n);
    }

    // ── array ────────────────────────────────────────────────────────────

    static String minItems(long n) {
        return "expected array length >= " + n;
    }

    static String maxItems(long n) {
        return "expected array length <= " + n;
    }

    static String uniqueItems() {
        return "expected array items to be unique";
    }

    // ── format ───────────────────────────────────────────────────────────

    static String expectedRfc3339DateTime() {
        return "expected string to be RFC 3339 date-time";
    }

    static String expectedRfc3339Date() {
        return "expected string to be RFC 3339 date";
    }

    static String expectedRfc3339Time() {
        return "expected string to be RFC 3339 time";
    }

    /// `detail` is Go's `net/mail` error text — spec §1: only the prefix is
    /// contractual, this suffix is compared loosely.
    static String expectedRfc5322Email(String detail) {
        return "expected string to be RFC 5322 email: " + detail;
    }

    static String expectedRfc3986Uri(String detail) {
        return "expected string to be RFC 3986 uri: " + detail;
    }

    static String expectedRfc4122Uuid(String detail) {
        return "expected string to be RFC 4122 uuid: " + detail;
    }

    static String expectedRfc5890Hostname() {
        return "expected string to be RFC 5890 hostname";
    }

    static String expectedRfc2673Ipv4() {
        return "expected string to be RFC 2673 ipv4";
    }

    static String expectedRfc2373Ipv6() {
        return "expected string to be RFC 2373 ipv6";
    }

    // ── parameters (huma `huma.go`, NOT `validate.go` — a different code
    //    path: query/path values are coerced from strings before Validate
    //    ever runs, so a bad value never reaches the messages above) ──────

    static String requiredQueryParameterMissing() {
        return "required query parameter is missing";
    }

    static String requiredPathParameterMissing() {
        return "required path parameter is missing";
    }

    static String invalidInteger() {
        return "invalid integer";
    }

    static String invalidBoolean() {
        return "invalid boolean";
    }
}
