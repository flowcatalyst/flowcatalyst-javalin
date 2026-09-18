package io.flowcatalyst.platform.ingest;

import io.flowcatalyst.platform.audit.AuditLog;
import io.flowcatalyst.platform.dispatchjob.DispatchJob;
import io.flowcatalyst.platform.dispatchjob.DispatchJobKind;
import io.flowcatalyst.platform.dispatchjob.DispatchJobStatus;
import io.flowcatalyst.platform.dispatchjob.Protocol;
import io.flowcatalyst.platform.dispatchjob.RetryStrategy;
import io.flowcatalyst.platform.event.Event;
import io.flowcatalyst.platform.shared.dispatch.DispatchMode;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The sdk-ingest spec §4.1 defaults table, and the events/audit
/// defaulting rules of §3.1/§4.3 — no HTTP, no DB: each mapper's pure
/// domain-level defaulting, tested directly. `IngestApiTest` covers the
/// same defaults again through the HTTP + DB round trip; this class pins
/// them at the unit the spec actually calls out as load-bearing so a
/// regression here fails fast without standing up Postgres.
class IngestMappingTest {

    // ── Events (spec §3.1/§3.2) ─────────────────────────────────────────

    @Test
    void anAbsentEventIdMintsATsidButASuppliedOneIsKeptVerbatim() {
        var minted = EventIngestMapper.toEvent(rawEvent(null, null, null));
        assertThat(minted.id()).hasSize(13);

        var kept = EventIngestMapper.toEvent(rawEvent("evn_custom_id", null, null));
        assertThat(kept.id()).isEqualTo("evn_custom_id");
    }

    @Test
    void anAbsentSpecVersionDefaultsToOnePointZeroButASuppliedOneIsKept() {
        assertThat(EventIngestMapper.toEvent(rawEvent(null, null, null)).specVersion()).isEqualTo("1.0");
        assertThat(EventIngestMapper.toEvent(rawEvent(null, "2.0", null)).specVersion()).isEqualTo("2.0");
    }

    @Test
    void anAbsentDeduplicationIdDefaultsToTypeDashTsidButASuppliedOneIsKept() {
        var minted = EventIngestMapper.toEvent(rawEvent(null, null, null));
        assertThat(minted.deduplicationId()).startsWith("it.event.type-").hasSize("it.event.type-".length() + 13);

        var kept = EventIngestMapper.toEvent(rawEvent(null, null, "my-dedup-id"));
        assertThat(kept.deduplicationId()).isEqualTo("my-dedup-id");
    }

    @Test
    void missingDataIsRejected() {
        assertThatThrownBy(() -> EventIngestMapper.toEvent(
                new EventIngestMapper.RawItem(null, null, "it.event.type", "src", null, null, null, null, null, null, null, List.of())))
                .isInstanceOf(UseCaseException.class)
                .hasMessageContaining("data is required");
    }

    private static EventIngestMapper.RawItem rawEvent(String id, String specVersion, String deduplicationId) {
        var data = tools.jackson.databind.node.JsonNodeFactory.instance.objectNode().put("k", "v");
        return new EventIngestMapper.RawItem(id, specVersion, "it.event.type", "src", null, data,
                deduplicationId, null, null, null, null, List.of());
    }

    // ── Dispatch jobs (spec §4.1 defaults table) ────────────────────────

