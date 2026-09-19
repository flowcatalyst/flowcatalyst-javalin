package io.flowcatalyst.function;

import java.util.List;
import java.util.Map;

/// The value model [WebhookJson] parses into. Package-private: never part
/// of the surface a function author sees — [Webhook#event] / [Webhook#schedule]
/// return typed [Event]/[Schedule] records, not this tree, and
/// `FunctionSignatureTest` only walks public signatures. Every variant
/// carries [#raw()], the exact source substring it was parsed from, which
/// is how [Event#dataJson] / [Schedule#payloadJson] recover the original
/// JSON text of a subtree without a serializer.
sealed interface JsonValue permits JsonValue.Obj, JsonValue.Arr, JsonValue.Str, JsonValue.Num, JsonValue.Bool,
        JsonValue.Null {

    String raw();

    record Obj(Map<String, JsonValue> members, String raw) implements JsonValue {
    }

    record Arr(List<JsonValue> items, String raw) implements JsonValue {
    }

    record Str(String value, String raw) implements JsonValue {
    }

    /// `raw` is the number's exact source text (`-0`, `1e10`, `3.14`, …) —
    /// kept as text, not a parsed `double`, so an integral field
    /// (`attemptNumber`, `timeoutSeconds`) can reject `"1.5"` instead of
    /// silently truncating it.
    record Num(String raw) implements JsonValue {
    }

    record Bool(boolean value) implements JsonValue {
        static final Bool TRUE = new Bool(true);
        static final Bool FALSE = new Bool(false);

        @Override
        public String raw() {
            return value ? "true" : "false";
        }
    }

    record Null() implements JsonValue {
        static final Null INSTANCE = new Null();

        @Override
        public String raw() {
            return "null";
        }
    }
}
