package io.flowcatalyst.platform.cors;

import io.flowcatalyst.sdk.usecase.UseCaseError;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The aggregate's pure rules (spec §1, §4): the origin format and the
/// factory — no database involved. There is no state machine (spec §2).
class CorsOriginTest {

    // ── Origin format (spec §4 — the pinned table the CORS filter relies on) ──

    @ParameterizedTest(name = "[{0}] \"{1}\" → \"{2}\"")
    @CsvSource({
            // scheme: http or https, lower-case
            "scheme,   https://example.com,              https://example.com",
            "scheme,   http://localhost,                 http://localhost",
            // host: ASCII letters (case preserved), digits, '.', '-'
            "host,     https://Example.COM,              https://Example.COM",
            "host,     https://a,                        https://a",
            "host,     https://1,                        https://1",
            "host,     https://10.0.0.1,                 https://10.0.0.1",
            "host,     https://a-b.c-d.example.com,      https://a-b.c-d.example.com",
            "host,     https://example..com,             https://example..com",
            // wildcard: '*' is an ordinary host character, anywhere
            "wildcard, https://*.example.com,            https://*.example.com",
            "wildcard, https://example.*,                https://example.*",
            "wildcard, https://ex*ample.com,             https://ex*ample.com",
            "wildcard, https://*,                        https://*",
            // port: ':' + ASCII digits, no range check
            "port,     http://localhost:3000,            http://localhost:3000",
            "port,     https://*.example.com:8443,       https://*.example.com:8443",
            "port,     https://example.com:0,            https://example.com:0",
            "port,     https://example.com:65536,        https://example.com:65536",
            // trim: surrounding whitespace is dropped, the rest kept verbatim
            "trim,     '  https://app.example.com  ',    https://app.example.com",
            "trim,     'https://app.example.com\t',      https://app.example.com"})
    void originAcceptsSchemeHostPortAndTrims(String rule, String raw, String expected) {
        assertThat(Origin.parse(raw)).as(rule).isEqualTo(new Origin(expected));
    }

    @ParameterizedTest(name = "[{0}] \"{1}\" is rejected")
    @CsvSource({
            // scheme
            "scheme,   example.com",
            "scheme,   ftp://example.com",
            "scheme,   HTTPS://EXAMPLE.COM",
            "scheme,   Https://example.com",
            "scheme,   https:/example.com",
            "scheme,   https://",
            // host
            "host,     https://exa mple.com",
            "host,     https://-example.com",
            "host,     https://example.com-",
            "host,     https://.example.com",
            "host,     https://example.com.",
            "host,     https://*.",
            "host,     https://a_b.example.com",
            "host,     https://[::1]",
            "host,     https://user@example.com",
            "host,     https://user:pw@example.com",
            // port
            "port,     https://example.com:",
            "port,     https://example.com:abc",
            "port,     https://example.com:3000:4000",
            "port,     https://example.com:٣",
            // nothing after host[:port]
            "suffix,   https://example.com/",
            "suffix,   https://example.com:3000/",
            "suffix,   https://example.com/path",
            "suffix,   https://example.com?x=1",
            "suffix,   https://example.com#frag"})
    void originRejectsAnythingButSchemeHostPort(String rule, String raw) {
        assertUseCaseError(() -> Origin.parse(raw), UseCaseError.Validation.class, "INVALID_ORIGIN_FORMAT");
        assertThatThrownBy(() -> Origin.parse(raw)).as(rule).hasMessageContaining(Origin.FORMAT_MESSAGE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\t\n"})
    void originRejectsBlank(String raw) {
        assertUseCaseError(() -> Origin.parse(raw), UseCaseError.Validation.class, "ORIGIN_REQUIRED");
    }

    @Test
    void originRejectsNull() {
        assertUseCaseError(() -> Origin.parse(null), UseCaseError.Validation.class, "ORIGIN_REQUIRED");
    }

    // ── Factory ────────────────────────────────────────────────────────────

    @Test
    void createMintsACorIdAndCarriesDescriptionAndCreator() {
        var o = CorsOrigin.create(Origin.parse(" https://example.com "), "frontend", "prn_123");
        assertThat(o.id()).startsWith("cor_");
        assertThat(o.origin()).isEqualTo("https://example.com");
        assertThat(o.description()).isEqualTo("frontend");
        assertThat(o.createdBy()).isEqualTo("prn_123");
        assertThat(o.createdAt()).isEqualTo(o.updatedAt());
    }

    @Test
    void createAcceptsAbsentDescriptionAndCreator() {
        var o = CorsOrigin.create(Origin.parse("http://localhost:3000"), null, null);
        assertThat(o.description()).isNull();
        assertThat(o.createdBy()).isNull();
    }

    private static void assertUseCaseError(ThrowingCallable call, Class<? extends UseCaseError> kind, String code) {
        assertThatThrownBy(call)
                .isInstanceOf(UseCaseException.class)
                .extracting(t -> ((UseCaseException) t).error())
                .satisfies(err -> {
                    assertThat(err).as("error kind").isInstanceOf(kind);
                    assertThat(err.code()).as("error code").isEqualTo(code);
                });
    }
}
