package io.flowcatalyst.router.api;

import io.flowcatalyst.router.api.RouterApi.State;
import io.flowcatalyst.router.inflight.InFlightMessage;
import io.flowcatalyst.router.pool.Pool;
import io.flowcatalyst.router.pool.QueuedMessage;
import io.flowcatalyst.router.wire.Message;
import io.javalin.http.Context;
import io.javalin.router.JavalinDefaultRoutingApi;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/// What this process currently owns, and the operator override (§9.1).
///
/// The three statuses on `/detail` are the value of this group: `MEDIATING`
/// (inside a pool worker right now — the join between [Pool#mediating()] and
/// the tracker entry), `RETRY_BACKOFF` (attempts > 0 and not in a worker), and
/// `TRACKED_IDLE` (neither: buffered behind its ordered group, waiting for a
/// slot, **or** a phantom whose broker has stopped redelivering). The last is
/// the one an operator is hunting, and its signature is `lastSeenElapsedMs`
/// growing without bound.
final class InFlightRoutes {

    /// See `#forceAck`: paired with `brokerAcked:false` when
    /// [io.flowcatalyst.router.queue.Consumer#ack] reports the broker did
    /// **not** confirm the removal (still best-effort/never-throws, but now
    /// honestly observable — see the interface's own javadoc).
    private static final String BROKER_ACK_NOT_CONFIRMED =
            "broker did not confirm the removal; the message may still redeliver";

    /// Mounts this group. Called by [RouterApi#register].
    static void register(JavalinDefaultRoutingApi routes, State s) {
        var p = s.prefix();
        routes.get(p + "/monitoring/in-flight-messages", ctx -> inFlightList(ctx, s));
        routes.get(p + "/monitoring/in-flight-messages/check", ctx -> inFlightCheck(ctx, s));
        routes.get(p + "/monitoring/in-flight-messages/detail", ctx -> inFlightDetail(ctx, s));
        routes.post(p + "/monitoring/in-flight-messages/check-batch", ctx -> inFlightCheckBatch(ctx, s));
        routes.post(p + "/monitoring/in-flight-messages/{messageId}/ack", ctx -> forceAck(ctx, s));
    }

    private static void inFlightList(Context ctx, State s) {
        int limit = Http.queryInt(ctx, "limit", 100);
        if (limit <= 0) {
            limit = 100;
        }
        String idFilter = Http.queryParam(ctx, "messageId").toLowerCase(Locale.ROOT);
        String poolFilter = Http.queryParam(ctx, "poolCode");
        var now = Instant.now();
        var all = new ArrayList<Wire.InFlightMessageInfo>();
        for (var im : s.tracker().snapshot()) {
            if (!idFilter.isEmpty() && !im.messageId().toLowerCase(Locale.ROOT).contains(idFilter)) {
                continue;
            }
            if (!poolFilter.isEmpty() && !im.poolCode().equalsIgnoreCase(poolFilter)) {
                continue;
            }
            all.add(new Wire.InFlightMessageInfo(im.messageId(),
                    im.brokerMessageId().isEmpty() ? null : im.brokerMessageId(),
                    im.queueIdentifier(), im.poolCode(), Duration.between(im.startedAt(), now).toMillis(),
                    im.startedAt(), im.messageGroupId(), im.attempts()));
        }
        // Full filtered set sorted elapsed DESC, THEN truncated (spec note;
        // pins TestInFlightMessages_OrderedByElapsedDesc).
        all.sort(Comparator.comparingLong(Wire.InFlightMessageInfo::elapsedTimeMs).reversed());
        if (all.size() > limit) {
            all = new ArrayList<>(all.subList(0, limit));
        }
        ctx.json(all);
    }

    private static void inFlightCheck(Context ctx, State s) {
        String messageId = Http.queryParam(ctx, "messageId");
        for (var im : s.tracker().snapshot()) {
            if (im.messageId().equals(messageId)) {
                ctx.json(new Wire.InFlightCheckResponse(messageId, true, im.poolCode(), im.queueIdentifier()));
                return;
            }
        }
        ctx.json(new Wire.InFlightCheckResponse(messageId, false, null, null));
    }

    private static void inFlightCheckBatch(Context ctx, State s) {
        var body = ctx.bodyAsClass(Wire.InFlightCheckBatchRequest.class);
        Set<String> live = new HashSet<>();
        for (var im : s.tracker().snapshot()) {
            live.add(im.messageId());
        }
        Map<String, Boolean> out = new LinkedHashMap<>();
        for (var id : body.messageIds()) {
            out.put(id, live.contains(id));
        }
        ctx.json(out);
    }

