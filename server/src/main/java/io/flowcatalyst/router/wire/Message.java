package io.flowcatalyst.router.wire;

import com.fasterxml.jackson.annotation.JsonInclude;

/// The JSON published to and consumed from every queue backend — an SQS body,
/// a Postgres `payload` column, a NATS message payload.
///
/// **This shape is a contract [C]** (`docs/spec/router.md` §2.1). Go and Java
/// never run at once, but a rollback leaves Java-written messages on a queue
/// for Go to drain, so the emitted bytes must stay compatible in both
/// directions. Two Go `encoding/json` behaviours are therefore reproduced
/// deliberately:
///
///   - Fields tagged `omitempty` on a **value** are dropped when zero —
///     `poolCode` when empty, `highPriority` when false. Hence the per-field
///     [JsonInclude] overrides: the platform's mapper defaults to
///     `NON_ABSENT`, which would emit `""` and `false`.
///   - Fields tagged `omitempty` on a **pointer** are dropped only when nil,
///     so an explicit empty string is still emitted. Those are modelled as
///     nullable components and left on the mapper's `NON_ABSENT` default,
///     which drops exactly `null`. `authToken` relies on this: present but
///     empty means `Authorization: Bearer ` with an empty token, which is
///     distinguishable from absent.
///
/// [#dispatchMode] is always emitted. Go omitted it until the scheduler
/// began setting it (`docs/spec/dispatch-propagation.md`), and an absent
/// value parses to [DispatchMode#IMMEDIATE] on the way in, so always writing
/// it is compatible in both directions and removes a silent default.
///
/// @param id              application message id — a TSID or dispatch-job id.
///                        The dedup key in the in-flight tracker, and the
///                        sole content of the delivered body:
///                        `{"messageId": "<id>"}`
/// @param poolCode        pool to process in. Empty or unknown routes to the
///                        router's fallback pool. Namespaced
///                        `{clientIdentifier}-{poolCode}` by the producer,
///                        and **opaque** — never split it back apart
/// @param authToken       sent as `Authorization: Bearer <authToken>` when
///                        non-null, **including when empty**
/// @param signingSecret   when non-null the request is HMAC-signed; see
///                        [WebhookSigner]
/// @param mediationType   only [MediationType.Http] is deliverable; anything
///                        else is ACK-dropped with a diagnostic
/// @param mediationTarget absolute URL POSTed to, and the circuit-breaker key
/// @param messageGroupId  FIFO group. Honoured only when [#dispatchMode]
///                        requires ordering
/// @param highPriority    carried and never acted on — the Go router reads it
///                        nowhere (`docs/spec/router.md` §2.1, §13 Q14)
/// @param dispatchMode    never null via [#dispatchMode()]: an absent or
///                        unrecognised wire value normalises to
///                        [DispatchMode#DEFAULT]. The record component
///                        itself stays raw (nullable) so
///                        [#dispatchModeSpecified()] can tell "the wire
///                        carried no value at all" from "the wire carried a
///                        value that normalised" — see the accessor and
///                        [#dispatchModeSpecified()] below.
public record Message(
        String id,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) String poolCode,
        String authToken,
        String signingSecret,
        MediationType mediationType,
        String mediationTarget,
        String messageGroupId,
        @JsonInclude(JsonInclude.Include.NON_DEFAULT) boolean highPriority,
        DispatchMode dispatchMode) {

    /// Normalises the one field that must never be null in the JVM, so no
    /// caller has to defend against a wire value that was simply absent.
    /// Empty-string-as-absent stays at the wire boundary and does not leak
    /// inward (CONVENTIONS §8).
    ///
    /// `dispatchMode` is deliberately **not** normalised here — see
    /// [#dispatchMode()].
    public Message {
        mediationType = mediationType == null ? MediationType.HTTP : mediationType;
    }

    /// Never null: an absent or unrecognised wire value normalises to
    /// [DispatchMode#DEFAULT].
    ///
    /// This overrides the record's implicit accessor deliberately: the
    /// canonical field stays raw (null when the wire carried no
    /// `dispatchMode` at all, non-null whenever it carried any value, even a
    /// blank or unrecognised one — [DispatchMode#parse] never returns null).
    /// Normalising here rather than in the compact constructor is what makes
    /// [#dispatchModeSpecified()] possible without a tenth record component,
    /// which would break every positional `new Message(...)` call site
    /// outside this unit's ownership (`docs/spec/router-completion.md`
    /// unit 3). `equals`/`hashCode`/`toString` are therefore based on the
    /// **raw** value, not this normalised one — this record does not
    /// compare a wire-absent message equal to one explicitly constructed
    /// with the default, which nothing in this codebase relies on today.
    public DispatchMode dispatchMode() {
        return dispatchMode == null ? DispatchMode.DEFAULT : dispatchMode;
    }

    /// Whether the wire carried a `dispatchMode` value at all — malformed
    /// under the strict-routing gate when it did not (R-13/R-16,
    /// `docs/spec/router-completion.md` unit 3; `docs/spec/router.md` §2.3).
    ///
    /// True for **any** wire value, including blank or unrecognised ones —
    /// those still parse to [DispatchMode#DEFAULT] (with a warning for the
    /// unrecognised case) and are not what the strict gate calls malformed;
    /// only genuine absence (no key, or an explicit JSON `null`) is.
    public boolean dispatchModeSpecified() {
        return dispatchMode != null;
    }

    /// The message group, or `""` when ungrouped.
    ///
    /// One accessor for every group decision in the router. The Go grew three
    /// hand-rolled nil-checks that `eff2a29` had to collapse onto a single
    /// helper; starting from one removes that class of drift. `""` is the
    /// ungrouped sentinel *within the router's group logic only* — it is what
    /// makes "ungrouped is a no-op" expressible in the flush registry and the
    /// group queues.
    public String groupId() {
        return messageGroupId == null ? "" : messageGroupId;
    }

    /// Whether this message must be sequenced within [#groupId()].
    /// Ungrouped messages are never ordered, whatever the mode claims.
    public boolean ordered() {
        return dispatchMode().requiresOrdering() && !groupId().isEmpty();
    }

    /// The exact bytes POSTed to the target, and the bytes that are signed.
    ///
    /// Built by hand rather than through the shared mapper because the body
    /// is a fixed one-field object whose byte-for-byte form is pinned by the
    /// golden vector: no whitespace, and the id escaped the way Go's
    /// `encoding/json` escapes it.
    public byte[] deliveryBody() {
        return ("{\"messageId\":" + JsonStrings.quote(id) + "}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
