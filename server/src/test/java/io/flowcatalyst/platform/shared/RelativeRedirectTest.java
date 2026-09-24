package io.flowcatalyst.platform.shared;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/// `docs/spec/security-fixes-2026-09-24.md` S2.6: the one same-origin
/// redirect rule, as a pinned table. Control characters are written as
/// `{TAB}` / `{LF}` / `{CR}` / `{NUL}` / `{NBSP}` and substituted, so each row
/// states what reaches the predicate after the server has decoded the query.
class RelativeRedirectTest {

    @ParameterizedTest(name = "{0}: {1}")
    @CsvSource(delimiter = '|', textBlock = """
            plain path          | /dashboard
            nested path         | /a/b/c
            path with query     | /a/b?x=1&y=2
            authorize round trip| /oauth/authorize?client_id=acme&redirect_uri=https%3A%2F%2Fapp.acme.test%2Fcb&state=s1
            encoded query value | /search?q=%2F%2Fnot-a-host
            fragment            | /page#section
            root                | /
            """)
    void accepts(String rule, String candidate) {
        assertThat(RelativeRedirect.isSafe(candidate)).as(rule).isTrue();
    }

    @ParameterizedTest(name = "{0}: {1}")
    @CsvSource(delimiter = '|', textBlock = """
            no leading slash         | evil.com
            no leading slash         | dashboard/x
            scheme                   | https://evil.com
            scheme                   | javascript:alert(1)
            authority                | //evil.com
            authority                | ///evil.com
            backslash                | /\\evil.com
            backslash                | /a\\b
            backslash decoded        | /%5Cevil.com
            control char raw         | /{TAB}/evil.com
            control char raw         | /{LF}/evil.com
            control char raw         | /{CR}/evil.com
            control char raw         | /{NUL}/evil.com
            control char encoded     | /%09/evil.com
            control char encoded     | /%0a/evil.com
            whitespace raw           | / /evil.com
            whitespace raw           | /{NBSP}/evil.com
            whitespace encoded       | /%20/evil.com
            decoded authority        | /%2F/evil.com
            decoded authority        | /%2f%2fevil.com
            unparseable              | /%zz
            unparseable              | /a{b
            """)
    void refuses(String rule, String candidate) {
        assertThat(RelativeRedirect.isSafe(expand(candidate))).as(rule).isFalse();
    }

    @Test
    void nullAndEmptyAreRefused() {
        assertThat(RelativeRedirect.isSafe(null)).isFalse();
        assertThat(RelativeRedirect.isSafe("")).isFalse();
    }

    private static String expand(String s) {
        return s.replace("{TAB}", "\t").replace("{LF}", "\n").replace("{CR}", "\r").replace("{NUL}", "\0")
                .replace("{NBSP}", " ");
    }
}
