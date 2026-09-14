# Structured logging — the JSON line shape

Status: spec + owner ruling 2026-09-14 ("fix the logs": Java adopts Go's
shape). Supersedes the "the log shape is not a contract" note in
`Logging.java`. Applies to every JSON log line the Java binaries write
(`fc-server`, `fcdev`, the router role) when `FC_LOG_FORMAT=json` or when
stderr is not a terminal. The text format is unchanged.

## 0. Why

The observability audit (`docs/audit/2026-09-14-observability-parity.md`
§2) found every concept logged in a different shape: Go writes flat slog
JSON; Java wrote logback's `JsonEncoder` output (`timestamp` epoch ms,
`formattedMessage`, key-values nested as an array of one-key objects under
`kvpList`, `mdc`, `throwable`). A CloudWatch query that reads a field on
one side cannot read it on the other. Go's shape is the better one for the
people who read logs: fields at the top level, one line per event, nothing
to index into.

## 1. The line

One JSON object per event, UTF-8, terminated by `\n`, keys in this order:

| Key | Value | Notes |
|---|---|---|
| `time` | `2026-09-14T16:45:31.145506+01:00` | RFC 3339 in the process's zone, **six** fractional digits, `Z` when the offset is zero — what Go's `slog.NewJSONHandler` emits |
| `level` | `DEBUG` \| `INFO` \| `WARN` \| `ERROR` | logback `TRACE` maps to `DEBUG` (slog has no trace) |
| `msg` | the formatted message | |
| *MDC entries* | flat, key as put (`correlation_id`, `principal_id`, …) | in MDC iteration order; absent when the MDC is empty |
| *key-value pairs* | flat, in call order (`.addKeyValue(k, v)`) | see §2 for value rendering |
| `logger` | the logger name | Java-only superset key; Go has no equivalent |
| `thread` | the thread name | Java-only superset key |
| `err` | `Class: message` of the throwable (`toString()`), when one is attached | Go's own convention for an error attribute is the key `err` holding the error string — this is the same key with the same meaning |
| `stack` | the throwable's stack trace, `\n`-joined, causes included (`Caused by:` frames) | Java-only superset key; absent without a throwable |

Nothing else: no `timestamp`, `formattedMessage`, `kvpList`, `mdc`,
`throwable`, `threadName`, `loggerName`, sequence number or context name.

## 2. Values

- `String` → JSON string, escaped per RFC 8259 (`"` `\` and control
  characters below U+0020 as `\uXXXX` or the short forms `\n` `\r` `\t`
  `\b` `\f`).
- `Number` → JSON number as `toString()` (`Double`/`Float` NaN and
  infinities as strings); `Boolean` → `true`/`false`; `null` → `null`.
- Anything else → its `toString()` as a JSON string (a `Duration` is
  `PT1.5S`, an `Instant` its ISO form — the same as today's `%kvp`).
- Keys are emitted as given. A key that collides with a reserved key
  (`time`, `level`, `msg`, `logger`, `thread`, `err`, `stack`) or with an
  earlier MDC entry is written as `kv_<key>` so no line ever has a
  duplicate key and the reserved meaning is never overwritten.

## 3. Implementation

`io.flowcatalyst.server.GoJsonEncoder` (name it for what it matches), a
logback `Encoder<ILoggingEvent>` built by hand — a `StringBuilder` and one
escaping routine — no Jackson, no reflection, nothing that touches startup
time. `Logging.jsonEncoder` returns it; the text encoder is untouched.
The header of `Logging.java` says the shape IS a contract now and points
here.

## 4. Consumers that parse Java's lines

- `e2e/runner/mail.ts` (`parseMailLog`) reads the "mail logged instead of
  sent" line to find invite/reset links: Java's line becomes
  `{"time":…,"level":"INFO","msg":"SMTP not configured; mail logged instead
  of sent","to":…,"subject":…,"body":…,"logger":…}` — the same flat shape
  the Go branch already parses. Update the parser and its unit test
  (`e2e/runner/__tests__`); `pnpm test:unit` in `e2e/` must stay green and
  the browser suite must still find mails on both sides.
- `docs/spec/frontend-e2e.md` §4 and `docs/spec/mail-outbox.md` wherever
  they describe the Java line: correct the field names.
- The parity harness only captures server logs; it parses nothing.

## 5. Tests (`GoJsonEncoderTest`; break each once)

| Assertion | Mutant |
|---|---|
| a plain INFO line is exactly `{"time":…,"level":"INFO","msg":"hello","logger":"x","thread":"main"}` — parsed keys in that order, no other keys | nested/extra keys |
| `.addKeyValue("queue","q1").addKeyValue("attempts",3).addKeyValue("ok",true).addKeyValue("gone",null)` → `"queue":"q1","attempts":3,"ok":true,"gone":null` in that order, before `logger` | wrong types or order |
| MDC `correlation_id=abc` → top-level `"correlation_id":"abc"` before the key-values, and gone once the MDC is cleared | MDC nested or leaked |
| `.setCause(new IllegalStateException("boom", new IOException("io")))` → `"err":"java.lang.IllegalStateException: boom"` and `stack` containing `at ` frames and `Caused by: java.io.IOException: io` | err/stack missing |
| `time` matches `^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{6}(Z\|[+-]\d\d:\d\d)$` and parses to within a second of now | wrong format/zone |
| TRACE → `DEBUG`; WARN, ERROR unchanged | level mapping |
| a message with `"`, `\`, a newline and U+0001 round-trips through a JSON parser unchanged | escaping |
| a key-value named `msg` is emitted as `kv_msg` and `msg` still holds the message | collision |
| end to end: `Logging.init(INFO, JSON)`, log through slf4j, capture stderr: one line, parses, has none of the old keys (`timestamp`, `formattedMessage`, `kvpList`, `mdc`, `throwable`) | encoder not wired |
