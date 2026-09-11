/// Reads mail back out of a captured server log (spec §4): neither side
/// sends real mail in the e2e run (no `FC_SMTP_HOST`/`SMTP_HOST`), so both
/// log the message they would have sent — Go's `email.LogService` and
/// Java's `MailService.logging()` — and this is the one place that knows
/// how to find a link in that log.
///
/// The two sides log the *same marker line* but in different shapes,
/// because they come from different logging stacks:
///
///  - Go (`slog` JSON handler) writes `to`/`subject`/`body` as separate
///    top-level JSON fields alongside `"msg"`.
///  - Java (Logback's `JsonEncoder`, `withFormattedMessage(true)`) writes
///    one `"formattedMessage"` field with the SLF4J `{}` placeholders
///    already substituted in-line — `to=… subject=… body=…` — because the
///    call site is a plain parameterised `LOG.warn(...)`, not a structured
///    key/value API.
///
/// `parseMailLog` reads either shape line-by-line (skipping anything that
/// isn't a mail line — banner text, unrelated JSON log lines, blank
/// lines); the file is otherwise treated as an opaque append-only text log.
export interface MailMessage {
    to: string;
    subject: string;
    body: string;
}

const MARKER = "SMTP not configured";

export function parseMailLog(text: string): MailMessage[] {
    const out: MailMessage[] = [];
    for (const line of text.split("\n")) {
        const trimmed = line.trim();
        if (trimmed === "" || !trimmed.includes(MARKER)) continue;
        const msg = tryParseGoShape(trimmed) ?? tryParseJavaShape(trimmed);
        if (msg) out.push(msg);
    }
    return out;
}

function tryParseGoShape(line: string): MailMessage | null {
    let parsed: unknown;
    try {
        parsed = JSON.parse(line);
    } catch {
        return null;
    }
    if (typeof parsed !== "object" || parsed === null) return null;
    const obj = parsed as Record<string, unknown>;
    if (typeof obj.to !== "string" || typeof obj.body !== "string") return null;
    return { to: obj.to, subject: typeof obj.subject === "string" ? obj.subject : "", body: obj.body };
}

/// `formattedMessage` (or a bare-text log line, when `FC_LOG_FORMAT=text`)
/// carries `... to=<addr> subject=<text> body=<text>` inline; `body` is
/// last, so it runs to the end of the field.
const JAVA_INLINE = /\bto=(\S+)\s+subject=(.*?)\s+body=(.*)$/s;

function tryParseJavaShape(line: string): MailMessage | null {
    let text = line;
    try {
        const parsed = JSON.parse(line);
        if (typeof parsed === "object" && parsed !== null) {
            const obj = parsed as Record<string, unknown>;
            // Since a9f7b7e (2026-09-09) the Java log carries its values as
            // structured fields — logback's `kvpList`, an array of one-key
            // objects — and the message is the bare marker sentence.
            if (Array.isArray(obj.kvpList)) {
                const kv: Record<string, unknown> = Object.assign({}, ...obj.kvpList);
                if (typeof kv.to === "string" && typeof kv.body === "string") {
                    return { to: kv.to, subject: typeof kv.subject === "string" ? kv.subject : "", body: kv.body };
                }
            }
            const field = obj.formattedMessage ?? obj.message ?? obj.msg;
            if (typeof field === "string") text = field;
        }
    } catch {
        // not JSON — fall through and match the raw line (text log format)
    }
    const m = JAVA_INLINE.exec(text);
    if (!m) return null;
    return { to: m[1], subject: m[2].trim(), body: m[3] };
}

/// The newest message addressed to `address` (case-insensitive), or `null`
/// when none. "Newest" = last matching line — both logs are append-only in
/// chronological order.
/// The newest message to `address`, optionally only one whose subject is
/// exactly `subject` — needed on Java, where mail leaves through the outbox
/// (a ~2 s poll) and so can land after an unrelated notice to the same
/// address that a previous step triggered.
export function lastMailTo(logText: string, address: string, subject?: string): MailMessage | null {
    const wanted = address.trim().toLowerCase();
    const messages = parseMailLog(logText);
    for (let i = messages.length - 1; i >= 0; i--) {
        if (messages[i].to.trim().toLowerCase() === wanted && (subject === undefined || messages[i].subject === subject)) {
            return messages[i];
        }
    }
    return null;
}

/// Polls `logPath` for a message to `address` — the log is written by a
/// child process (spec §4), so there's an unavoidable gap between the HTTP
/// response returning and the line landing on disk through our own pipe
/// relay (`side.ts`'s `child.stdout.pipe(log)`).
export async function waitForMailTo(logPath: string, address: string, timeoutMs = 10_000, subject?: string): Promise<MailMessage> {
    const { readFile } = await import("node:fs/promises");
    const deadline = Date.now() + timeoutMs;
    for (;;) {
        let text = "";
        try {
            text = await readFile(logPath, "utf8");
        } catch {
            // log file not created yet
        }
        const found = lastMailTo(text, address, subject);
        if (found) return found;
        if (Date.now() >= deadline) {
            throw new Error(`waitForMailTo: no mail to ${address}${subject ? ` with subject "${subject}"` : ""} in ${logPath} within ${timeoutMs}ms`);
        }
        await new Promise((r) => setTimeout(r, 200));
    }
}

/// The first `http…`/`https…` URL in `body`, or `null` when there isn't one.
/// HTML bodies wrap the link in an `href="…"` attribute; the URL itself
/// never contains `"`, `'`, `<`, `>` or whitespace, so a bare scan for the
/// scheme is enough — no HTML parser needed.
const URL_RE = /https?:\/\/[^\s"'<>]+/;

export function firstLink(body: string): string | null {
    const m = URL_RE.exec(body);
    return m ? m[0] : null;
}
