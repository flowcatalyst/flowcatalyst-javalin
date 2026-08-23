package io.flowcatalyst.platform.publicapi;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/// The one reader of the stored login-theme document (spec §5 table): what
/// is echoed, what collapses to "nothing configured", and that no partial
/// parse ever escapes.
class LoginThemeTest {

    @ParameterizedTest(name = "[{index}] blank \"{0}\" reads as nothing configured")
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\n\t"})
    void blankStoredValueIsEmpty(String stored) {
        assertThat(LoginTheme.parse(stored)).isEmpty();
    }

    @ParameterizedTest(name = "[{index}] {1}: {0}")
    @CsvSource(delimiter = '|', value = {
            "null              | JSON null literal",
            "{not json         | malformed JSON",
            "[1]               | array, not an object",
            "\"x\"             | string, not an object",
            "42                | number, not an object",
    })
    void nonObjectDocumentsAreEmpty(String stored, String rule) {
        assertThat(LoginTheme.parse(stored)).as(rule).isEmpty();
    }

    @Test
    void emptyObjectIsTheEmptyTheme() {
        assertThat(LoginTheme.parse("{}")).contains(LoginTheme.EMPTY);
    }

    @Test
    void everyFieldIsReadVerbatimAndUnknownKeysAreIgnored() {
        var theme = LoginTheme.parse("""
                {"brandName":" Acme ","brandSubtitle":"Ops","logoUrl":"https://cdn/logo.png","logoSvg":"<svg/>",
                 "logoHeight":48,"primaryColor":"red","accentColor":"#222","backgroundColor":"#333",
                 "backgroundGradient":"linear-gradient(#1,#2)","footerText":"Acme Inc","customCss":".a{}","unknown":1}
                """).orElseThrow();

        assertThat(theme.brandName()).as("not trimmed — stored verbatim").isEqualTo(" Acme ");
        assertThat(theme.brandSubtitle()).isEqualTo("Ops");
        assertThat(theme.logoUrl()).isEqualTo("https://cdn/logo.png");
        assertThat(theme.logoSvg()).isEqualTo("<svg/>");
        assertThat(theme.logoHeight()).isEqualTo(48);
        assertThat(theme.primaryColor()).as("no colour validation on the stored shape").isEqualTo("red");
        assertThat(theme.accentColor()).isEqualTo("#222");
        assertThat(theme.backgroundColor()).isEqualTo("#333");
        assertThat(theme.backgroundGradient()).isEqualTo("linear-gradient(#1,#2)");
        assertThat(theme.footerText()).isEqualTo("Acme Inc");
        assertThat(theme.customCss()).isEqualTo(".a{}");
    }

    @Test
    void logoHeightReadsLenientlyFromAJsonString() {
        // spec §5 last row / open question 4: Jackson scalar coercion, kept until ruled on.
        assertThat(LoginTheme.parse("{\"logoHeight\":\"48\"}").orElseThrow().logoHeight()).isEqualTo(48);
    }

    @Test
    void partialObjectLeavesTheRestAbsentAndKeepsEmptyStrings() {
        var theme = LoginTheme.parse("{\"brandName\":\"\",\"logoHeight\":40}").orElseThrow();

        assertThat(theme.brandName()).as("a stored empty string is a value, not absence").isEmpty();
        assertThat(theme.logoHeight()).isEqualTo(40);
        assertThat(theme.primaryColor()).isNull();
        assertThat(theme.customCss()).isNull();
    }
}
