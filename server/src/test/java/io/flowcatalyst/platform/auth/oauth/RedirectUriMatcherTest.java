package io.flowcatalyst.platform.auth.oauth;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// auth-core §7.3.2 — the pinned table.
class RedirectUriMatcherTest {

    @ParameterizedTest(name = "{0} vs [{1}] → {2}")
    @CsvSource(delimiter = '|', value = {
            // exact
            "https://app.x.com/cb | https://app.x.com/cb | true",
            "https://app.x.com/foo | https://app.x.com/cb | false",
            // userinfo / degenerate
            "https://app.x.com@evil.com/x | https://*.x.com | false",
            "https://* | https://*.x.com | false",
            // scheme + port
            "http://app.x.com/cb | https://*.x.com | false",
            "https://app.x.com:8443/cb | https://*.x.com | false",
            "https://app.x.com:8443/cb | https://*.x.com:8443 | true",
            // host wildcards
            "https://app.x.com/cb | https://*.x.com | true",
            "https://qa-acme.x.com/cb | https://qa-*.x.com | true",
            "https://acme-qa.x.com/cb | https://*-qa.x.com | true",
            "https://x.com/cb | https://*.x.com | false",
            "https://a.b.x.com/cb | https://*.x.com | false",
            "https://x.com.evil.com/cb | https://*.x.com | false",
            "https://qa-.x.com/cb | https://qa-*.x.com | false",
            "https://app.com/cb | https://*.com | false",
            "https://evilx.com/cb | https://*.x.com | false",
            "https://APP.X.COM/cb | https://*.x.com | true",
            // paths
            "https://app.x.com/auth/done | https://*.x.com/auth/* | true",
            "https://app.x.com/admin | https://*.x.com/auth/* | false",
            "https://app.x.com/other | https://*.x.com/cb | false",
            "https://app.x.com/cb?q=1#f | https://*.x.com/cb | true",
            "https://app.x.com/anything | https://*.x.com/ | true",
    })
    void table(String uri, String pattern, boolean expected) {
        assertThat(RedirectUriMatcher.matches(uri, List.of(pattern))).isEqualTo(expected);
    }

    @ParameterizedTest(name = "label {0} vs {1} → {2}")
    @CsvSource(delimiter = '|', value = {
            "qa-foo | qa-* | true", "qa- | qa-* | false", "foo-qa | *-qa | true", "-qa | *-qa | false",
            "a-b-c | a-*-c | true", "a--c | a-*-c | false", "abc | * | true", "'' | * | false", "app | app | true",
    })
    void labels(String label, String pattern, boolean expected) {
        assertThat(RedirectUriMatcher.labelMatches(label == null ? "" : label, pattern)).isEqualTo(expected);
    }
}
