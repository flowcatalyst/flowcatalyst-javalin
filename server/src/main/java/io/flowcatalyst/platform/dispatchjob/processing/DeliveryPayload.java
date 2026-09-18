package io.flowcatalyst.platform.dispatchjob.processing;

import io.flowcatalyst.platform.dispatchjob.DispatchJob;
import io.flowcatalyst.platform.shared.json.Json;
import tools.jackson.core.JacksonException;

import java.nio.charset.StandardCharsets;

/// Builds the body [SubscriberDelivery] sends to the real subscriber
/// (dispatch-seam spec §5 "Delivery request construction", Go
/// `buildPayload`): the raw `payload` bytes in `data_only` mode, otherwise a
/// CloudEvents-ish envelope.
public final class DeliveryPayload {

    private static final byte[] EMPTY_OBJECT = "{}".getBytes(StandardCharsets.UTF_8);

    private DeliveryPayload() {
    }

    /// `job.payload` verbatim when `dataOnly`, else the envelope below.
    /// `attemptNumber` is 1-based — this attempt, not the count already
    /// consumed (`job.attemptCount() + 1`). `clientCode` is the client's
    /// `identifier` slug ([ClientCodeResolver], `docs/spec/webhook-client-code.md`
    /// R1) — `null` when the job has no `clientId` or the client cannot be
    /// resolved, in which case the key is dropped, not sent as `null` (see
    /// below). A `dataOnly` job never gets a `clientCode` in its body — the
    /// raw payload passes through untouched regardless — but it still gets
    /// the `X-FlowCatalyst-Client` header ([SubscriberDelivery], R2).
    ///
    /// ```
    /// {id, type, attemptNumber, source?, subject?, correlationId?,
    ///  messageGroup?, clientId?, clientCode?, data?}
    /// ```
    ///
    /// `data` embeds `payload` as parsed JSON when it parses; a **non-JSON**
    /// payload string passes through as the literal string value of `data`
    /// rather than being dropped (spec §5).
    public static byte[] build(DispatchJob job, String clientCode) {
        if (job.dataOnly()) {
            return job.payload() == null ? EMPTY_OBJECT : job.payload().getBytes(StandardCharsets.UTF_8);
        }
        Object data = job.payload() == null ? null : dataValue(job.payload());
        var envelope = new Envelope(job.id(), job.code(), job.attemptCount() + 1, job.source(), job.subject(),
                job.correlationId(), job.messageGroup(), job.clientId(), clientCode, data);
        return Json.write(envelope).getBytes(StandardCharsets.UTF_8);
    }

    /// The parsed tree when `payload` is valid JSON (embedded inline, not
    /// double-encoded), else the raw string (spec §5's "passes through as
    /// the literal string value" rule).
    private static Object dataValue(String payload) {
        try {
            return Json.MAPPER.readTree(payload);
        } catch (JacksonException e) {
            return payload;
        }
    }

    /// Wire shape only — [tools.jackson.databind.ObjectMapper]'s `NON_ABSENT`
    /// default inclusion (see [Json]) drops every `null` field here.
    private record Envelope(
            String id,
            String type,
            int attemptNumber,
            String source,
            String subject,
            String correlationId,
            String messageGroup,
            String clientId,
            String clientCode,
            Object data) {
    }
}
