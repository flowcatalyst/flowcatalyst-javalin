package io.flowcatalyst.platform.client;

import io.flowcatalyst.sdk.usecase.UseCaseError;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The aggregate's pure rules (spec §1–2): identifier normalisation, lenient
/// status reads, and every transition — no database involved.
class ClientTest {

    private static Client fresh() {
        return Client.create("  Acme Corp  ", ClientIdentifier.parse("  ACME-Corp  "));
    }

    // ── Identifier ─────────────────────────────────────────────────────────

    @ParameterizedTest(name = "\"{0}\" → \"{1}\"")
    @CsvSource({
            "acme, acme",
            "'  ACME-Corp  ', acme-corp",
            "a, a",
            "7, 7",
            "a-b-c, a-b-c",
            "ABC123, abc123"})
    void identifierIsTrimmedAndLowerCased(String raw, String expected) {
        assertThat(ClientIdentifier.parse(raw)).isEqualTo(new ClientIdentifier(expected));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void identifierRejectsBlank(String raw) {
        assertUseCaseError(() -> ClientIdentifier.parse(raw), UseCaseError.Validation.class, "IDENTIFIER_REQUIRED");
    }

    @Test
    void identifierRejectsNull() {
        assertUseCaseError(() -> ClientIdentifier.parse(null), UseCaseError.Validation.class, "IDENTIFIER_REQUIRED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"my_client", "-abc", "abc-", "a b", "acme.corp", "acme/corp", "-"})
    void identifierRejectsNonSlugs(String raw) {
        assertUseCaseError(() -> ClientIdentifier.parse(raw), UseCaseError.Validation.class, "INVALID_IDENTIFIER");
        assertThatThrownBy(() -> ClientIdentifier.parse(raw)).hasMessageContaining(ClientIdentifier.FORMAT_MESSAGE);
    }

    // ── Status ─────────────────────────────────────────────────────────────

    @Test
    void statusReadsLenientlyDefaultingToActive() {
        assertThat(ClientStatus.parse("SUSPENDED")).isEqualTo(ClientStatus.SUSPENDED);
        assertThat(ClientStatus.parse("INACTIVE")).isEqualTo(ClientStatus.INACTIVE);
        assertThat(ClientStatus.parse("ACTIVE")).isEqualTo(ClientStatus.ACTIVE);
        assertThat(ClientStatus.parse("garbage")).isEqualTo(ClientStatus.ACTIVE);
        assertThat(ClientStatus.parse(null)).isEqualTo(ClientStatus.ACTIVE);
    }

    // ── Create ─────────────────────────────────────────────────────────────

    @Test
    void createIsActiveWithTrimmedNameNormalisedIdentifierAndNoNotes() {
        var c = fresh();
        assertThat(c.id()).startsWith("clt_");
        assertThat(c.name()).as("name is trimmed").isEqualTo("Acme Corp");
        assertThat(c.identifier()).as("identifier is normalised").isEqualTo("acme-corp");
        assertThat(c.status()).isEqualTo(ClientStatus.ACTIVE);
        assertThat(c.isActive()).isTrue();
        assertThat(c.isSuspended()).isFalse();
        assertThat(c.statusReason()).isNull();
        assertThat(c.statusChangedAt()).as("no status change yet").isNull();
        assertThat(c.notes()).isEmpty();
        assertThat(c.createdAt()).isEqualTo(c.updatedAt());
    }

    // ── Rename ─────────────────────────────────────────────────────────────

    @Test
    void renameTrimsAndKeepsTheIdentifier() {
        var c = fresh();
        var renamed = c.rename("  New Name ");
        assertThat(renamed.name()).isEqualTo("New Name");
        assertThat(renamed.identifier()).isEqualTo(c.identifier());
        assertThat(c.name()).as("records are immutable").isEqualTo("Acme Corp");
    }

    // ── Suspend / activate ─────────────────────────────────────────────────

    @Test
    void suspendRecordsReasonAndTimeAndActivateClearsTheReason() {
        var c = fresh();
        var suspended = c.suspend("billing overdue");
        assertThat(suspended.status()).isEqualTo(ClientStatus.SUSPENDED);
        assertThat(suspended.isSuspended()).isTrue();
        assertThat(suspended.statusReason()).isEqualTo("billing overdue");
        assertThat(suspended.statusChangedAt()).isNotNull();
        assertThat(suspended.updatedAt()).isAfterOrEqualTo(c.updatedAt());
        assertThat(c.status()).as("original untouched").isEqualTo(ClientStatus.ACTIVE);

        var activated = suspended.activate();
        assertThat(activated.status()).isEqualTo(ClientStatus.ACTIVE);
        assertThat(activated.statusReason()).as("activation clears the suspension reason").isNull();
        assertThat(activated.statusChangedAt()).isAfterOrEqualTo(suspended.statusChangedAt());
    }

    /// Spec §2: no preconditions — repeating a transition re-stamps rather than failing.
    @Test
    void suspendAndActivateHaveNoPreconditions() {
        var c = fresh();
        var twice = c.suspend("first").suspend("second");
        assertThat(twice.status()).isEqualTo(ClientStatus.SUSPENDED);
        assertThat(twice.statusReason()).isEqualTo("second");

        var active = c.activate();
        assertThat(active.status()).isEqualTo(ClientStatus.ACTIVE);
        assertThat(active.statusChangedAt()).as("activating an active client stamps the change time").isNotNull();
    }

    // ── Notes ──────────────────────────────────────────────────────────────

    @Test
    void addNoteAppendsInOrderAndBumpsUpdatedAt() {
        var c = fresh();
        var one = c.addNote(ClientNote.of("billing", "switched to annual", "prn_x"));
        var two = one.addNote(ClientNote.of("support", "ticket 42", null));

        assertThat(two.notes()).extracting(ClientNote::category).containsExactly("billing", "support");
        assertThat(two.notes().getFirst().addedBy()).isEqualTo("prn_x");
        assertThat(two.notes().get(1).addedBy()).as("no principal → no addedBy").isNull();
        assertThat(two.notes().get(1).addedAt()).isNotNull();
        assertThat(two.updatedAt()).isAfterOrEqualTo(c.updatedAt());
        assertThat(c.notes()).as("original untouched").isEmpty();
        assertThatThrownBy(() -> two.notes().add(ClientNote.of("x", "y", null)))
                .as("notes list is immutable").isInstanceOf(UnsupportedOperationException.class);
    }

    // ── Helpers ────────────────────────────────────────────────────────────

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
