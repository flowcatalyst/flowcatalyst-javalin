package io.flowcatalyst.function;

import java.util.Objects;

/// A subscription/dispatch-job delivery's envelope, parsed by
/// [Webhook#event(Request)] from a `webhook`-endpoint [Request]'s body.
/// Field-for-field the wire shape the platform actually sends — read from
/// `platform/dispatchjob/processing/DeliveryPayload.build` (dispatch-seam
/// spec §5 "Delivery request construction"):
///
/// ```
/// {id, type, attemptNumber, source?, subject?, correlationId?,
///  messageGroup?, clientId?, clientCode?, data?}
/// ```
///
/// Only the shape of a **non-`dataOnly`** subscription's delivery — a
/// `dataOnly:true` subscription sends `job.payload` verbatim as the whole
/// body, which is not this envelope at all; a function declaring such an
/// endpoint parses [Request#body] itself instead of calling this method.
///
/// `dataJson` is the raw JSON text of the `data` member exactly as it
/// appeared on the wire (an object, array, string, number, boolean, or
/// absent) — never parsed further here, since this jar carries no JSON
/// library a function could otherwise be handed a parsed tree from; `null`
/// when `data` was absent.
///
/// @param id            the dispatch job's own id
/// @param type          the event type / dispatch job code
/// @param attemptNumber this delivery attempt, 1-based
/// @param source        the originating event's `source`, `null` when absent
/// @param subject       the originating event's `subject`, `null` when absent
/// @param correlationId links this delivery to the flow that caused it, `null` when absent
/// @param messageGroup  the ordering group this delivery belongs to, `null` when absent
/// @param clientId      the owning client's id, `null` for a platform-scoped job or an unresolved client
/// @param clientCode    the owning client's code, `null` under the same conditions as `clientId`
/// @param dataJson      the raw JSON text of `data`, `null` when absent
public record Event(
        String id,
        String type,
        int attemptNumber,
        String source,
        String subject,
        String correlationId,
        String messageGroup,
        String clientId,
        String clientCode,
        String dataJson) {

    public Event {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
    }
}
