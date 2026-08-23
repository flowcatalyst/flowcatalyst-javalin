package io.flowcatalyst.platform.publicapi;

import io.flowcatalyst.platform.publicapi.EmailTheme.EmailContent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static io.flowcatalyst.platform.publicapi.EmailTheme.DEFAULT_ACCENT_COLOR;
import static io.flowcatalyst.platform.publicapi.EmailTheme.DEFAULT_PRIMARY_COLOR;
import static org.assertj.core.api.Assertions.assertThat;

/// The email theme without a DB (spec §6): layering over the defaults, the
/// colour gate as a pinned accept / reject table, the logo source rules and
/// the rendered document's escaping and structure.
class EmailThemeTest {

    private static final EmailTheme PLAIN = EmailTheme.defaults("Inhance");

    private static LoginTheme stored(String json) {
        return LoginTheme.parse(json).orElseThrow();
    }

    // ── Layering ───────────────────────────────────────────────────────────

    @Test
    void emptyThemeIsAllDefaultsWithThePlatformName() {
        var t = EmailTheme.of("Flowcatalyst", LoginTheme.EMPTY);

        assertThat(t).isEqualTo(EmailTheme.defaults("Flowcatalyst"));
        assertThat(t.logoUrl()).isNull();
        assertThat(t.logoSvg()).isNull();
        assertThat(t.footerText()).isNull();
    }

    @Test
    void storedFieldsOverrideTheDefaultsTrimmed() {
        var t = EmailTheme.of("Flowcatalyst", stored("""
                {"brandName":" Acme ","primaryColor":" #111111 ","accentColor":"#222222",
                 "logoUrl":" https://cdn.example.com/logo.png ","logoSvg":" <svg/> ","footerText":" Acme Inc "}
                """));

        assertThat(t.brandName()).isEqualTo("Acme");
        assertThat(t.primaryColor()).isEqualTo("#111111");
        assertThat(t.accentColor()).isEqualTo("#222222");
        assertThat(t.logoUrl()).isEqualTo("https://cdn.example.com/logo.png");
        assertThat(t.logoSvg()).isEqualTo("<svg/>");
        assertThat(t.footerText()).isEqualTo("Acme Inc");
    }

    @Test
    void blankStoredStringsReadAsAbsent() {
        var t = EmailTheme.of("Flowcatalyst", stored("{\"brandName\":\"  \",\"logoUrl\":\"\",\"logoSvg\":\" \",\"footerText\":\"\"}"));

        assertThat(t.brandName()).as("blank brand falls back to the platform name").isEqualTo("Flowcatalyst");
        assertThat(t.logoUrl()).isNull();
        assertThat(t.logoSvg()).isNull();
        assertThat(t.footerText()).isNull();
    }

    @Test
    void anUnsafeColourFallsBackPerFieldWhileOtherOverridesApply() {
        var t = EmailTheme.of("Flowcatalyst", stored("{\"primaryColor\":\"red\",\"accentColor\":\"#abc;\\\"></td><script>\",\"brandName\":\"Acme\"}"));

        assertThat(t.primaryColor()).as("keyword rejected").isEqualTo(DEFAULT_PRIMARY_COLOR);
        assertThat(t.accentColor()).as("attribute break-out rejected").isEqualTo(DEFAULT_ACCENT_COLOR);
        assertThat(t.brandName()).isEqualTo("Acme");
    }

    // ── Colour gate (spec §6 pattern table) ────────────────────────────────

    @ParameterizedTest(name = "[{index}] {1}: {0}")
    @CsvSource(delimiter = '|', value = {
            "#abc               | hex 3",
            "#102A43            | hex 6 upper-case",
            "#11223344          | hex 8",
            "rgb(9, 103, 210)   | rgb with spaces",
            "rgba(0,0,0,.5)     | rgba with fraction",
            "RGB(100%,0%,0%)    | rgb upper-case with percent",
    })
    void safeColoursAreKept(String colour, String rule) {
        assertThat(EmailTheme.safeColor(colour, "fallback")).as(rule).isEqualTo(colour);
    }

    @ParameterizedTest(name = "[{index}] {1}: {0}")
    @CsvSource(delimiter = '|', value = {
            "#ab                        | hex too short",
            "#123456789                 | hex too long",
            "#ggg                       | hex non-hex digits",
            "102a43                     | hex without #",
            "rgb(9,103,210              | rgb unclosed",
            "rgb(a,b,c)                 | rgb letters",
            "hsl(1,2%,3%)               | other function",
            "red                        | keyword",
            "''                         | empty",
            "#abc;\"></td><script>      | attribute break-out",
            "url(x)                     | url()",
    })
    void unsafeColoursFallBack(String colour, String rule) {
        assertThat(EmailTheme.safeColor(colour, "fallback")).as(rule).isEqualTo("fallback");
    }

