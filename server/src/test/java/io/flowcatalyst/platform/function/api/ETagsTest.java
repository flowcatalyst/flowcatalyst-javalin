package io.flowcatalyst.platform.function.api;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/// `ETags.matches` (spec `function-api.md` §6.1's conditional-GET rule): each
/// clause of "exact, weak `W/"…"`, a comma list, `*`, whitespace" pinned
/// independently, plus the no-match and null-header rows a mutant that
/// returns `true` unconditionally would still pass without.
class ETagsTest {

    private static final String ETAG = "\"abc123\"";

    @ParameterizedTest(name = "[{0}] header={1} -> {2}")
    @CsvSource(delimiter = '|', textBlock = """
            exact match                        | "abc123"                      | true
            weak validator strips W/           | W/"abc123"                    | true
            list, second entry matches         | "xyz999", "abc123"             | true
            list, weak entry matches           | "xyz999", W/"abc123"           | true
            star matches anything              | *                             | true
            surrounding whitespace tolerated   |   "abc123"                    | true
            no entry matches                   | "xyz999"                      | false
            similar but not equal (case)       | "ABC123"                      | false
            """)
    void matches(String label, String header, boolean expected) {
        assertThat(ETags.matches(header, ETAG)).as(label).isEqualTo(expected);
    }

    @org.junit.jupiter.api.Test
    void nullHeaderNeverMatches() {
        assertThat(ETags.matches(null, ETAG)).as("mutant: treat absent If-None-Match as a match").isFalse();
    }

    @org.junit.jupiter.api.Test
    void blankHeaderNeverMatches() {
        assertThat(ETags.matches("   ", ETAG)).as("mutant: treat a blank header as a match").isFalse();
    }
}
