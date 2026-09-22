package io.flowcatalyst.example.hello;

import io.flowcatalyst.function.Caller;
import io.flowcatalyst.function.Event;
import io.flowcatalyst.function.EventEmitException;
import io.flowcatalyst.function.Function;
import io.flowcatalyst.function.FunctionContext;
import io.flowcatalyst.function.OutboundEvent;
import io.flowcatalyst.function.Request;
import io.flowcatalyst.function.Result;
import io.flowcatalyst.function.Webhook;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.UUID;

/// The reference function every developer reads first (`docs/functions.md`,
/// `docs/spec/function-developer-surface.md` §3). Deliberately small, but
/// exercises everything a real function needs to get right:
///
/// - a **`webhook`** endpoint (`/events/greeting-requested`) — parses the
///   platform's own delivery envelope with {@link Webhook#event}, reads a
///   config value and checks a secret's PRESENCE without ever logging its
///   VALUE, emits an event this function's own application owns through
///   {@link FunctionContext#events()}, and returns {@link Result#ack()} — or,
///   when the platform refuses the emit with a 5xx (a transport/availability
///   problem, not a rejection), {@link Result#retry(Duration)} instead of
///   failing outright (`function-api`'s own `Result` class doc: `fail()`
///   cannot force an immediate non-retryable failure either way, so a 4xx
///   rejection like `EVENT_TYPE_NOT_OWNED` is reported with
///   {@link Result#fail(String)} instead — retrying it would never succeed).
/// - a **`platform`** endpoint (`GET /api/hello/{name}`) — reads the path
///   parameter the host's route match bound, and the caller's principal id
///   from {@link Request#caller()}.
/// - a **`none`** endpoint (`GET /healthz`) — the host checks nothing; this
///   function trusts every caller for its own liveness probe.
public final class HelloFunction implements Function {

    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final String EVENT_TYPE = "hello:greeting:greeting:sent";

    @Override
    public void init(FunctionContext ctx) {
        ctx.logger().log(Level.INFO, "hello function starting up (address={0}, version={1})",
                ctx.address(), ctx.version());
    }

    @Override
    public Result handle(Request in, FunctionContext ctx) throws Exception {
        String path = in.path();
        if (path.equals("/healthz")) {
            return handleHealth();
        }
        if (path.equals("/events/greeting-requested")) {
            return handleGreetingRequested(in, ctx);
        }
        if (path.startsWith("/api/hello/")) {
            return handleHello(in);
        }
        // Unreachable in production: the host never delivers a path the manifest
        // does not declare an endpoint for (`function-host-listener.md` §2 step 4).
        return Result.fail("no route for path " + path);
    }

    private Result handleHealth() {
        return Result.json(200, "{\"status\":\"ok\"}");
    }

    private Result handleHello(Request in) {
        String name = in.pathParams().getOrDefault("name", "world");
        String principalId = principalIdOf(in.caller());
        ObjectNode body = JSON.createObjectNode();
        body.put("message", "hello, " + name + "!");
        body.put("principalId", principalId);
        return Result.json(200, JSON.writeValueAsString(body));
    }

    private static String principalIdOf(Caller caller) {
        return switch (caller) {
            case Caller.Principal p -> p.id();
            case Caller.Platform ignored -> "platform";
            case Caller.Anonymous ignored -> "anonymous";
        };
    }

    private Result handleGreetingRequested(Request in, FunctionContext ctx) throws Exception {
        Event event = Webhook.event(in);

        String greeting = ctx.config().get("GREETING").orElse("Hello");
        // Presence only — the value itself is never read into a log line, a
        // response body or the emitted event (`function-context.md` §1 X1).
        boolean apiKeyPresent = ctx.secrets().get("API_KEY").isPresent();
        ctx.logger().log(Level.INFO, "greeting requested (subject={0}, apiKeyPresent={1})",
                event.subject(), apiKeyPresent);

        String name = extractName(event.dataJson());
        GreetingPayload payload = new GreetingPayload(name, greeting + ", " + name + "!");
        byte[] data = JSON.writeValueAsBytes(payload);

        OutboundEvent outbound = new OutboundEvent(
                EVENT_TYPE,
                "function:" + ctx.address().render(),
                event.subject(),
                "application/json",
                data,
                event.correlationId(),
                event.id(),
                event.messageGroup(),
                UUID.randomUUID().toString());

        try {
            ctx.events().emit(outbound);
        } catch (EventEmitException e) {
            if (e.status() / 100 == 5) {
                // A transport/availability problem on the platform's side — worth
                // asking the delivery to come back (function-api's Result doc:
                // honoured by a subscription/direct dispatch job; a scheduled job
                // ignores the delay, per that same table).
                return Result.retry(Duration.ofSeconds(5));
            }
            // A genuine rejection (e.g. EVENT_TYPE_NOT_OWNED) — retrying would
            // never succeed, so this is reported, not retried.
            return Result.fail("event emit refused: " + e.code());
        }
        return Result.ack();
    }

    private static String extractName(String dataJson) {
        if (dataJson == null || dataJson.isBlank()) {
            return "world";
        }
        try {
            JsonNode node = JSON.readTree(dataJson);
            JsonNode nameNode = node.path("name");
            return nameNode.isString() ? nameNode.asString() : "world";
        } catch (RuntimeException e) {
            return "world";
        }
    }

    @Override
    public void stop() {
        // Nothing held across calls to release.
    }
}