    // ── Logo source ────────────────────────────────────────────────────────

    private static String svgDataUri(String svg) {
        return "data:image/svg+xml;base64," + Base64.getEncoder().encodeToString(svg.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void hostedLogoUrlWinsOverTheSvg() {
        var t = new EmailTheme("Inhance", DEFAULT_PRIMARY_COLOR, DEFAULT_ACCENT_COLOR, "HTTPS://cdn.example.com/logo.png", "<svg/>", null);
        assertThat(t.logoSrc()).contains("HTTPS://cdn.example.com/logo.png");
    }

    @Test
    void dataImageUrlIsAcceptedAsHosted() {
        var t = new EmailTheme("Inhance", DEFAULT_PRIMARY_COLOR, DEFAULT_ACCENT_COLOR, "data:image/png;base64,AAAA", "<svg/>", null);
        assertThat(t.logoSrc()).contains("data:image/png;base64,AAAA");
    }

    @Test
    void svgBecomesABase64DataUriWhenThereIsNoHostedUrl() {
        var t = new EmailTheme("Inhance", DEFAULT_PRIMARY_COLOR, DEFAULT_ACCENT_COLOR, null, "<svg/>", null);
        assertThat(t.logoSrc()).contains(svgDataUri("<svg/>"));
    }

    @Test
    void unsafeUrlSchemeIsNeverEmittedAndFallsBackToTheSvg() {
        var t = new EmailTheme("Inhance", DEFAULT_PRIMARY_COLOR, DEFAULT_ACCENT_COLOR, "javascript:alert(1)", "<svg/>", null);
        assertThat(t.logoSrc()).contains(svgDataUri("<svg/>"));
    }

    @Test
    void noLogoMeansNoSource() {
        assertThat(PLAIN.logoSrc()).isEmpty();
        assertThat(PLAIN.logoDataUri()).isEmpty();
    }

    // ── Rendering ──────────────────────────────────────────────────────────

    @Test
    void buttonUsesTheAccentColourWithWhiteTextAndEscapesTheUrl() {
        String html = PLAIN.render(new EmailContent("Welcome to Inhance", "An account has been created for you.",
                "Set your password", "https://platform.example.com/auth/reset-password?token=abc&x=1", List.of(), null));

        assertThat(html)
                .contains("background-color:" + DEFAULT_ACCENT_COLOR)
                .contains("color:#ffffff")
                .contains("Set your password")
                .contains("Welcome to Inhance")
                .contains("https://platform.example.com/auth/reset-password?token=abc&amp;x=1")
                .contains("Or paste this link into your browser:")
                .doesNotContain("token=abc&x=1");
    }

    @Test
    void contentIsHtmlEscaped() {
        String html = PLAIN.render(new EmailContent("<script>alert(1)</script>", "Tom & \"Jerry\"", null, null, List.of("a 'quote'"), null));

        assertThat(html)
                .doesNotContain("<script>alert(1)</script>")
                .contains("&lt;script&gt;alert(1)&lt;/script&gt;")
                .contains("Tom &amp; &#34;Jerry&#34;")
                .contains("a &#39;quote&#39;");
    }

    @Test
    void bannerShowsTheBrandNameWithoutALogoAndTheImageWithOne() {
        String noLogo = PLAIN.render(new EmailContent("Hi", null, null, null, List.of(), null));
        assertThat(noLogo).doesNotContain("<img").contains(">Inhance<");

        String withLogo = new EmailTheme("Inhance", DEFAULT_PRIMARY_COLOR, DEFAULT_ACCENT_COLOR, "https://cdn.example.com/logo.png", null, null)
                .render(new EmailContent("Hi", null, null, null, List.of(), null));
        assertThat(withLogo).contains("<img src=\"https://cdn.example.com/logo.png\"").contains("alt=\"Inhance\"");
    }

    @Test
    void buttonNeedsBothLabelAndUrlAndBlankParagraphsAreSkipped() {
        String html = PLAIN.render(new EmailContent(null, null, "Go", null, List.of("  ", "Keep me"), "  "));

        assertThat(html)
                .doesNotContain("<a href=")
                .doesNotContain("<h1")
                .contains("Keep me")
                .as("blank footer gets the automated-message note")
                .contains("This is an automated message from Inhance. Please do not reply to this email.");
    }

    @Test
    void configuredFooterReplacesTheAutomatedNote() {
        String html = PLAIN.render(new EmailContent(null, null, null, null, List.of(), "Acme Inc, 1 Main St"));
        assertThat(html).contains("Acme Inc, 1 Main St").doesNotContain("automated message");
    }
}
