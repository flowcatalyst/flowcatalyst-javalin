package io.flowcatalyst.function;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;

/// Parses a `webhook`-endpoint [Request]'s body into the envelope the
/// platform actually sent (`docs/spec/function-invocation.md` §7): a
/// subscription or direct dispatch job's delivery ([#event]), or a
/// scheduled job's firing ([#schedule]). Both shapes are read from the
/// server's own payload builders — see [Event] and [Schedule] for the wire
/// tables and their evidence.
///
/// @throws WebhookFormatException the body is not valid JSON, or not
///                                shaped like the envelope being parsed
public final class Webhook {

    private Webhook() {
    }

    /// Parses a subscription / direct-dispatch-job delivery body into an
    /// [Event]. Only valid for a `dataOnly:false` subscription — see
    /// [Event]'s class doc.
    public static Event event(Request request) {
        JsonValue.Obj obj = topLevelObject(request);
        return new Event(
                requireString(obj, "id"),
                requireString(obj, "type"),
                requireInt(obj, "attemptNumber"),
                optString(obj, "source"),
                optString(obj, "subject"),
                optString(obj, "correlationId"),
                optString(obj, "messageGroup"),
                optString(obj, "clientId"),
                optString(obj, "clientCode"),
                optRaw(obj, "data"));
    }

    /// Parses a scheduled-job firing body into a [Schedule].
    public static Schedule schedule(Request request) {
        JsonValue.Obj obj = topLevelObject(request);
        return new Schedule(
                requireString(obj, "jobId"),
                requireString(obj, "jobCode"),
                requireString(obj, "instanceId"),
                optInstant(obj, "scheduledFor"),
                requireInstant(obj, "firedAt"),
                requireString(obj, "triggerKind"),
                optString(obj, "correlationId"),
                optRaw(obj, "payload"),
                requireBool(obj, "tracksCompletion"),
                optInt(obj, "timeoutSeconds"),
                requireBool(obj, "concurrent"));
    }

    private static JsonValue.Obj topLevelObject(Request request) {
        String text = new String(request.body(), StandardCharsets.UTF_8);
        JsonValue root = WebhookJson.parse(text);
        if (!(root instanceof JsonValue.Obj obj)) {
            throw new WebhookFormatException("webhook body must be a JSON object");
        }
        return obj;
    }

    private static String requireString(JsonValue.Obj obj, String key) {
        JsonValue v = obj.members().get(key);
        if (!(v instanceof JsonValue.Str str)) {
            throw new WebhookFormatException("missing or non-string field '" + key + "'");
        }
        return str.value();
    }

    private static String optString(JsonValue.Obj obj, String key) {
        JsonValue v = obj.members().get(key);
        if (v == null || v instanceof JsonValue.Null) {
            return null;
        }
        if (!(v instanceof JsonValue.Str str)) {
            throw new WebhookFormatException("field '" + key + "' must be a string");
        }
        return str.value();
    }

    private static boolean requireBool(JsonValue.Obj obj, String key) {
        JsonValue v = obj.members().get(key);
        if (!(v instanceof JsonValue.Bool b)) {
            throw new WebhookFormatException("missing or non-boolean field '" + key + "'");
        }
        return b.value();
    }

    private static int requireInt(JsonValue.Obj obj, String key) {
        JsonValue v = obj.members().get(key);
        if (!(v instanceof JsonValue.Num num)) {
            throw new WebhookFormatException("missing or non-numeric field '" + key + "'");
        }
        return parseInt(key, num);
    }

    private static Integer optInt(JsonValue.Obj obj, String key) {
        JsonValue v = obj.members().get(key);
        if (v == null || v instanceof JsonValue.Null) {
            return null;
        }
        if (!(v instanceof JsonValue.Num num)) {
            throw new WebhookFormatException("field '" + key + "' must be a number");
        }
        return parseInt(key, num);
    }

    private static int parseInt(String key, JsonValue.Num num) {
        try {
            return Integer.parseInt(num.raw());
        } catch (NumberFormatException e) {
            throw new WebhookFormatException("field '" + key + "' is not an integer: " + num.raw(), e);
        }
    }

    private static String optRaw(JsonValue.Obj obj, String key) {
        JsonValue v = obj.members().get(key);
        if (v == null || v instanceof JsonValue.Null) {
            return null;
        }
        return v.raw();
    }

    private static Instant requireInstant(JsonValue.Obj obj, String key) {
        return parseInstant(key, requireString(obj, key));
    }

    private static Instant optInstant(JsonValue.Obj obj, String key) {
        String raw = optString(obj, key);
        return raw == null ? null : parseInstant(key, raw);
    }

    private static Instant parseInstant(String key, String raw) {
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException e) {
            throw new WebhookFormatException("field '" + key + "' is not a valid timestamp: " + raw, e);
        }
    }
}
