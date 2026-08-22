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

    // ── Origin format ──────────────────────────────────────────────────────

    @ParameterizedTest(name = "\"{0}\" → \"{1}\"")
    @CsvSource({
            "https://example.com, https://example.com",
            "http://localhost:3000, http://localhost:3000",
            "'  https://app.example.com  ', https://app.example.com",
            "https://*.example.com, https://*.example.com",
            "http://127.0.0.1:8080, http://127.0.0.1:8080",
            "https://a, https://a"})
    void originIsTrimmedAndKeptVerbatim(String raw, String expected) {
        assertThat(Origin.parse(raw)).isEqualTo(new Origin(expected));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void originRejectsBlank(String raw) {
        assertUseCaseError(() -> Origin.parse(raw), UseCaseError.Validation.class, "ORIGIN_REQUIRED");
    }

    @Test
    void originRejectsNull() {
        assertUseCaseError(() -> Origin.parse(null), UseCaseError.Validation.class, "ORIGIN_REQUIRED");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "example.com",                  // no scheme
            "ftp://example.com",            // wrong scheme
            "HTTPS://EXAMPLE.COM",          // scheme is lower-case only
            "https://example.com/path",     // path
            "https://example.com/",         // trailing slash
            "https://example.com?x=1",      // query
            "https://example.com#frag",     // fragment
            "https://exa mple.com",         // space in host
            "https://-example.com",         // host cannot start with a hyphen
            "https://example.com-",         // or end with one
            "https://example.com:abc",      // non-numeric port
            "https://",                     // no host
            "https://user@example.com"})    // userinfo
    void originRejectsAnythingButSchemeHostPort(String raw) {
        assertUseCaseError(() -> Origin.parse(raw), UseCaseError.Validation.class, "INVALID_ORIGIN_FORMAT");
        assertThatThrownBy(() -> Origin.parse(raw)).hasMessageContaining(Origin.FORMAT_MESSAGE);
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
