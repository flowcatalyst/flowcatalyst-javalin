import { describe, it, expect } from "vitest";
import { parseMailLog, lastMailTo, firstLink } from "../mail.js";

const GO_LINE = (to: string, subject: string, body: string) =>
    JSON.stringify({
        time: "2026-09-05T10:00:00Z",
        level: "WARN",
        msg: "[email] SMTP not configured — logging instead of sending",
        to,
        subject,
        body,
    });

// The shape Java's GoJsonEncoder writes since the `docs/spec/logging.md`
// owner ruling (2026-09-14): the same flat shape Go writes — `to`/
// `subject`/`body` top-level, alongside `msg` — plus the Java-only
// superset keys `logger`/`thread`, which the parser ignores. Copied from a
// real fcdev log line's field names.
const JAVA_LINE = (to: string, subject: string, body: string) =>
    JSON.stringify({
        time: "2026-09-05T10:00:00.000123+01:00",
        level: "WARN",
        msg: "SMTP not configured; mail logged instead of sent",
        to,
        subject,
        body,
        logger: "io.flowcatalyst.platform.mail.MailService",
        thread: "virtual-1066",
    });

describe("parseMailLog / lastMailTo (Go shape)", () => {
    it("finds the newest message for an address", () => {
        const log = [
            GO_LINE("a@example.com", "First", "<a href=\"http://localhost:1/first\">go</a>"),
            GO_LINE("a@example.com", "Second", "<a href=\"http://localhost:1/second\">go</a>"),
        ].join("\n");

        const msg = lastMailTo(log, "a@example.com");
        expect(msg?.subject).toBe("Second");

        // Mutant check: if lastMailTo picked the FIRST match instead of the
        // last, this same assertion would see "First" and fail — the
        // ordering is what's actually pinned here, not just "a message was
        // found".
    });

    it("ignores other addresses", () => {
        const log = [
            GO_LINE("someone-else@example.com", "Not this one", "<a href=\"http://localhost:1/nope\">go</a>"),
        ].join("\n");

        expect(lastMailTo(log, "a@example.com")).toBeNull();
    });

    it("is case-insensitive on the recipient", () => {
        const log = GO_LINE("A@Example.com", "Case", "body");
        expect(lastMailTo(log, "a@example.com")).not.toBeNull();
    });
});

describe("parseMailLog / lastMailTo (Java flat shape)", () => {
    it("extracts to/subject/body from the top-level fields", () => {
        const log = JAVA_LINE("b@example.com", "Reset your password", "<a href=\"http://localhost:2/reset?token=abc\">link</a>");
        const msg = lastMailTo(log, "b@example.com");
        expect(msg).not.toBeNull();
        expect(msg?.subject).toBe("Reset your password");
        expect(msg?.body).toContain("token=abc");
    });

    it("newest-for-an-address also holds across the java shape", () => {
        const log = [
            JAVA_LINE("c@example.com", "Old", "old body"),
            JAVA_LINE("c@example.com", "New", "new body"),
        ].join("\n");
        expect(lastMailTo(log, "c@example.com")?.subject).toBe("New");
    });

    it("newest-for-an-address holds across a mixed go/java log", () => {
        const log = [
            GO_LINE("d@example.com", "Old", "old body"),
            JAVA_LINE("d@example.com", "New", "new body"),
        ].join("\n");
        expect(lastMailTo(log, "d@example.com")?.subject).toBe("New");
    });
});

describe("parseMailLog", () => {
    it("skips unrelated log lines instead of throwing", () => {
        const log = [
            JSON.stringify({ level: "INFO", msg: "server started", port: 8080 }),
            "not json at all",
            GO_LINE("z@example.com", "Only", "body"),
        ].join("\n");
        const messages = parseMailLog(log);
        expect(messages).toHaveLength(1);
        expect(messages[0].to).toBe("z@example.com");
    });
});

describe("firstLink", () => {
    it("returns the first http(s) URL in a body", () => {
        const body = 'Click <a href="https://example.com/reset?token=xyz">here</a> or copy http://fallback.example.com/x';
        expect(firstLink(body)).toBe("https://example.com/reset?token=xyz");
    });

    it("returns null when there is no link", () => {
        expect(firstLink("no links in this body")).toBeNull();
    });

    it("stops at the closing quote, not mid-URL punctuation", () => {
        // Mutant check: a greedy `.*` instead of `[^\s"'<>]+` would swallow
        // the closing `</a>` into the "link".
        const body = '<a href="http://localhost:3/reset?token=abc">reset</a> — thanks';
        expect(firstLink(body)).toBe("http://localhost:3/reset?token=abc");
    });
});

describe("lastMailTo with a subject", () => {
    it("skips a newer unrelated notice to the same address", () => {
        const log = [
            JAVA_LINE("e@example.com", "Reset your password", "http://x/reset?token=t"),
            JAVA_LINE("e@example.com", "Your password was changed", "no link"),
        ].join("\n");
        expect(lastMailTo(log, "e@example.com")?.subject).toBe("Your password was changed");
        expect(lastMailTo(log, "e@example.com", "Reset your password")?.body).toContain("token=t");
    });
});
