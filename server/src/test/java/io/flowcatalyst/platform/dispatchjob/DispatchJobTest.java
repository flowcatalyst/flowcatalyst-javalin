package io.flowcatalyst.platform.dispatchjob;

import io.flowcatalyst.platform.subscription.DispatchMode;
import io.flowcatalyst.sdk.tsid.Tsid;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The pure rules of the aggregate (spec §1–2): the requeue transition, the
/// lenient enum readers, terminality and the code-derived facets. No
/// database; the envelope and persistence are covered by the operations and
/// repository tests.
class DispatchJobTest {

    private static final Instant CREATED = Instant.parse("2026-03-01T10:00:00.000000Z");

    /// A `FAILED` job that consumed its budget and carries every terminal stamp.
    private static DispatchJob failedJob() {
        return job(DispatchJobStatus.FAILED);
    }

    /// A job in `status` that consumed its budget and carries every terminal stamp.
    private static DispatchJob job(DispatchJobStatus status) {
        return new DispatchJob(Tsid.generate(), "ext-1", DispatchJobKind.EVENT, "orders:fulfillment:shipment:shipped",
                "orders", "shipment-1", "https://hook.example/in", Protocol.HTTP_WEBHOOK, "{\"a\":1}",
                "application/json", true, "evt1", "corr-1", "cli_1", "sub_1", "sa_1", "dpl_1", "group-1",
                DispatchMode.BLOCK_ON_ERROR, 3, 30, null, 3, RetryStrategy.EXPONENTIAL, status,
                3, "boom", List.of(new DispatchJob.Metadata("k", "v")), "idem-1", CREATED, CREATED.plusSeconds(60),
                CREATED.plusSeconds(30), CREATED.plusSeconds(3600), CREATED.plusSeconds(50), CREATED.plusSeconds(60), 1234L);
    }

    // ── requeue (spec §2) ──────────────────────────────────────────────────

    @Test
    void requeueResetsTheDeliveryStateAndKeepsTheMessage() {
        DispatchJob before = failedJob();
        DispatchJob after = before.requeue();

        assertThat(after.status()).isEqualTo(DispatchJobStatus.PENDING);
        assertThat(after.scheduledFor()).as("immediately eligible").isNull();
        assertThat(after.attemptCount()).as("full budget").isZero();
        assertThat(after.lastError()).isNull();
        assertThat(after.completedAt()).isNull();
        assertThat(after.durationMillis()).isNull();
        assertThat(after.updatedAt()).isAfter(before.updatedAt());

        // untouched: identity, routing, message, history stamps that are not terminal
        assertThat(after.id()).isEqualTo(before.id());
        assertThat(after.createdAt()).as("partition key never changes").isEqualTo(CREATED);
        assertThat(after.code()).isEqualTo(before.code());
        assertThat(after.payload()).isEqualTo(before.payload());
        assertThat(after.metadata()).isEqualTo(before.metadata());
        assertThat(after.messageGroup()).isEqualTo("group-1");
        assertThat(after.maxRetries()).isEqualTo(3);
        assertThat(after.expiresAt()).isEqualTo(before.expiresAt());
        assertThat(after.lastAttemptAt()).isEqualTo(before.lastAttemptAt());
    }

    /// Total — no precondition (spec §2, open question 2): every status, terminal or in flight, resets.
    @ParameterizedTest(name = "requeue from {0} resets to PENDING")
    @EnumSource(DispatchJobStatus.class)
    void requeueIsTotalSoAnyStatusResets(DispatchJobStatus status) {
        assertThat(job(status).requeue().status()).isEqualTo(DispatchJobStatus.PENDING);
    }

    // ── cancel / complete (spec §8) ─────────────────────────────────────────

    @Test
    void cancelFlipsToCancelledAndStampsCompletedAtButKeepsTheFailureEvidence() {
        DispatchJob before = failedJob();
        DispatchJob after = before.cancel();

        assertThat(after.status()).isEqualTo(DispatchJobStatus.CANCELLED);
        assertThat(after.completedAt()).isAfterOrEqualTo(before.completedAt().minusSeconds(1));
        assertThat(after.updatedAt()).isAfter(before.updatedAt());
        // preserved, not cleared — Go's Cancel() touches only status/completedAt/updatedAt
        assertThat(after.lastError()).isEqualTo("boom");
        assertThat(after.attemptCount()).isEqualTo(3);
        assertThat(after.scheduledFor()).isEqualTo(before.scheduledFor());
        assertThat(after.id()).isEqualTo(before.id());
    }

    @Test
    void completeFlipsToCompletedAndStampsCompletedAtButKeepsTheFailureEvidence() {
        DispatchJob before = failedJob();
        DispatchJob after = before.complete();

        assertThat(after.status()).isEqualTo(DispatchJobStatus.COMPLETED);
        assertThat(after.updatedAt()).isAfter(before.updatedAt());
        assertThat(after.lastError()).isEqualTo("boom");
        assertThat(after.attemptCount()).isEqualTo(3);
    }