    @ParameterizedTest(name = "kind {0} -> {1}")
    @CsvSource({"'',EVENT", "EVENT,EVENT", "TASK,TASK"})
    void kindDefaultsToEventWhenAbsent(String wire, DispatchJobKind expected) {
        assertThat(DispatchJobIngestMapper.toJob(rawJob(it -> it.withKind(wire))).kind()).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"NOT_A_KIND", "event", "task"})
    void aNonBlankUnrecognisedKindIsRejectedNeverCoercedToEvent(String bad) {
        assertThatThrownBy(() -> DispatchJobIngestMapper.toJob(rawJob(it -> it.withKind(bad))))
                .isInstanceOf(UseCaseException.class)
                .hasMessageContaining("INVALID_KIND");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "  ", "NOT_A_MODE"})
    void modeDefaultsToNextOnErrorWhenAbsentOrUnrecognised(String wire) {
        assertThat(DispatchJobIngestMapper.toJob(rawJob(it -> it.withMode(wire))).mode()).isEqualTo(DispatchMode.NEXT_ON_ERROR);
    }

    @Test
    void payloadContentTypeDefaultsToApplicationJsonWhenBlank() {
        assertThat(DispatchJobIngestMapper.toJob(rawJob(it -> it.withPayloadContentType(""))).payloadContentType())
                .isEqualTo("application/json");
        assertThat(DispatchJobIngestMapper.toJob(rawJob(it -> it.withPayloadContentType("text/plain"))).payloadContentType())
                .isEqualTo("text/plain");
    }

    @Test
    void sequenceZeroDefaultsToNinetyNineButAnOverrideWinsIncludingExplicitZero() {
        assertThat(DispatchJobIngestMapper.toJob(rawJob(it -> it)).sequence()).isEqualTo(99);
        assertThat(DispatchJobIngestMapper.toJob(rawJob(it -> it.withSequence(7))).sequence()).isEqualTo(7);
        assertThat(DispatchJobIngestMapper.toJob(rawJob(it -> it.withSequenceOverride(0))).sequence())
                .as("an explicit singular override of 0 must win over the 0->99 default")
                .isEqualTo(0);
        assertThat(DispatchJobIngestMapper.toJob(rawJob(it -> it.withSequenceOverride(42))).sequence()).isEqualTo(42);
    }

    @Test
    void timeoutSecondsZeroDefaultsToThirty() {
        assertThat(DispatchJobIngestMapper.toJob(rawJob(it -> it)).timeoutSeconds()).isEqualTo(30);
        assertThat(DispatchJobIngestMapper.toJob(rawJob(it -> it.withTimeoutSeconds(5))).timeoutSeconds()).isEqualTo(5);
    }

    @Test
    void maxRetriesZeroDefaultsToThree() {
        assertThat(DispatchJobIngestMapper.toJob(rawJob(it -> it)).maxRetries()).isEqualTo(3);
        assertThat(DispatchJobIngestMapper.toJob(rawJob(it -> it.withMaxRetries(9))).maxRetries()).isEqualTo(9);
    }

    @Test
    void protocolIsAlwaysHttpWebhook() {
        assertThat(DispatchJobIngestMapper.toJob(rawJob(it -> it)).protocol()).isEqualTo(Protocol.HTTP_WEBHOOK);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "immediate", "IMMEDIATE", "fixed", "FIXED_DELAY", "exponential"})
    void retryStrategyDefaultsToExponentialWhenAbsent(String wire) {
        var expected = switch (wire == null ? "" : wire) {
            case "immediate", "IMMEDIATE" -> RetryStrategy.IMMEDIATE;
            case "fixed", "FIXED_DELAY" -> RetryStrategy.FIXED;
            default -> RetryStrategy.EXPONENTIAL;
        };
        assertThat(DispatchJobIngestMapper.toJob(rawJob(it -> it.withRetryStrategy(wire))).retryStrategy()).isEqualTo(expected);
    }

    @Test
    void aNonBlankUnrecognisedRetryStrategyIsRejected() {
        assertThatThrownBy(() -> DispatchJobIngestMapper.toJob(rawJob(it -> it.withRetryStrategy("NOT_A_STRATEGY"))))
                .isInstanceOf(UseCaseException.class)
                .hasMessageContaining("INVALID_RETRY_STRATEGY");
    }

    @Test
    void statusIsAlwaysPending() {
        assertThat(DispatchJobIngestMapper.toJob(rawJob(it -> it)).status()).isEqualTo(DispatchJobStatus.PENDING);
    }

    @Test
    void anAbsentJobIdMintsAThirteenCharTsidButASuppliedOneIsKeptVerbatim() {
        assertThat(DispatchJobIngestMapper.toJob(rawJob(it -> it)).id()).hasSize(13);
        assertThat(DispatchJobIngestMapper.toJob(rawJob(it -> it.withId("myjobid12345"))).id()).isEqualTo("myjobid12345");
    }

    // ── Dispatch jobs: queue priority (dispatch-job-priority spec R3) ───

    @Test
    void queueAbsentOrBlankStoresNull() {
        assertThat(DispatchJobIngestMapper.toJob(rawJob(it -> it)).queue()).isNull();
        assertThat(DispatchJobIngestMapper.toJob(rawJob(it -> it.withQueue("  "))).queue()).isNull();
    }

    @ParameterizedTest(name = "queue \"{0}\" -> {1}")
    @CsvSource({"DEFAULT, DEFAULT", "default, DEFAULT", "HIGH_PRIORITY, HIGH_PRIORITY", "high_priority, HIGH_PRIORITY"})
    void queueIsStoredCaseInsensitivelyWhenRecognised(String wire, String expected) {
        assertThat(DispatchJobIngestMapper.toJob(rawJob(it -> it.withQueue(wire))).queue()).isEqualTo(expected);
    }

    /// T3: unrecognised legacy text on create is rejected exactly like any
    /// other bad queue value — R3 validates the same way a subscription's
    /// does, never silently accepting arbitrary strings the way the READ
    /// side's `forJob`/`forPublishing` leniency does.
    @ParameterizedTest
    @ValueSource(strings = {"workers-high", "low", "NOT_A_QUEUE"})
    void aNonBlankUnrecognisedQueueIsRejected(String bad) {
        assertThatThrownBy(() -> DispatchJobIngestMapper.toJob(rawJob(it -> it.withQueue(bad))))
                .isInstanceOf(UseCaseException.class)
                .hasMessageContaining("INVALID_QUEUE");
    }

    private static DispatchJobIngestMapper.RawItem rawJob(java.util.function.Function<Raw, Raw> f) {
        var raw = f.apply(new Raw(null, "EVENT", "", 0, null, 0, 0, null, null, null));
        return new DispatchJobIngestMapper.RawItem(raw.id, null, raw.kind, "it:dispatch:job:created", null, null,
                "https://target.test/hook", null, raw.payloadContentType, false, null, null, null, null, null, null,
                null, raw.mode, raw.sequence, raw.sequenceOverride, raw.timeoutSeconds, raw.maxRetries,
                raw.retryStrategy, List.of(), null, raw.queue);
    }

    /// A small builder so each defaulting test only names the one field it varies.
    private record Raw(String id, String kind, String payloadContentType, int sequence, Integer sequenceOverride,
                        int timeoutSeconds, int maxRetries, String retryStrategy, String mode, String queue) {
        Raw withId(String v) { return new Raw(v, kind, payloadContentType, sequence, sequenceOverride, timeoutSeconds, maxRetries, retryStrategy, mode, queue); }
        Raw withKind(String v) { return new Raw(id, v, payloadContentType, sequence, sequenceOverride, timeoutSeconds, maxRetries, retryStrategy, mode, queue); }
        Raw withPayloadContentType(String v) { return new Raw(id, kind, v, sequence, sequenceOverride, timeoutSeconds, maxRetries, retryStrategy, mode, queue); }
        Raw withSequence(int v) { return new Raw(id, kind, payloadContentType, v, sequenceOverride, timeoutSeconds, maxRetries, retryStrategy, mode, queue); }
        Raw withSequenceOverride(Integer v) { return new Raw(id, kind, payloadContentType, sequence, v, timeoutSeconds, maxRetries, retryStrategy, mode, queue); }
        Raw withTimeoutSeconds(int v) { return new Raw(id, kind, payloadContentType, sequence, sequenceOverride, v, maxRetries, retryStrategy, mode, queue); }
        Raw withMaxRetries(int v) { return new Raw(id, kind, payloadContentType, sequence, sequenceOverride, timeoutSeconds, v, retryStrategy, mode, queue); }
        Raw withRetryStrategy(String v) { return new Raw(id, kind, payloadContentType, sequence, sequenceOverride, timeoutSeconds, maxRetries, v, mode, queue); }
        Raw withMode(String v) { return new Raw(id, kind, payloadContentType, sequence, sequenceOverride, timeoutSeconds, maxRetries, retryStrategy, v, queue); }
        Raw withQueue(String v) { return new Raw(id, kind, payloadContentType, sequence, sequenceOverride, timeoutSeconds, maxRetries, retryStrategy, mode, v); }
    }

    // ── Audit logs (spec §4.3) ───────────────────────────────────────────

    @Test
    void performedAtParsesRfc3339WhenPresent() {
        var log = AuditLogIngestMapper.toLog("Entity", "e1", "CREATE", null, "prn_ACTOR",
                "2026-01-02T03:04:05Z", null, null);
        assertThat(log.performedAt()).isEqualTo(Instant.parse("2026-01-02T03:04:05Z"));
    }

    @Test
    void performedAtDefaultsToNowWhenAbsentOrUnparseable() {
        Instant before = Instant.now();
        var absent = AuditLogIngestMapper.toLog("Entity", "e1", "CREATE", null, "prn_ACTOR",
                null, null, null);
        var malformed = AuditLogIngestMapper.toLog("Entity", "e1", "CREATE", null, "prn_ACTOR",
                "not-a-timestamp", null, null);
        Instant after = Instant.now();

        assertThat(absent.performedAt()).isBetween(before, after);
        assertThat(malformed.performedAt()).isBetween(before, after);
    }

    /// Owner ruling 2026-09-06 #10b: the actor is required — the mapper never
    /// invents one, the handler refuses the item before calling it.
    @Test
    void principalIdIsRequiredAndKeptVerbatim() {
        var explicit = AuditLogIngestMapper.toLog("Entity", "e1", "CREATE", null, "explicit-principal",
                null, null, null);
        assertThat(explicit.principalId()).isEqualTo("explicit-principal");
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                AuditLogIngestMapper.toLog("Entity", "e1", "CREATE", null, "  ", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void idIsAFreshAuditLogTsid() {
        var log = AuditLogIngestMapper.toLog("Entity", "e1", "CREATE", null, "p", null, null, null);
        assertThat(log.id()).startsWith("aud_");
    }
}
