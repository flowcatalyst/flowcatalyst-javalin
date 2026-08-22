package io.flowcatalyst.platform.application;

import io.flowcatalyst.sdk.usecase.UseCaseError;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The aggregates' pure rules (spec §1–2): code normalisation, lenient type
/// reads, and every transition with its error code — no database involved.
class ApplicationTest {

    // ── Code ───────────────────────────────────────────────────────────────

    @ParameterizedTest
    @CsvSource({"logistics_portal,logistics_portal", "'  Transport-Order ',transport-order", "a1,a1", "ABC,abc"})
    void codeIsTrimmedAndLowerCasedBeforeValidation(String raw, String expected) {
        assertThat(ApplicationCode.parse(raw).value()).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void blankCodeIsRequired(String raw) {
        assertUseCaseError(() -> ApplicationCode.parse(raw), UseCaseError.Validation.class, "CODE_REQUIRED");
        assertUseCaseError(() -> ApplicationCode.parse(null), UseCaseError.Validation.class, "CODE_REQUIRED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"1bad", "-lead", "_lead", "has space", "dots.not.ok", "colon:no", "ümlaut"})
    void malformedCodeIsRejectedWithTheOneFormatMessage(String raw) {
        assertUseCaseError(() -> ApplicationCode.parse(raw), UseCaseError.Validation.class, "INVALID_CODE_FORMAT");
        assertThatThrownBy(() -> ApplicationCode.parse(raw)).hasMessageContaining(ApplicationCode.FORMAT_MESSAGE);
    }

    // ── Type ───────────────────────────────────────────────────────────────

    @Test
    void typeReadsLenientlyDefaultingToApplication() {
        assertThat(ApplicationType.parse("INTEGRATION")).isEqualTo(ApplicationType.INTEGRATION);
        assertThat(ApplicationType.parse("APPLICATION")).isEqualTo(ApplicationType.APPLICATION);
        assertThat(ApplicationType.parse("integration")).as("case-sensitive, like the stored string").isEqualTo(ApplicationType.APPLICATION);
        assertThat(ApplicationType.parse("bogus")).isEqualTo(ApplicationType.APPLICATION);
        assertThat(ApplicationType.parse(null)).isEqualTo(ApplicationType.APPLICATION);
    }

    // ── Create ─────────────────────────────────────────────────────────────

    @Test
    void createIsActiveWithNormalisedCodeTrimmedNameAndNoDetails() {
        var a = Application.create(ApplicationType.APPLICATION, "  Orders_App ", "  Orders  ");
        assertThat(a.id()).startsWith("app_");
        assertThat(a.code()).isEqualTo("orders_app");
        assertThat(a.name()).isEqualTo("Orders");
        assertThat(a.type()).isEqualTo(ApplicationType.APPLICATION);
        assertThat(a.isIntegration()).isFalse();
        assertThat(a.active()).isTrue();
        assertThat(a.hasServiceAccount()).isFalse();
        assertThat(a.description()).isNull();
        assertThat(a.iconUrl()).isNull();
        assertThat(a.website()).isNull();
        assertThat(a.logo()).isNull();
        assertThat(a.logoMimeType()).isNull();
        assertThat(a.defaultBaseUrl()).isNull();
        assertThat(a.createdAt()).isEqualTo(a.updatedAt());
    }

    @Test
    void createAsIntegrationKeepsTheType() {
        var a = Application.create(ApplicationType.INTEGRATION, "crm", "CRM");
        assertThat(a.isIntegration()).isTrue();
        assertThat(a.type()).isEqualTo(ApplicationType.INTEGRATION);
    }

    @Test
    void createRejectsAMalformedCode() {
        assertUseCaseError(() -> Application.create(ApplicationType.APPLICATION, "1bad", "x"),
                UseCaseError.Validation.class, "INVALID_CODE_FORMAT");
        assertUseCaseError(() -> Application.create(ApplicationType.APPLICATION, " ", "x"),
                UseCaseError.Validation.class, "CODE_REQUIRED");
    }

    // ── Active flag ────────────────────────────────────────────────────────

    @Test
    void activateAndDeactivateAreIdempotentAndLeaveTheOriginalUntouched() {
        var a = Application.create(ApplicationType.APPLICATION, "flag", "Flag");
        var off = a.deactivate();
        assertThat(off.active()).isFalse();
        assertThat(off.updatedAt()).isAfterOrEqualTo(a.updatedAt());
        assertThat(a.active()).as("records are immutable").isTrue();
        assertThat(off.deactivate().active()).as("no-op transition is allowed").isFalse();
        assertThat(off.activate().active()).isTrue();
        assertThat(a.activate().active()).as("activating an active application is allowed").isTrue();
    }

    // ── Service account ────────────────────────────────────────────────────

    @Test
    void serviceAccountIsAttachedOnce() {
        var a = Application.create(ApplicationType.APPLICATION, "sa", "SA");
        var attached = a.attachServiceAccount("sac_principal00001");
        assertThat(attached.serviceAccountId()).isEqualTo("sac_principal00001");
        assertThat(attached.hasServiceAccount()).isTrue();
        assertThat(a.hasServiceAccount()).isFalse();
        assertUseCaseError(() -> attached.attachServiceAccount("sac_principal00002"),
                UseCaseError.BusinessRule.class, "APPLICATION_HAS_SERVICE_ACCOUNT");
    }

    // ── Copies ─────────────────────────────────────────────────────────────

    @Test
    void detailCopiesReplaceOneFieldEach() {
        var a = Application.create(ApplicationType.APPLICATION, "copy", "Copy")
                .withName("  Renamed ")
                .withDescription("d")
                .withIconUrl("i")
                .withWebsite("w")
                .withLogo("l")
                .withLogoMimeType("image/svg+xml")
                .withDefaultBaseUrl("https://b");
        assertThat(a.name()).as("name is trimmed").isEqualTo("Renamed");
        assertThat(a.description()).isEqualTo("d");
        assertThat(a.iconUrl()).isEqualTo("i");
        assertThat(a.website()).isEqualTo("w");
        assertThat(a.logo()).isEqualTo("l");
        assertThat(a.logoMimeType()).isEqualTo("image/svg+xml");
        assertThat(a.defaultBaseUrl()).isEqualTo("https://b");
        assertThat(a.withDescription(null).description()).isNull();
    }

    // ── Client config ──────────────────────────────────────────────────────

    @Test
    void clientConfigStartsEnabledAndFlipsBothWays() {
        var c = ClientConfig.create("app_x", "clt_y");
        assertThat(c.id()).startsWith("apc_");
        assertThat(c.applicationId()).isEqualTo("app_x");
        assertThat(c.clientId()).isEqualTo("clt_y");
        assertThat(c.enabled()).isTrue();
        var off = c.disable();
        assertThat(off.enabled()).isFalse();
        assertThat(off.id()).as("same row").isEqualTo(c.id());
        assertThat(off.disable().enabled()).as("idempotent").isFalse();
        assertThat(off.enable().enabled()).isTrue();
        assertThat(c.enabled()).as("records are immutable").isTrue();
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
