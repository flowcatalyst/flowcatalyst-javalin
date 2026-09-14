package io.flowcatalyst.server;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import ch.qos.logback.core.encoder.EncoderBase;
import org.slf4j.event.KeyValuePair;

import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/// A logback `Encoder<ILoggingEvent>`, hand-built, that emits the same flat
/// JSON shape as Go's `slog.NewJSONHandler` — `docs/spec/logging.md`, the
/// contract for every JSON log line either platform writes. No Jackson, no
/// reflection: one `StringBuilder` and one escaping routine, so it costs
/// nothing at startup.
///
/// Key order (spec §1): `time`, `level`, `msg`, the MDC (in MDC iteration
/// order), the key-value pairs (in `.addKeyValue(...)` call order), then the
/// Java-only superset keys `logger`, `thread`, `err`, `stack`. A key that
/// collides with a reserved key or an earlier MDC/key-value entry is
/// rewritten `kv_<key>` (spec §2) so no line ever repeats a key.
public final class GoJsonEncoder extends EncoderBase<ILoggingEvent> {

    private static final Set<String> RESERVED =
            Set.of("time", "level", "msg", "logger", "thread", "err", "stack");

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX");

    @Override
    public byte[] headerBytes() {
        return null;
    }

    @Override
    public byte[] footerBytes() {
        return null;
    }

    @Override
    public byte[] encode(ILoggingEvent event) {
        var sb = new StringBuilder(256);
        sb.append('{');

        appendField(sb, true, "time", TIME_FORMAT.format(event.getInstant().atZone(ZoneId.systemDefault())));
        appendField(sb, false, "level", levelOf(event.getLevel()));
        appendField(sb, false, "msg", event.getFormattedMessage());

        var seen = new HashSet<>(RESERVED);

        Map<String, String> mdc = event.getMDCPropertyMap();
        if (mdc != null) {
            for (Map.Entry<String, String> entry : mdc.entrySet()) {
                appendField(sb, false, keyFor(entry.getKey(), seen), entry.getValue());
            }
        }

        List<KeyValuePair> kvps = event.getKeyValuePairs();
        if (kvps != null) {
            for (KeyValuePair kv : kvps) {
                appendField(sb, false, keyFor(kv.key, seen), kv.value);
            }
        }

        appendField(sb, false, "logger", event.getLoggerName());
        appendField(sb, false, "thread", event.getThreadName());

        IThrowableProxy throwable = event.getThrowableProxy();
        if (throwable != null) {
            appendField(sb, false, "err", errOf(throwable));
            appendField(sb, false, "stack", stackOf(throwable));
        }

        sb.append('}').append('\n');
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /// `TRACE` has no slog equivalent and maps to `DEBUG`; every other level
    /// is unchanged.
    private static String levelOf(Level level) {
        return level == Level.TRACE ? "DEBUG" : level.toString();
    }

    /// A key that repeats a reserved key or an earlier MDC/key-value entry
    /// is rewritten `kv_<key>`; either way the raw key is recorded so a
    /// third occurrence of the same name collides too.
    private static String keyFor(String rawKey, Set<String> seen) {
        String key = seen.contains(rawKey) ? "kv_" + rawKey : rawKey;
        seen.add(rawKey);
        return key;
    }

    /// `Class: message`, the same text `Throwable.toString()` produces —
    /// built from the proxy rather than the live throwable since that is
    /// all `ILoggingEvent` carries after the fact.
    private static String errOf(IThrowableProxy proxy) {
        return proxy.getMessage() != null
                ? proxy.getClassName() + ": " + proxy.getMessage()
                : proxy.getClassName();
    }

    /// The throwable's stack trace, `\n`-joined, causes included as
    /// `Caused by: ...` sections — the same text `printStackTrace()` writes,
    /// without the trailing tab indentation.
    private static String stackOf(IThrowableProxy proxy) {
        var sb = new StringBuilder();
        appendThrowable(sb, proxy, false);
        return sb.toString();
    }

    private static void appendThrowable(StringBuilder sb, IThrowableProxy proxy, boolean isCause) {
        if (isCause) {
            sb.append('\n').append("Caused by: ");
        }
        sb.append(errOf(proxy));
        for (StackTraceElementProxy step : proxy.getStackTraceElementProxyArray()) {
            sb.append('\n').append(step.getSTEAsString());
        }
        if (proxy.getCause() != null) {
            appendThrowable(sb, proxy.getCause(), true);
        }
    }

    private static void appendField(StringBuilder sb, boolean first, String key, Object value) {
        if (!first) {
            sb.append(',');
        }
        sb.append('"');
        escape(sb, key);
        sb.append("\":");
        appendValue(sb, value);
    }

    /// Value rendering (spec §2): `String` → escaped JSON string; `Number` →
    /// its `toString()`, except a NaN/infinite `Double`/`Float` which is not
    /// valid JSON and is rendered as a string instead; `Boolean` → bare
    /// `true`/`false`; `null` → bare `null`; anything else → its
    /// `toString()` as a JSON string.
    private static void appendValue(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            sb.append('"');
            escape(sb, s);
            sb.append('"');
        } else if (value instanceof Boolean b) {
            sb.append(b.booleanValue());
        } else if (value instanceof Double d) {
            if (d.isNaN() || d.isInfinite()) {
                sb.append('"').append(d).append('"');
            } else {
                sb.append((double) d);
            }
        } else if (value instanceof Float f) {
            if (f.isNaN() || f.isInfinite()) {
                sb.append('"').append(f).append('"');
            } else {
                sb.append((float) f);
            }
        } else if (value instanceof Number n) {
            sb.append(n);
        } else {
            sb.append('"');
            escape(sb, value.toString());
            sb.append('"');
        }
    }

    /// RFC 8259 string escaping: `"` and `\` backslash-escaped, the named
    /// short forms for the common control characters, every other control
    /// character below U+0020 as a six-character unicode escape (backslash,
    /// u, four hex digits).
    private static void escape(StringBuilder sb, String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
    }
}
