package io.flowcatalyst.platform.publicapi;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/// The resolved branding for server-rendered transactional email (spec
/// `docs/spec/publicapi.md` §6): the stored [LoginTheme] layered over the
/// defaults, colours confined to forms that are safe inside an inline
/// `style` attribute, so the senders stay unconditional. Absent optionals
/// are `null` (never `""`).
///
/// @param brandName    never blank — the stored brand name or the platform name
/// @param primaryColor banner / heading colour, always a safe colour
/// @param accentColor  button / link colour, always a safe colour
/// @param logoUrl      hosted logo URL, `null` when unset
/// @param logoSvg      inline SVG markup, `null` when unset
/// @param footerText   configured footer, `null` when unset
public record EmailTheme(
        String brandName,
        String primaryColor,
        String accentColor,
        String logoUrl,
        String logoSvg,
        String footerText) {

    /// Defaults mirror the SPA's `loginTheme.ts` so emails match the login page.
    public static final String DEFAULT_PRIMARY_COLOR = "#102a43";
    public static final String DEFAULT_ACCENT_COLOR = "#0967d2";

    /// The only colour forms allowed into an inline style attribute (spec §6
    /// table): hex `#rgb`..`#rrggbbaa`, or `rgb()`/`rgba()` over digits,
    /// `. , %` and whitespace. Keywords, `url(`, and anything that could
    /// close the attribute fall back to the default.
    static final Pattern SAFE_COLOR = Pattern.compile("^#[0-9a-f]{3,8}$|^rgba?\\([0-9.,%\\s]+\\)$", Pattern.CASE_INSENSITIVE);

    public EmailTheme {
        Objects.requireNonNull(brandName, "brandName");
        Objects.requireNonNull(primaryColor, "primaryColor");
        Objects.requireNonNull(accentColor, "accentColor");
    }

    /// Nothing configured beyond the platform name.
    public static EmailTheme defaults(String brandName) {
        return new EmailTheme(brandName, DEFAULT_PRIMARY_COLOR, DEFAULT_ACCENT_COLOR, null, null, null);
    }

    /// The stored theme layered over the defaults, each field by its own rule
    /// (spec §6 layering table); `platformName` is the brand when the theme
    /// carries none.
    public static EmailTheme of(String platformName, LoginTheme theme) {
        Objects.requireNonNull(platformName, "platformName");
        Objects.requireNonNull(theme, "theme");
        String brand = Optional.ofNullable(blankToNull(theme.brandName())).orElse(platformName);
        return new EmailTheme(
                brand,
                theme.primaryColor() == null ? DEFAULT_PRIMARY_COLOR : safeColor(theme.primaryColor(), DEFAULT_PRIMARY_COLOR),
                theme.accentColor() == null ? DEFAULT_ACCENT_COLOR : safeColor(theme.accentColor(), DEFAULT_ACCENT_COLOR),
                blankToNull(theme.logoUrl()),
                blankToNull(theme.logoSvg()),
                blankToNull(theme.footerText()));
    }

    /// The banner `<img src>` (spec §6 logo table): a hosted `http(s)` /
    /// `data:image/` URL wins (PNG/JPG render where mail clients block inline
    /// SVG); otherwise the SVG as a data URI; empty when there is no logo.
    public Optional<String> logoSrc() {
        if (logoUrl != null) {
            String lower = logoUrl.toLowerCase(Locale.ROOT);
            if (lower.startsWith("https://") || lower.startsWith("http://") || lower.startsWith("data:image/")) {
                return Optional.of(logoUrl);
            }
        }
        return logoDataUri();
    }

    /// The SVG logo as a `data:image/svg+xml;base64,…` URI; empty when unset.
    public Optional<String> logoDataUri() {
        if (logoSvg == null) return Optional.empty();
        return Optional.of("data:image/svg+xml;base64," + Base64.getEncoder().encodeToString(logoSvg.getBytes(StandardCharsets.UTF_8)));
    }

    /// The body of a branded email; `null` parts are omitted, the button
    /// needs both its label and URL, `afterButton` paragraphs that are blank
    /// are skipped, a `null`/blank footer gets the automated-message note.
    public record EmailContent(
            String heading,
            String intro,
            String buttonLabel,
            String buttonUrl,
            List<String> afterButton,
            String footer) {

        public EmailContent {
            afterButton = afterButton == null ? List.of() : List.copyOf(afterButton);
        }
    }

    /// A self-contained, table-based, inline-styled HTML document (spec §6
    /// render contract). All text and the URL are HTML-escaped; colours are
    /// interpolated raw because [#SAFE_COLOR] already confined them.
    public String render(EmailContent c) {
        Objects.requireNonNull(c, "content");
        String header = logoSrc()
                .map(src -> "<img src=\"" + escape(src) + "\" alt=\"" + escape(brandName)
                        + "\" height=\"40\" style=\"height:40px;max-height:40px;display:block;margin:0 auto;border:0;outline:none;text-decoration:none;\" />")
                .orElse("<span style=\"color:#ffffff;font-size:20px;font-weight:700;\">" + escape(brandName) + "</span>");

        var body = new StringBuilder();
        if (present(c.heading())) {
            body.append("<h1 style=\"margin:0 0 16px;font-size:22px;font-weight:700;color:").append(primaryColor).append(";\">")
                    .append(escape(c.heading())).append("</h1>");
        }
        if (present(c.intro())) {
            body.append("<p style=\"margin:0 0 24px;font-size:15px;line-height:1.6;color:#33475b;\">")
                    .append(escape(c.intro())).append("</p>");
        }
        if (present(c.buttonLabel()) && present(c.buttonUrl())) {
            String url = escape(c.buttonUrl());
            body.append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" style=\"margin:0 0 24px;\"><tr>")
                    .append("<td style=\"border-radius:6px;background-color:").append(accentColor).append(";\">")
                    .append("<a href=\"").append(url).append("\" style=\"display:inline-block;padding:12px 28px;font-size:15px;")
                    .append("font-weight:600;color:#ffffff;text-decoration:none;border-radius:6px;\">")
                    .append(escape(c.buttonLabel())).append("</a></td></tr></table>")
                    .append("<p style=\"margin:0 0 24px;font-size:13px;line-height:1.6;color:#62748b;\">")
                    .append("Or paste this link into your browser:<br>")
                    .append("<a href=\"").append(url).append("\" style=\"color:").append(accentColor).append(";word-break:break-all;\">")
                    .append(url).append("</a></p>");
        }
        for (String p : c.afterButton()) {
            if (p == null || p.isBlank()) continue;
            body.append("<p style=\"margin:0 0 16px;font-size:14px;line-height:1.6;color:#33475b;\">").append(escape(p)).append("</p>");
        }

        String footer = blankToNull(c.footer());
        if (footer == null) footer = "This is an automated message from " + brandName + ". Please do not reply to this email.";

        return "<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"></head>"
                + "<body style=\"margin:0;padding:0;background-color:#f4f6f8;\">"
                + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" "
                + "style=\"background-color:#f4f6f8;padding:24px 0;\"><tr><td align=\"center\">"
                + "<table role=\"presentation\" width=\"600\" cellpadding=\"0\" cellspacing=\"0\" "
                + "style=\"width:600px;max-width:600px;background-color:#ffffff;border-radius:10px;"
                + "overflow:hidden;border:1px solid #e3e8ee;\">"
                + "<tr><td style=\"background-color:" + primaryColor + ";padding:24px 32px;text-align:center;\">"
                + header + "</td></tr>"
                + "<tr><td style=\"padding:32px;font-family:Arial,Helvetica,sans-serif;\">" + body + "</td></tr>"
                + "<tr><td style=\"padding:20px 32px;background-color:#f4f6f8;border-top:1px solid #e3e8ee;"
                + "font-family:Arial,Helvetica,sans-serif;font-size:12px;line-height:1.5;color:#8a94a6;text-align:center;\">"
                + escape(footer) + "</td></tr>"
                + "</table></td></tr></table></body></html>";
    }

    /// `value` trimmed when it matches [#SAFE_COLOR], else `fallback`.
    static String safeColor(String value, String fallback) {
        String v = value.strip();
        return SAFE_COLOR.matcher(v).matches() ? v : fallback;
    }

    /// Trimmed, with blank collapsed to `null` — the JVM-side absence.
    private static String blankToNull(String s) {
        if (s == null) return null;
        String t = s.strip();
        return t.isEmpty() ? null : t;
    }

    private static boolean present(String s) {
        return s != null && !s.isEmpty();
    }

    /// The five characters that matter inside element text and attribute values.
    private static String escape(String s) {
        var out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&#34;");
                case '\'' -> out.append("&#39;");
                default -> out.append(ch);
            }
        }
        return out.toString();
    }
}
