package io.flowcatalyst.platform.ingest;

import tools.jackson.databind.JsonNode;
import io.flowcatalyst.platform.event.Event;
import io.flowcatalyst.sdk.tsid.Tsid;
import io.flowcatalyst.sdk.usecase.UseCaseException;

import java.time.Instant;
import java.util.List;

/// Applies the ingest defaults for one event item (sdk-ingest spec §3.1,
/// §3.2) in the domain, so the singular create and one batch item persist
/// identically. `clientId` arrives already resolved (code lookup + tenant
/// guard are the API handler's job, spec §3.1) — this class only fills in
/// what the item itself left unspecified.
public final class EventIngestMapper {

    private EventIngestMapper() {
    }

    /// One inbound item, shape-identical for the singular and batch routes;
    /// the singular route has no `id`/`specVersion` field on the wire, so it
    /// always passes `null` for those.
    public record RawItem(
            String id,
            String specVersion,
            String type,
            String source,
            String subject,
            JsonNode data,
            String deduplicationId,
            String correlationId,
            String causationId,
            String messageGroup,
            String clientId,
            List<Event.ContextEntry> context) {
    }

    /// @throws UseCaseException validation `VALIDATION` "data is required" when `data` is absent/`null`
    public static Event toEvent(RawItem it) {
        if (it.data() == null || it.data().isNull()) {
            throw UseCaseException.validation("VALIDATION", "data is required");
        }
        Instant now = Instant.now();
        String id = blank(it.id()) == null ? Tsid.generate() : it.id();
        String specVersion = blank(it.specVersion()) == null ? "1.0" : it.specVersion();
        // type/source are wire-optional on the batch item (no schema "required" — only `data` is
        // validated, spec §3.1); the aggregate's invariant forbids null, matching the NOT NULL
        // columns, so an absent value reads as "" rather than throwing (Go's zero-value behaviour).
        String type = it.type() == null ? "" : it.type();
        String source = it.source() == null ? "" : it.source();
        String deduplicationId = blank(it.deduplicationId()) == null
                ? type + "-" + Tsid.generate()
                : it.deduplicationId();
        return new Event(id, specVersion, type, source, it.subject(), now, it.data(), it.context(),
                deduplicationId, it.clientId(), it.messageGroup(), it.correlationId(), it.causationId(), now, null);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
