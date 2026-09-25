package io.flowcatalyst.router.api;

import io.flowcatalyst.platform.shared.dispatch.DispatchMode;
import io.flowcatalyst.router.api.RouterApi.State;
import io.flowcatalyst.router.queue.Publisher;
import io.flowcatalyst.router.wire.MediationType;
import io.flowcatalyst.router.wire.Message;
import io.flowcatalyst.http.Exchange;
import io.flowcatalyst.http.Routes;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/// Manual/test message injection (`docs/spec/router.md` §9.1): `POST
/// /messages` publishes one message, `POST /api/seed/messages` publishes a
/// batch of IMMEDIATE ones at a mock target. Both reuse
/// `s.manager().publisher(key)` to reach the SAME broker connection a poll
/// loop reads from — no separate publisher pool is opened here.
///
/// Both default `dispatch_mode` to [DispatchMode#IMMEDIATE] **explicitly**,
/// never by leaving [Message#dispatchMode] raw-null: [Message#dispatchMode()]'s
/// generic fallback is [DispatchMode#DEFAULT] (`NEXT_ON_ERROR`), which is
/// the right default for a message arriving with no opinion at all, but the
/// wrong one for this endpoint — the spec is explicit that an unspecified
/// `dispatch_mode` here means IMMEDIATE, not "assume ordering matters".
final class MessageRoutes {

    /// `POST /api/seed/messages` default mediation target (§5 #57) — one of
    /// the always-on mock endpoints [MockRoutes] serves.
    private static final String DEFAULT_SEED_TARGET = "https://localhost:8080/api/test/fast";

    private static final int SEED_MAX_COUNT = 10_000;

    /// Mounts `POST /messages`. Called by [RouterApi#register].
    static void register(Routes routes, State s) {
        routes.post(s.prefix() + "/messages", ctx -> publishMessage(ctx, s));
    }

    /// Mounts `POST /api/seed/messages`, in dev mode only
    /// (`docs/spec/router-api-auth.md` rule 7). Called by [RouterApi#registerDevRoutes].
    static void registerDev(Routes routes, State s) {
        routes.post(s.prefix() + "/api/seed/messages", ctx -> seedMessages(ctx, s));
    }

    /// `POST /messages` — 201 on success; 422 on a missing required field;
    /// 503 when no queue is registered or the resolved queue's backend has
    /// no [Publisher]; 502 when the publish itself fails.
    private static void publishMessage(Exchange ctx, State s) {
        if (s.manager() == null) {
            Http.serviceUnavailable(ctx, "publisher not configured");
            return;
        }
        var req = ctx.bodyAsClass(Wire.PublishMessageRequest.class);
        if (blank(req.poolCode())) {
            Http.unprocessable(ctx, "pool_code is required");
            return;
        }
        if (blank(req.mediationTarget())) {
            Http.unprocessable(ctx, "mediation_target is required");
            return;
        }
        var publisher = s.manager().publisher(req.poolCode()).orElse(null);
        if (publisher == null) {
            Http.serviceUnavailable(ctx, "publisher not configured");
            return;
        }
        var message = toMessage(blank(req.id()) ? UUID.randomUUID().toString() : req.id(), req);
        String brokerId;
        try {
            brokerId = publisher.publish(message);
        } catch (Publisher.PublishException e) {
            Http.badGateway(ctx, "publish: " + e.getMessage());
            return;
        }
        ctx.status(201).json(new Wire.PublishMessageResponse(
                message.id(), brokerId, message.poolCode(), publisher.identifier()));
    }

    /// `POST /api/seed/messages` — every published message is
    /// [DispatchMode#IMMEDIATE] at `mediation_target` (default the `fast`
    /// mock), so a burst of `count` published messages drains as fast as the
    /// pool's concurrency allows rather than waiting on ordering.
    private static void seedMessages(Exchange ctx, State s) {
        if (s.manager() == null) {
            Http.serviceUnavailable(ctx, "publisher not configured");
            return;
        }
        var req = ctx.bodyAsClass(Wire.SeedMessagesRequest.class);
        if (blank(req.poolCode())) {
            Http.unprocessable(ctx, "pool_code is required");
            return;
        }
        if (req.count() < 1 || req.count() > SEED_MAX_COUNT) {
            Http.unprocessable(ctx, "count must be between 1 and " + SEED_MAX_COUNT);
            return;
        }
        var publisher = s.manager().publisher(req.poolCode()).orElse(null);
        if (publisher == null) {
            Http.serviceUnavailable(ctx, "publisher not configured");
            return;
        }
        String target = blank(req.mediationTarget()) ? DEFAULT_SEED_TARGET : req.mediationTarget();
        List<Message> messages = new ArrayList<>(req.count());
        for (int i = 0; i < req.count(); i++) {
            messages.add(new Message(UUID.randomUUID().toString(), req.poolCode(), null, null,
                    MediationType.HTTP, target, null, false, DispatchMode.IMMEDIATE));
        }
        List<String> ids;
        try {
            ids = publisher.publishBatch(messages);
        } catch (Publisher.PublishException e) {
            Http.badGateway(ctx, "publish batch: " + e.getMessage());
            return;
        }
        ctx.json(new Wire.SeedMessagesResponse(req.poolCode(), publisher.identifier(), ids.size()));
    }

    /// The wire request to the [Message] a [Publisher] takes. `mediationType`
    /// is left raw-null on a blank wire value rather than resolved here — the
    /// record's own compact constructor defaults a null [MediationType] to
    /// HTTP, which is the one normalisation this handler can lean on safely.
    /// `dispatchMode` gets no such help (see the class doc) and is always
    /// resolved to a concrete value before the [Message] is built.
    private static Message toMessage(String id, Wire.PublishMessageRequest req) {
        MediationType mediationType = blank(req.mediationType()) ? null : MediationType.parse(req.mediationType());
        DispatchMode dispatchMode = blank(req.dispatchMode()) ? DispatchMode.IMMEDIATE : DispatchMode.parse(req.dispatchMode());
        String groupId = blank(req.messageGroupId()) ? null : req.messageGroupId();
        return new Message(id, req.poolCode(), req.authToken(), req.signingSecret(), mediationType,
                req.mediationTarget(), groupId, req.highPriority(), dispatchMode);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private MessageRoutes() {
    }
}