    /// Force-ACK (spec: "operator override"). Order matches Go
    /// (`handlers_mutations.go: inFlightForceAck`): subsystem-absence (503)
    /// before not-tracked (404).
    ///
    /// `wasMediating` warns that a delivery attempt was **still running inside
    /// a worker** when the entry was cleared; that attempt finishes on its own
    /// and may still reach the target after this responds. It is read from
    /// [Pool#mediating()] — it was hard-coded `false` until 2026-08-26, which
    /// told every operator force-acking a genuinely wedged message that
    /// nothing was in flight for it.
    ///
    /// `brokerAcked`/`brokerAckError`: [io.flowcatalyst.router.queue.Consumer#ack]
    /// now returns whether the broker confirmed the removal (still
    /// best-effort/never-throws — see the interface's own javadoc, which
    /// names exactly this force-ack case as the reason). `brokerAcked` is
    /// the real outcome; `brokerAckError` is set only when it is `false`, so
    /// an operator is never told a delete is confirmed when it is not.
    private static void forceAck(Context ctx, State s) {
        if (s.manager() == null) {
            Http.serviceUnavailable(ctx, "in-flight ack not configured");
            return;
        }
        String messageId = ctx.pathParam("messageId");
        InFlightMessage entry = null;
        for (var im : s.tracker().snapshot()) {
            if (im.messageId().equals(messageId)) {
                entry = im;
                break;
            }
        }
        if (entry == null) {
            Http.notFound(ctx, "message not in pipeline: " + messageId);
            return;
        }
        var consumer = s.manager().consumer(entry.queueIdentifier());
        if (consumer.isEmpty()) {
            Http.serviceUnavailable(ctx, "no acker registered for queue " + entry.queueIdentifier());
            return;
        }
        var ackTarget = new QueuedMessage(
                new Message(entry.messageId(), entry.poolCode(), null, null, null, "", entry.messageGroupId(),
                        false, null),
                entry.brokerMessageId(), entry.receiptHandle(), entry.queueIdentifier(), entry.attempts());
        // Read BEFORE the ack: the worker may finish between the two, and an
        // operator who is told "nothing was running" because the race went the
        // other way has been told the one thing this flag exists to deny.
        boolean wasMediating = mediating(s, messageId).isPresent();
        boolean brokerAcked = consumer.get().ack(ackTarget);
        s.tracker().remove(entry.messageId());
        long elapsedMs = entry.elapsedSeconds(Instant.now()) * 1000;
        ctx.json(new Wire.ForceAckResponse(messageId, true, brokerAcked,
                brokerAcked ? null : BROKER_ACK_NOT_CONFIRMED, entry.queueIdentifier(), entry.poolCode(), elapsedMs,
                wasMediating));
    }

    /// `GET /monitoring/in-flight-messages/detail` — everything known about one
    /// tracked message.
    ///
    /// An unknown id is **not a 404**: `inPipeline:false` is the answer to
    /// "is it safe to resend this?", and the caller asking is usually asking
    /// precisely because it expects the answer to be no.
    private static void inFlightDetail(Context ctx, State s) {
        String messageId = Http.queryParam(ctx, "messageId");
        InFlightMessage entry = null;
        for (var im : s.tracker().snapshot()) {
            if (im.messageId().equals(messageId)) {
                entry = im;
                break;
            }
        }
        if (entry == null) {
            ctx.json(Wire.InFlightMessageDetail.notInPipeline(messageId));
            return;
        }
        ctx.json(inFlightDetail(entry, mediating(s, messageId).orElse(null), Instant.now()));
    }

    /// The tracker entry joined to the live worker view, separated from the
    /// HTTP so each status can be produced from state chosen to distinguish it.
    static Wire.InFlightMessageDetail inFlightDetail(InFlightMessage entry,
                                                io.flowcatalyst.router.pool.Mediating mediating, Instant now) {
        // Order matters: being inside a worker is the strongest fact, and a
        // message on its second attempt IS in a worker while it is being
        // retried — reporting RETRY_BACKOFF there would tell an operator it is
        // waiting when it is actually stuck against the target.
        String status = mediating != null ? "MEDIATING"
                : entry.retrying() ? "RETRY_BACKOFF"
                : "TRACKED_IDLE";
        return new Wire.InFlightMessageDetail(entry.messageId(), true, status,
                emptyToNull(entry.brokerMessageId()), entry.queueIdentifier(), entry.poolCode(),
                emptyToNull(entry.messageGroupId()), entry.attempts(),
                millisBetween(entry.startedAt(), now), entry.startedAt(),
                entry.lastSeenAt(), millisBetween(entry.lastSeenAt(), now),
                mediating == null ? null : mediating.target(),
                mediating == null ? null : millisBetween(mediating.startedAt(), now));
    }

    /// The live worker entry for a message, if any pool has one.
    private static java.util.Optional<io.flowcatalyst.router.pool.Mediating> mediating(State s, String messageId) {
        if (s.manager() == null) {
            return java.util.Optional.empty();
        }
        return s.manager().pools().values().stream()
                .flatMap(pool -> pool.mediating().stream())
                .filter(row -> row.messageId().equals(messageId))
                .findFirst();
    }

    /// Never negative: a clock that stepped backwards should read as "just
    /// now", not as a message delivered in the future.
    private static long millisBetween(Instant from, Instant to) {
        return Math.max(0, Duration.between(from, to).toMillis());
    }

    /// `""` → `null`, so Jackson drops the field the way Go's `omitempty`
    /// does on these two.
    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private InFlightRoutes() {
    }
}
