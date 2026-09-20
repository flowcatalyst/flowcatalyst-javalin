package io.flowcatalyst.fnhost.context;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// X8 unit half (`docs/spec/function-context.md` §2, `CONVENTIONS.md` §8 "a
/// matcher is a pinned table"): [HttpAllowlist#allows] and [HttpAllowlist#isLoopback],
/// exhaustively, grouped by rule.
class HttpAllowlistTest {

    @ParameterizedTest(name = "[{0}] allow={1} host={2} -> {3}")
    @CsvSource({
            // rule,            allowlist (';'-joined),      host,                    expected
            "exact match,       api.example.com,              api.example.com,        true",
            "exact mismatch,    api.example.com,              other.example.com,      false",
            "exact case-insens, API.example.com,               api.EXAMPLE.com,        true",
            "suffix subdomain,  *.example.com,                a.example.com,          true",
            "suffix deep,       *.example.com,                a.b.example.com,        true",
            "suffix apex reject,*.example.com,                example.com,            false",
            "suffix evil reject,*.suffix.test,                evilsuffix.test,        false",
            "suffix lookalike,  *.suffix.test,                notsuffixatall.test,    false",
            "suffix case-insens,*.EXAMPLE.com,                a.example.COM,          true",
            "no entries,        '',                            anything.test,          false",
            "not on list,       api.example.com,              api.other.com,          false",
    })
    void x8_allowlistMatcherIsAPinnedTable(String rule, String allowlist, String host, boolean expected) {
        List<String> entries = allowlist.isBlank() ? List.of() : List.of(allowlist.split(";"));
        HttpAllowlist matcher = new HttpAllowlist(entries);
        assertThat(matcher.allows(host)).as(rule + ": mutant: endsWith without the dot").isEqualTo(expected);
    }

    @ParameterizedTest(name = "[{0}] host={1} -> {2}")
    @CsvSource({
            "localhost literal, localhost,     true",
            "ipv4 loopback,     127.0.0.1,     true",
            "ipv6 loopback,     ::1,           true",
            "ordinary host,     example.com,   false",
    })
    void x8_loopbackIsAPinnedTable(String rule, String host, boolean expected) {
        assertThat(HttpAllowlist.isLoopback(host)).as(rule).isEqualTo(expected);
    }

    @org.junit.jupiter.api.Test
    void x8_nullAndBlankHostAreNeverAllowedOrLoopback() {
        HttpAllowlist matcher = new HttpAllowlist(List.of("*.example.com", "example.com"));
        assertThat(matcher.allows(null)).isFalse();
        assertThat(matcher.allows("")).isFalse();
        assertThat(matcher.allows("   ")).isFalse();
        assertThat(HttpAllowlist.isLoopback(null)).isFalse();
    }
}
