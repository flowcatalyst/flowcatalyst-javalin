package io.flowcatalyst.fnhost.wasm;

import io.flowcatalyst.function.EventEmitException;
import io.flowcatalyst.function.Events;
import io.flowcatalyst.function.OutboundEvent;
import io.flowcatalyst.function.Secrets;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.sdk.result.Result;
import io.flowcatalyst.sdk.result.Result.Err;
import io.flowcatalyst.sdk.result.Result.Ok;
import org.extism.sdk.chicory.CurrentPlugin;
import org.extism.sdk.chicory.ExtismHostFunction;
import org.extism.sdk.chicory.ExtismValType;
import org.extism.sdk.chicory.ExtismValueList;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
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
/// | `fc_db_query` | `{"db","sql","params":[…],"tx"?}` | `{"rows":[{col:value}],"truncated":bool}` |
/// | `fc_db_execute` | `{"db","sql","params":[…],"tx"?}` | `{"updated":n}` |
/// | `fc_db_begin` | `{"db"}` | `{"tx":"<opaque id>"}` |
/// | `fc_db_commit` / `fc_db_rollback` | `{"tx"}` | `{"ok":true}` |
///
/// Every `fc_db_*` failure is `{"error":{"code","message"}}` ([DbFailure]); the
/// state they act on is the call's own [DbSession] (`docs/spec/function-wasm-db.md`).
///
/// None logs anything: a secret value must never reach a log line, SQL text and
/// parameter values never either, and an outcome is the guest's to report.
final class HostFunctions {

    /// The names a module may import from `extism:host/user` — anything else
    /// there could never link, so the loader refuses it up front.
    static final Set<String> USER_NAMESPACE_NAMES = Set.of("fc_secret_get", "fc_emit_event",
            "fc_db_query", "fc_db_execute", "fc_db_begin", "fc_db_commit", "fc_db_rollback");

    /// Reads a guest's input JSON: decimals exact (a `numeric` parameter must not
    /// pass through a double on its way to the server).
    private static final ObjectReader DB_INPUT = Json.MAPPER.reader()
            .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

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
                db("fc_db_query", (session, in) -> session.query(in)),
                db("fc_db_execute", (session, in) -> session.execute(in)),
                db("fc_db_begin", (session, in) -> session.begin(in).map(HostFunctions::txAnswer)),
                db("fc_db_commit", (session, in) -> session.commit(in).map(ignored -> OK)),
                db("fc_db_rollback", (session, in) -> session.rollback(in).map(ignored -> OK)),
        };
    }

    private static final byte[] OK = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);

    /// One `fc_db_*` operation on the calling invocation's [DbSession].
    @FunctionalInterface
    private interface DbOperation {
        Result<byte[], DbFailure> apply(DbSession session, JsonNode in);
    }

    private static ExtismHostFunction db(String name, DbOperation operation) {
        return ExtismHostFunction.of(name, List.of(ExtismValType.I64), List.of(ExtismValType.I64),
                (plugin, args, returns) -> {
                    byte[] in = plugin.memory().readBytes(args.getLong(0));
                    byte[] answer = switch (dbCall(in, operation)) {
                        case Ok<byte[], DbFailure>(byte[] ok) -> ok;
                        case Err<byte[], DbFailure>(DbFailure failure) -> errorAnswer(failure);
                    };
                    returns.setLong(0, plugin.memory().writeBytes(answer));
                });
    }

    private static Result<byte[], DbFailure> dbCall(byte[] in, DbOperation operation) {
        if (!DbSession.CURRENT.isBound()) {
            return Result.err(new DbFailure.NoInvocation());
        }
        return dbInput(in).flatMap(node -> operation.apply(DbSession.CURRENT.get(), node));
    }

    /// A guest's `fc_db_*` input as a JSON object, decimals exact.
    static Result<JsonNode, DbFailure> dbInput(byte[] in) {
        JsonNode node;
        try {
            node = DB_INPUT.readTree(in);
        } catch (JacksonException e) {
            return Result.err(new DbFailure.BadRequest("the input is not JSON"));
        }
        if (node == null || !node.isObject()) {
            return Result.err(new DbFailure.BadRequest("the input must be a JSON object"));
        }
        return Result.ok(node);
    }

    private static byte[] txAnswer(String id) {
        ObjectNode answer = Json.MAPPER.createObjectNode();
        answer.put("tx", id);
        return Json.MAPPER.writeValueAsBytes(answer);
    }

    static byte[] errorAnswer(DbFailure failure) {
        ObjectNode answer = Json.MAPPER.createObjectNode();
        ObjectNode error = answer.putObject("error");
        error.put("code", failure.code());
        error.put("message", failure.message());
        return Json.MAPPER.writeValueAsBytes(answer);
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
