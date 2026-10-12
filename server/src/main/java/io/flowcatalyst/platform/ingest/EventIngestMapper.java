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

    /// @throws UseCaseException validation `VALIDATION` "data is required" when `data` is absent/`null`,
    ///         "source is required" when the source is blank and [#eventSource] has no default
    public static Event toEvent(RawItem it) {
        if (it.data() == null || it.data().isNull()) {
            throw UseCaseException.validation("VALIDATION", "data is required");
        }
        Instant now = Instant.now();
        String id = blank(it.id()) == null ? Tsid.generate() : it.id();
        String specVersion = blank(it.specVersion()) == null ? "1.0" : it.specVersion();
        // type is wire-optional on the batch item (the batch handler refuses a blank one per
        // item); the aggregate's invariant forbids null, matching the NOT NULL column, so an
        // absent value reads as "" rather than throwing (Go's zero-value behaviour). An absent or
        // blank source is defaulted from the type (eventSource); with no application segment
        // there is none to store (before F14 the singular route stored "").
        String type = it.type() == null ? "" : it.type();
        String source = eventSource(it.source(), it.type());
        if (source == null) {
            throw UseCaseException.validation("VALIDATION", "source is required");
        }
        String deduplicationId = blank(it.deduplicationId()) == null
                ? type + "-" + Tsid.generate()
                : it.deduplicationId();
        return new Event(id, specVersion, type, source, it.subject(), now, it.data(), it.context(),
                deduplicationId, it.clientId(), it.messageGroup(), it.correlationId(), it.causationId(), now, null);
    }

    /// An event's source (owner ruling 2026-10-12, Go F14 `eventSource`;
    /// sdk-ingest spec §3.5): the one given when it is not blank, stored as
    /// sent; else the type's `{application}:{subdomain}`, its first two `:`
    /// segments of the trimmed type (`orders:sales:order:created` →
    /// `orders:sales`, the source every platform and SDK domain event
    /// carries), the first alone when there is no non-blank second
    /// (`orders` → `orders`). `null` when the type has no non-blank first
    /// segment either, which the caller refuses as "source is required".
    /// The Java, TypeScript and Laravel SDKs omit an unset source.
    public static String eventSource(String source, String eventType) {
        if (source != null && !source.isBlank()) return source;
        if (eventType == null) return null;
        String type = eventType.strip();
        int colon = type.indexOf(':');
        String application = colon < 0 ? type : type.substring(0, colon);
        if (application.isBlank()) return null;
        if (colon < 0) return application;
        String rest = type.substring(colon + 1);
        int next = rest.indexOf(':');
        String subdomain = next < 0 ? rest : rest.substring(0, next);
        return subdomain.isBlank() ? application : application + ":" + subdomain;
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
