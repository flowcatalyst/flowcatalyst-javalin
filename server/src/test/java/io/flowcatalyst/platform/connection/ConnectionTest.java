package io.flowcatalyst.platform.connection;

import io.flowcatalyst.sdk.usecase.UseCaseError;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The aggregate's pure rules (spec §1–2): code normalisation, the lenient
/// status reader and the pause / activate transitions — no database involved.
class ConnectionTest {

    private static final ConnectionCode CODE = ConnectionCode.parse("orders-webhook");

    // ── Code ───────────────────────────────────────────────────────────────

    @ParameterizedTest(name = "''{0}'' → ''{1}''")
    @CsvSource({
            "orders-webhook, orders-webhook",
            "'  ORDERS-Webhook  ', orders-webhook",
            "a1, a1",
            "z, z"})
    void codeIsTrimmedAndLowerCased(String raw, String expected) {
        assertThat(ConnectionCode.parse(raw).value()).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void codeMustNotBeBlank(String raw) {
        assertUseCaseError(() -> ConnectionCode.parse(raw), UseCaseError.Validation.class, "CODE_REQUIRED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"1orders", "-orders", "orders_webhook", "orders webhook", "orders.webhook", "orders:webhook"})
    void codeMustStartWithALetterAndUseOnlyLowercaseAlphanumericsAndHyphens(String raw) {
        assertUseCaseError(() -> ConnectionCode.parse(raw), UseCaseError.Validation.class, "INVALID_CODE_FORMAT");
        assertThatThrownBy(() -> ConnectionCode.parse(raw)).hasMessageContaining(ConnectionCode.FORMAT_MESSAGE);
    }

    // ── Create ─────────────────────────────────────────────────────────────

    @Test
    void createIsActivePlatformWideAndTrimsTheName() {
        var c = Connection.create(CODE, "  Orders Webhook  ", "sva_test1");
        assertThat(c.id()).startsWith("con_");
        assertThat(c.code()).isEqualTo("orders-webhook");
        assertThat(c.name()).isEqualTo("Orders Webhook");
        assertThat(c.status()).isEqualTo(ConnectionStatus.ACTIVE);
        assertThat(c.isActive()).isTrue();
        assertThat(c.serviceAccountId()).isEqualTo("sva_test1");
        assertThat(c.description()).isNull();
        assertThat(c.externalId()).isNull();
        assertThat(c.clientId()).isNull();
        assertThat(c.clientIdentifier()).isNull();
        assertThat(c.createdAt()).isEqualTo(c.updatedAt());
    }

    // ── Transitions ────────────────────────────────────────────────────────

    @Test
    void pauseAndActivateFlipTheStatusAndLeaveTheOriginalUntouched() {
        var c = Connection.create(CODE, "Name", "sva_test1");
        var paused = c.pause();
        assertThat(paused.isPaused()).isTrue();
        assertThat(paused.updatedAt()).isAfterOrEqualTo(c.updatedAt());
        assertThat(c.status()).as("records are immutable").isEqualTo(ConnectionStatus.ACTIVE);

        var reactivated = paused.activate();
        assertThat(reactivated.isActive()).isTrue();
        assertThat(reactivated.updatedAt()).isAfterOrEqualTo(paused.updatedAt());
    }

    @Test
    void pauseAndActivateAreIdempotent() {
        var paused = Connection.create(CODE, "Name", "sva_test1").pause();
        assertThat(paused.pause().status()).as("spec §2 open question 3: no ALREADY_PAUSED").isEqualTo(ConnectionStatus.PAUSED);
        assertThat(paused.activate().activate().status()).isEqualTo(ConnectionStatus.ACTIVE);
    }

    // ── Copies ─────────────────────────────────────────────────────────────

    @Test
    void copiesReplaceOneFieldEachAndKeepTheRest() {
        var c = Connection.create(CODE, "Before", "sva_test1")
                .withDescription("old")
                .withExternalId("ext-old")
                .withClientId("cli_x")
                .pause();
        var updated = c.withName("  After  ").withDescription(null).withExternalId("ext-new");
        assertThat(updated.name()).as("name is trimmed on update").isEqualTo("After");
        assertThat(updated.description()).as("null clears, it is a full replace").isNull();
        assertThat(updated.externalId()).isEqualTo("ext-new");
        assertThat(updated.code()).isEqualTo(c.code());
        assertThat(updated.status()).isEqualTo(ConnectionStatus.PAUSED);
        assertThat(updated.clientId()).isEqualTo("cli_x");
        assertThat(updated.serviceAccountId()).isEqualTo("sva_test1");
        assertThat(updated.createdAt()).isEqualTo(c.createdAt());
    }

    // ── Wire-side lenient reader (the update command's status field) ───────

    /// Exactly `PAUSED` pauses; anything else — other case, whitespace,
    /// unknown, absent — reads as `ACTIVE` (spec §1, open question 4). This
    /// is [ConnectionStatus#parseCommandStatus], the wire-only reader —
    /// untouched by X-06, which governs stored rows, not request bodies.
    @ParameterizedTest(name = "''{0}'' → {1}")
    @CsvSource(nullValues = "null", value = {
            "PAUSED, PAUSED",
            "ACTIVE, ACTIVE",
            "'  PAUSED ', ACTIVE",
            "paused, ACTIVE",
            "UNKNOWN, ACTIVE",
            "'', ACTIVE",
            "null, ACTIVE"})
    void commandStatusIsReadLenientlyWithActiveAsTheDefault(String raw, ConnectionStatus expected) {
        assertThat(ConnectionStatus.parseCommandStatus(raw)).isEqualTo(expected);
    }

    // ── Stored-side strict reader (X-06) ────────────────────────────────────

    @ParameterizedTest(name = "''{0}'' → {1}")
    @CsvSource({"ACTIVE, ACTIVE", "PAUSED, PAUSED"})
    void storedStatusParsesTheTwoRecognisedValues(String raw, ConnectionStatus expected) {
        assertThat(ConnectionStatus.parse(raw)).isEqualTo(expected);
    }

    /// X-06: unlike [#commandStatusIsReadLenientlyWithActiveAsTheDefault],
    /// the stored reader never defaults — an unrecognised or absent value is
    /// a corrupt row, not a fallback to `ACTIVE`.
    @ParameterizedTest(name = "''{0}''")
    @CsvSource(nullValues = "null", value = {"UNKNOWN", "active", "'  PAUSED '", "''", "null"})
    void storedStatusRejectsAnythingElse(String raw) {
        assertThatThrownBy(() -> ConnectionStatus.parse(raw))
                .isInstanceOf(ConnectionStatus.UnrecognisedConnectionStatusException.class);
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
