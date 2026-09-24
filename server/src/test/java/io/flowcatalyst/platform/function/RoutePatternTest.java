package io.flowcatalyst.platform.function;

import io.flowcatalyst.sdk.result.Result;
import io.flowcatalyst.sdk.usecase.UseCaseError;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Spec `function-registry.md` §5.2 (parse + match) and §5.3 (precedence +
/// ambiguity), including §8 M7/M8.
class RoutePatternTest {

    // ── parse — §5.2's Accepted/Rejected table ───────────────────────────────

    @ParameterizedTest(name = "[{index}] leading slash accepted: {0}")
    @CsvSource({"/", "/invoices"})
    void leadingSlashAccepted(String raw) {
        assertThat(RoutePattern.parse(raw).value()).isEqualTo(raw);
    }

    @ParameterizedTest(name = "[{index}] leading slash rejected: ''{0}''")
    @CsvSource({"invoices", "''"})
    void leadingSlashRejected(String raw) {
        assertRejected(raw);
    }

    @Test
    void nullRejected() {
        assertRejected(null);
    }

    @Test
    void literalSegmentAccepted() {
        RoutePattern pattern = RoutePattern.parse("/v1/invoices.json");
        assertThat(pattern.segments()).containsExactly(
                new RoutePattern.Literal("v1"), new RoutePattern.Literal("invoices.json"));
    }

    @ParameterizedTest(name = "[{index}] literal rejected: {0}")
    @CsvSource({"'/in voices'", "/a%20b", "/a?b", "/a#b"})
    void literalSegmentRejected(String raw) {
        assertRejected(raw);
    }

    @Test
    void paramSegmentAccepted() {
        assertThat(RoutePattern.parse("/invoices/{id}").segments())
                .containsExactly(new RoutePattern.Literal("invoices"), new RoutePattern.Param("id"));
        assertThat(RoutePattern.parse("/a/{x}/b/{y}").segments())
                .containsExactly(new RoutePattern.Literal("a"), new RoutePattern.Param("x"),
                        new RoutePattern.Literal("b"), new RoutePattern.Param("y"));
    }

    @ParameterizedTest(name = "[{index}] param rejected: {0}")
    @CsvSource({"/{}", "/{1d}", "/a{id}", "/{id}x"})
    void paramSegmentRejected(String raw) {
        assertRejected(raw);
    }

    @Test
    void duplicateParamNameRejected() {
        assertRejected("/{a}/{a}");
    }

    @ParameterizedTest(name = "[{index}] rest accepted: {0}")
    @CsvSource({"/files/*", "/*"})
    void restAccepted(String raw) {
        assertThat(RoutePattern.parse(raw).segments()).last().isEqualTo(new RoutePattern.Rest());
    }

    @ParameterizedTest(name = "[{index}] rest rejected: {0}")
    @CsvSource({"/*/a", "/a*", "/**"})
    void restRejected(String raw) {
        assertRejected(raw);
    }

    @ParameterizedTest(name = "[{index}] empty segment rejected: {0}")
    @CsvSource({"//a", "/a//b", "/a/"})
    void emptySegmentRejected(String raw) {
        assertRejected(raw);
    }

    @Test
    void lengthOneThousandTwentyFourAccepted() {
        String raw = "/" + "a".repeat(1023);
        assertThat(raw).hasSize(1024);
        assertThat(RoutePattern.parse(raw).value()).isEqualTo(raw);
    }

    @Test
    void lengthOneThousandTwentyFiveRejected() {
        assertRejected("/" + "a".repeat(1024));
    }

    private static void assertRejected(String raw) {
        assertThatThrownBy(() -> RoutePattern.parse(raw))
                .isInstanceOf(UseCaseException.class)
                .extracting(t -> ((UseCaseException) t).error())
                .satisfies(err -> {
                    assertThat(err).isInstanceOf(UseCaseError.Validation.class);
                    assertThat(err.code()).isEqualTo("ROUTE_PATTERN_INVALID");
                });
    }

    // ── #check (CONVENTIONS.md §8: no exceptions for control flow) ──────────

    @Test
    void checkIsOkForAValidPattern() {
        assertThat(RoutePattern.check("/invoices/{id}"))
                .as("mutant: check must accept exactly what parse accepts")
                .isEqualTo(Result.ok(RoutePattern.parse("/invoices/{id}")));
    }

    @ParameterizedTest(name = "[{index}] check rejects: {0}")
    @CsvSource({"invoices", "''", "'/in voices'", "/{}", "/{a}/{a}", "/*/a"})
    void checkIsErrForInvalidPatternWithTheParseMessage(String raw) {
        assertThat(RoutePattern.check(raw))
                .as("mutant: check's Err must carry parse's exact code/message, never a different or missing one")
                .isEqualTo(Result.err(new RoutePattern.Invalid("ROUTE_PATTERN_INVALID", RoutePattern.INVALID_MESSAGE)));
    }

    @Test
    void checkIsErrForNullWithTheParseMessage() {
        assertThat(RoutePattern.check(null))
                .isEqualTo(Result.err(new RoutePattern.Invalid("ROUTE_PATTERN_INVALID", RoutePattern.INVALID_MESSAGE)));
    }

    // ── match — §5.2's rules, and §8 M8 ──────────────────────────────────────

    @Test
    void literalMatchesByteForByteCaseSensitive() {
        RoutePattern pattern = RoutePattern.parse("/Invoices");
        assertThat(pattern.match("/Invoices")).isPresent();
        assertThat(pattern.match("/invoices")).as("case-sensitive").isEmpty();
    }

    @Test
    void paramCapturesOneSegmentDecoded() {
        RoutePattern pattern = RoutePattern.parse("/invoices/{id}");
        assertThat(pattern.match("/invoices/abc-123")).contains(Map.of("id", "abc-123"));
    }

    @Test
    void paramValueIsPercentDecodedUtf8() {
        RoutePattern pattern = RoutePattern.parse("/search/{term}");
        // "café" percent-encoded UTF-8
        assertThat(pattern.match("/search/caf%C3%A9")).contains(Map.of("term", "café"));
    }

    @Test
    void plusIsNotDecodedAsSpace() {
        RoutePattern pattern = RoutePattern.parse("/search/{term}");
        assertThat(pattern.match("/search/a+b")).contains(Map.of("term", "a+b"));
    }

    @Test
    void malformedEscapeIsNoMatch() {
        RoutePattern pattern = RoutePattern.parse("/search/{term}");
        assertThat(pattern.match("/search/a%2")).isEmpty();
        assertThat(pattern.match("/search/a%zz")).isEmpty();
    }

    @Test
    void invalidUtf8SequenceIsNoMatch() {
        RoutePattern pattern = RoutePattern.parse("/search/{term}");
        // %FF is not a valid UTF-8 lead byte on its own
        assertThat(pattern.match("/search/%FF")).isEmpty();
    }

    @Test
    void restMatchesZeroOrMoreRemainingSegments() {
        RoutePattern pattern = RoutePattern.parse("/files/*");
        assertThat(pattern.match("/files")).as("§8 M8: Rest matches zero segments").isPresent();
        assertThat(pattern.match("/files/")).isPresent();
        assertThat(pattern.match("/files/a/b")).isPresent();
    }

    @Test
    void withoutRestSegmentCountsMustBeEqual() {
        RoutePattern pattern = RoutePattern.parse("/a");
        assertThat(pattern.match("/a")).isPresent();
        assertThat(pattern.match("/a/")).as("§8 M8: trailing slash does not match /a").isEmpty();
        assertThat(pattern.match("/a/b")).isEmpty();
    }

    @Test
    void matchNullIsEmpty() {
        assertThat(RoutePattern.parse("/a").match(null)).isEmpty();
    }

    @Test
    void literalsAreComparedRawNeverDecoded() {
        // §8 M8: a raw request segment that happens to be a percent-escape
        // must NOT be decoded before comparing against a literal segment —
        // "a%2Db" must not match the literal "a-b" by decoding to it.
        RoutePattern pattern = RoutePattern.parse("/a-b");
        assertThat(pattern.match("/a%2Db")).as("literal comparison is raw, not decoded").isEmpty();
        assertThat(pattern.match("/a-b")).isPresent();
    }

    // ── §5.3 precedence and ambiguity ────────────────────────────────────────

    @Test
    void literalBeatsParam() {
        RoutePattern a = RoutePattern.parse("/a/b");
        RoutePattern b = RoutePattern.parse("/a/{x}");
        assertThat(winner(a, b, "/a/b")).isEqualTo(a);
    }

    @Test
    void paramBeatsRest() {
        RoutePattern a = RoutePattern.parse("/a/{x}");
        RoutePattern b = RoutePattern.parse("/a/*");
        assertThat(winner(a, b, "/a/b")).isEqualTo(a);
    }

    @Test
    void leftmostDecides() {
        RoutePattern a = RoutePattern.parse("/a/b/{y}");
        RoutePattern b = RoutePattern.parse("/a/{x}/c");
        assertThat(winner(a, b, "/a/b/c")).isEqualTo(a);
    }

    @Test
    void noRestBeatsRestAtATie() {
        RoutePattern a = RoutePattern.parse("/files");
        RoutePattern b = RoutePattern.parse("/files/*");
        assertThat(winner(a, b, "/files")).isEqualTo(a);
    }

    @Test
    void ambiguousParamNamesIrrelevant() {
        RoutePattern a = RoutePattern.parse("/a/{x}");
        RoutePattern b = RoutePattern.parse("/a/{y}");
        assertThat(a.ambiguousWith(b)).isTrue();
        assertThat(b.ambiguousWith(a)).isTrue();
    }

    @Test
    void ambiguousIdenticalPatternsCompareEqual() {
        RoutePattern a = RoutePattern.parse("/a/*");
        RoutePattern b = RoutePattern.parse("/a/*");
        assertThat(a.ambiguousWith(b)).isTrue();
        assertThat(a.compareTo(b)).isZero();
    }

    @Test
    void ambiguousButDifferentStringsDoNotCompareEqual() {
        RoutePattern a = RoutePattern.parse("/a/{x}");
        RoutePattern b = RoutePattern.parse("/a/{y}");
        assertThat(a.compareTo(b)).as("ambiguous but not identical strings").isNotZero();
    }

    @Test
    void notAmbiguousDifferentLiteral() {
        RoutePattern a = RoutePattern.parse("/a/{x}");
        RoutePattern b = RoutePattern.parse("/a/b");
        assertThat(a.ambiguousWith(b)).isFalse();
    }

    @Test
    void notAmbiguousDifferentLength() {
        RoutePattern a = RoutePattern.parse("/a/{x}");
        RoutePattern b = RoutePattern.parse("/a/{x}/b");
        assertThat(a.ambiguousWith(b)).isFalse();
    }

    @Test
    void ambiguousWithRequiresSameLiteralText() {
        // §8 M7: ambiguousWith must NOT be satisfied merely because both
        // segments are literals (or both are params by coincidence of rank) —
        // literal positions require the SAME text.
        RoutePattern a = RoutePattern.parse("/a/b");
        RoutePattern b = RoutePattern.parse("/a/c");
        assertThat(a.ambiguousWith(b)).isFalse();
    }

    private static RoutePattern winner(RoutePattern a, RoutePattern b, String path) {
        Optional<RoutePattern.Match> match = RoutePattern.firstMatch(List.of(a, b), path);
        assertThat(match).isPresent();
        return match.get().pattern();
    }

    // ── firstMatch ────────────────────────────────────────────────────────────

    @Test
    void firstMatchSortsBySpecificityAndReturnsParams() {
        RoutePattern literal = RoutePattern.parse("/a/b");
        RoutePattern param = RoutePattern.parse("/a/{x}");
        RoutePattern rest = RoutePattern.parse("/a/*");
        // Deliberately out of order — firstMatch must sort, not rely on input order.
        List<RoutePattern> patterns = List.of(rest, param, literal);

        Optional<RoutePattern.Match> onLiteral = RoutePattern.firstMatch(patterns, "/a/b");
        assertThat(onLiteral).isPresent();
        assertThat(onLiteral.get().pattern()).isEqualTo(literal);
        assertThat(onLiteral.get().params()).isEmpty();

        Optional<RoutePattern.Match> onParam = RoutePattern.firstMatch(patterns, "/a/c");
        assertThat(onParam).isPresent();
        assertThat(onParam.get().pattern()).isEqualTo(param);
        assertThat(onParam.get().params()).containsExactly(Map.entry("x", "c"));
    }

    @Test
    void firstMatchEmptyWhenNothingMatches() {
        RoutePattern pattern = RoutePattern.parse("/a/b");
        assertThat(RoutePattern.firstMatch(List.of(pattern), "/x/y")).isEmpty();
    }
}
