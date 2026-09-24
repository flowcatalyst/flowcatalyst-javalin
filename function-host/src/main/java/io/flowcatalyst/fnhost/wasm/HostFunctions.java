package io.flowcatalyst.fnhost.wasm;

import io.flowcatalyst.function.EventEmitException;
import io.flowcatalyst.function.Events;
import io.flowcatalyst.function.OutboundEvent;
import io.flowcatalyst.function.Secrets;
import io.flowcatalyst.platform.shared.json.Json;
import org.extism.sdk.chicory.CurrentPlugin;
import org.extism.sdk.chicory.ExtismHostFunction;
import org.extism.sdk.chicory.ExtismValType;
import org.extism.sdk.chicory.ExtismValueList;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Set;

/// The FlowCatalyst host functions in `extism:host/user`
/// (`docs/spec/function-wasm-runtime.md` §4) — the ones Extism does not
/// already provide. Each takes and returns an Extism memory offset (`i64`);
/// every failure is a value the guest reads, never a trap.
///
/// | Name | In | Out |
/// |---|---|---|
/// | `fc_secret_get` | the key | the value, or empty (offset `0`) when the key is not one the manifest declares or has no value |
/// | `fc_emit_event` | [OutboundEvent]'s shape as JSON | `{"ok":true}` or `{"ok":false,"error":"<code or why>"}` |
///
/// Neither logs anything: a secret value must never reach a log line, and an
/// emit's outcome is the guest's to report.
final class HostFunctions {

    /// The names a module may import from `extism:host/user` — anything else
    /// there could never link, so the loader refuses it up front.
    static final Set<String> USER_NAMESPACE_NAMES = Set.of("fc_secret_get", "fc_emit_event");

    private HostFunctions() {
    }

    /// Fresh host-function objects for ONE plugin instance: the ported SDK
    /// binds each [ExtismHostFunction] to exactly one instance and refuses a
    /// second bind, so they are never shared.
    static ExtismHostFunction[] forInstance(Secrets secrets, Set<String> declaredSecrets, Events events) {
        return new ExtismHostFunction[] {
                ExtismHostFunction.of("fc_secret_get", List.of(ExtismValType.I64), List.of(ExtismValType.I64),
                        (plugin, args, returns) -> secretGet(plugin, args, returns, secrets, declaredSecrets)),
                ExtismHostFunction.of("fc_emit_event", List.of(ExtismValType.I64), List.of(ExtismValType.I64),
                        (plugin, args, returns) -> emitEvent(plugin, args, returns, events)),
        };
    }

    private static void secretGet(CurrentPlugin plugin, ExtismValueList args, ExtismValueList returns,
                                  Secrets secrets, Set<String> declaredSecrets) {
        String key = plugin.memory().readString(args.getLong(0));
        String value = declaredSecrets.contains(key) ? secrets.get(key).orElse(null) : null;
        returns.setLong(0, value == null || value.isEmpty() ? 0 : plugin.memory().writeString(value));
    }

    private static void emitEvent(CurrentPlugin plugin, ExtismValueList args, ExtismValueList returns,
                                  Events events) {
        byte[] json = plugin.memory().readBytes(args.getLong(0));
        ObjectNode answer = Json.MAPPER.createObjectNode();
        String error = emit(json, events);
        answer.put("ok", error == null);
        if (error != null) {
            answer.put("error", error);
        }
        returns.setLong(0, plugin.memory().writeBytes(Json.MAPPER.writeValueAsBytes(answer)));
    }

    /// `null` on success, else what the guest is told.
    private static String emit(byte[] json, Events events) {
        OutboundEvent event;
        try {
            JsonNode node = Json.MAPPER.readTree(json);
            if (!node.isObject()) {
                return "INVALID_EVENT: not a JSON object";
            }
            String type = text(node, "type");
            String dedupId = text(node, "dedupId");
            if (type == null || type.isBlank()) {
                return "INVALID_EVENT: type is required";
            }
            if (dedupId == null || dedupId.isBlank()) {
                return "DEDUP_ID_REQUIRED";
            }
            JsonNode data = node.path("data");
            byte[] dataBytes = data.isMissingNode() || data.isNull()
                    ? new byte[0]
                    : Json.MAPPER.writeValueAsBytes(data);
            event = new OutboundEvent(type, text(node, "source"), text(node, "subject"),
                    text(node, "dataContentType"), dataBytes, text(node, "correlationId"),
                    text(node, "causationId"), text(node, "messageGroup"), dedupId);
        } catch (JacksonException e) {
            return "INVALID_EVENT: not JSON";
        }
        try {
            events.emit(event);
            return null;
        } catch (EventEmitException e) {
            return e.code();
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return "EMIT_FAILED";
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isString() ? value.asString() : null;
    }
}