    @Test
    void metadataIsDefensivelyCopiedAndNeverNull() {
        DispatchJob j = failedJob();
        DispatchJob none = new DispatchJob(j.id(), null, j.kind(), j.code(), null, null, j.targetUrl(), j.protocol(),
                null, j.payloadContentType(), false, null, null, null, null, null, null, null, DispatchMode.IMMEDIATE,
                99, 30, null, 3, RetryStrategy.EXPONENTIAL, DispatchJobStatus.PENDING, 0, null, null, null,
                CREATED, CREATED, null, null, null, null, null);
        assertThat(none.metadata()).isEmpty();
        assertThat(none.isTerminal()).isFalse();
    }

    // ── Enums (spec §1.1, §1.2, §2) ────────────────────────────────────────

    @ParameterizedTest(name = "status ''{0}'' reads as {1}")
    @CsvSource(value = {
            "PENDING, PENDING",
            "QUEUED, QUEUED",
            "PROCESSING, PROCESSING",
            "IN_PROGRESS, PROCESSING",
            "COMPLETED, COMPLETED",
            "FAILED, FAILED",
            "ERROR, FAILED",
            "CANCELLED, CANCELLED",
            "EXPIRED, EXPIRED"})
    void statusParsesStrictlyWithOnlyTheDocumentedLegacyAliases(String stored, DispatchJobStatus expected) {
        assertThat(DispatchJobStatus.parse(stored)).isEqualTo(expected);
    }

    /// X-06: a value outside the recognised/legacy set is a loud read
    /// failure, never a silent default to `PENDING` — a corrupted terminal
    /// status silently reappearing as `PENDING` could resurrect a job that
    /// already completed or failed (dispatch-seam spec §4).
    @Test
    void statusRejectsAnUnrecognisedOrMissingValue() {
        assertThatThrownBy(() -> DispatchJobStatus.parse("bogus"))
                .isInstanceOf(DispatchJobStatus.UnrecognisedStatusException.class);
        assertThatThrownBy(() -> DispatchJobStatus.parse(null))
                .isInstanceOf(DispatchJobStatus.UnrecognisedStatusException.class);
    }

    @ParameterizedTest(name = "{0} terminal = {1}")
    @CsvSource({
            "PENDING, false", "QUEUED, false", "PROCESSING, false",
            "COMPLETED, true", "FAILED, true", "CANCELLED, true", "EXPIRED, true"})
    void terminalityIsTheFourFinalStates(DispatchJobStatus status, boolean terminal) {
        assertThat(status.isTerminal()).isEqualTo(terminal);
    }

    @ParameterizedTest(name = "kind ''{0}'' reads as {1}")
    @CsvSource(nullValues = "NULL", value = {"EVENT, EVENT", "TASK, TASK", "task, EVENT", "NULL, EVENT"})
    void kindParsesLeniently(String stored, DispatchJobKind expected) {
        assertThat(DispatchJobKind.parse(stored)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "retry strategy ''{0}'' reads as {1} (wire ''{2}'')")
    @CsvSource(nullValues = "NULL", value = {
            "immediate, IMMEDIATE, immediate",
            "IMMEDIATE, IMMEDIATE, immediate",
            "fixed, FIXED, fixed",
            "FIXED_DELAY, FIXED, fixed",
            "exponential, EXPONENTIAL, exponential",
            "linear, EXPONENTIAL, exponential",
            "NULL, EXPONENTIAL, exponential"})
    void retryStrategyParsesLenientlyAndWritesLowercase(String stored, RetryStrategy expected, String wire) {
        assertThat(RetryStrategy.parse(stored)).isEqualTo(expected);
        assertThat(expected.wire()).isEqualTo(wire);
    }

    @ParameterizedTest(name = "error type ''{0}'' reads as {1}")
    @CsvSource(nullValues = "NULL", value = {
            "CONNECTION, CONNECTION", "TIMEOUT, TIMEOUT", "HTTP_ERROR, HTTP_ERROR", "VALIDATION, VALIDATION",
            "UNKNOWN, UNKNOWN", "weird, UNKNOWN", "NULL, UNKNOWN"})
    void errorTypeParsesLeniently(String stored, AttemptErrorType expected) {
        assertThat(AttemptErrorType.parse(stored)).isEqualTo(expected);
    }

    @Test
    void protocolIsAlwaysHttpWebhook() {
        assertThat(Protocol.parse("SMTP")).isEqualTo(Protocol.HTTP_WEBHOOK);
        assertThat(Protocol.parse(null)).isEqualTo(Protocol.HTTP_WEBHOOK);
    }

    // ── Code facets (spec §1.3) ────────────────────────────────────────────

    @ParameterizedTest(name = "''{0}'' → {1} / {2} / {3}")
    @CsvSource(nullValues = "NULL", value = {
            "orders:fulfillment:shipment:shipped, orders, fulfillment, shipment",
            "orders:fulfillment, orders, fulfillment, NULL",
            "orders, orders, NULL, NULL",
            ":fulfillment:shipment, NULL, fulfillment, shipment",
            "'', NULL, NULL, NULL",
            "NULL, NULL, NULL, NULL"})
    void codeFacetsAreTheFirstThreeSegmentsWithEmptyOnesAbsent(String code, String app, String sub, String agg) {
        assertThat(CodeFacets.of(code)).isEqualTo(new CodeFacets(app, sub, agg));
    }
}
