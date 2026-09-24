import { afterEach, describe, expect, it, vi } from "vitest";
import { buildContext } from "../src/context.js";
import { HttpDenied } from "../src/http-denied.js";

type G = Record<string, unknown>;

function installMemory() {
	// A tiny in-process "memory": Memory.fromString allocates a slot, the
	// fake host functions read/write slots by offset, Memory.find reads one
	// back — enough to exercise the real offset-passing protocol without a
	// real Wasm instance.
	const slots = new Map<number, string>();
	let next = 1;
	(globalThis as G).Memory = {
		fromString: (value: string) => {
			const offset = next++;
			slots.set(offset, value);
			return { offset, len: value.length, readString: () => slots.get(offset) ?? "", free: () => slots.delete(offset) };
		},
		find: (offset: number) => ({
			offset,
			len: (slots.get(offset) ?? "").length,
			readString: () => slots.get(offset) ?? "",
			free: () => slots.delete(offset),
		}),
	};
	return slots;
}

afterEach(() => {
	delete (globalThis as G).Config;
	delete (globalThis as G).Http;
	delete (globalThis as G).Host;
	delete (globalThis as G).Memory;
});

describe("Context#config", () => {
	it("returns the value Config.get answers, undefined when null", () => {
		(globalThis as G).Config = { get: (key: string) => (key === "GREETING" ? "hello" : null) };
		const ctx = buildContext();
		expect(ctx.config.get("GREETING")).toBe("hello");
		expect(ctx.config.get("MISSING")).toBeUndefined();
	});

	it("require throws for an undeclared key", () => {
		(globalThis as G).Config = { get: () => null };
		const ctx = buildContext();
		expect(() => ctx.config.require("MISSING")).toThrow(/MISSING/);
	});
});

describe("Context#secrets — presence-only, via fc_secret_get", () => {
	it("returns the value the host function answers", () => {
		const slots = installMemory();
		(globalThis as G).Host = {
			getFunctions: () => ({
				fc_secret_get: (offset: number) => {
					const key = slots.get(offset);
					if (key !== "API_KEY") return 0; // the host's own "empty" signal
					const out = 999;
					slots.set(out, "s3cret");
					return out;
				},
			}),
		};
		const ctx = buildContext();
		expect(ctx.secrets.get("API_KEY")).toBe("s3cret");
	});

	// mutant: treat offset 0 as a real memory handle instead of "empty" — HostFunctions.java's
	// own contract (`docs/spec/function-wasm-runtime.md` §4) is that 0 means undeclared/unset.
	it("returns undefined for a key the host answers offset 0 for", () => {
		installMemory();
		(globalThis as G).Host = { getFunctions: () => ({ fc_secret_get: () => 0 }) };
		const ctx = buildContext();
		expect(ctx.secrets.get("UNDECLARED")).toBeUndefined();
		expect(() => ctx.secrets.require("UNDECLARED")).toThrow(/UNDECLARED/);
	});
});

describe("Context#http — a denied or unreachable host throws HttpDenied", () => {
	it("returns a normal reply on success", () => {
		(globalThis as G).Http = {
			request: vi.fn().mockReturnValue({ status: 201, body: "created", headers: { "x-upstream": "yes" } }),
		};
		const ctx = buildContext();
		const reply = ctx.http.request({ url: "https://example.com/ok" });
		expect(reply.status).toBe(201);
		expect(reply.body).toBe("created");
		expect(reply.headers["x-upstream"]).toBe("yes");
	});

	// mutant: don't check status === 0 — a denial (function-wasm-runtime.md §4: "a denied
	// host is a guest-visible error, not a trap", surfaced to the guest as status 0) would
	// then be returned as an ordinary reply instead of thrown, and a caller who forgot to
	// check the status would silently treat a refusal as a real response.
	it("throws HttpDenied when the host answers status 0, using the {error} body as the message", () => {
		(globalThis as G).Http = {
			request: vi.fn().mockReturnValue({ status: 0, body: JSON.stringify({ error: "not on httpAllow list" }) }),
		};
		const ctx = buildContext();
		expect(() => ctx.http.request({ url: "https://denied.example.com" })).toThrow(HttpDenied);
		try {
			ctx.http.request({ url: "https://denied.example.com" });
			expect.unreachable();
		} catch (e) {
			expect(e).toBeInstanceOf(HttpDenied);
			expect((e as Error).message).toContain("not on httpAllow list");
		}
	});

	it("passes method, joined headers and the body through to Http.request", () => {
		const request = vi.fn().mockReturnValue({ status: 200, body: "" });
		(globalThis as G).Http = { request };
		const ctx = buildContext();
		ctx.http.request({
			url: "https://example.com",
			method: "POST",
			headers: { "X-Multi": ["a", "b"] },
			body: "payload",
		});
		expect(request).toHaveBeenCalledWith(
			{ url: "https://example.com", method: "POST", headers: { "X-Multi": "a, b" } },
			"payload",
		);
	});
});

describe("Context#events — fc_emit_event", () => {
	it("emits without throwing when the host answers ok:true", () => {
		const slots = installMemory();
		(globalThis as G).Host = {
			getFunctions: () => ({
				fc_emit_event: (offset: number) => {
					const sent = JSON.parse(slots.get(offset) ?? "{}");
					expect(sent.type).toBe("hello:greeting:sent");
					expect(sent.dedupId).toBe("d-1");
					const out = 42;
					slots.set(out, JSON.stringify({ ok: true }));
					return out;
				},
			}),
		};
		const ctx = buildContext();
		expect(() => ctx.events.emit({ type: "hello:greeting:sent", dedupId: "d-1", data: { name: "world" } })).not.toThrow();
	});

	// mutant: ignore the platform's ok:false answer — a rejected emit must surface to the
	// function, exactly as Java's Events#emit throws EventEmitException.
	it("throws EventEmitException when the host answers ok:false", () => {
		const slots = installMemory();
		(globalThis as G).Host = {
			getFunctions: () => ({
				fc_emit_event: () => {
					const out = 7;
					slots.set(out, JSON.stringify({ ok: false, error: "EVENT_TYPE_NOT_OWNED" }));
					return out;
				},
			}),
		};
		const ctx = buildContext();
		try {
			ctx.events.emit({ type: "x", dedupId: "d-2" });
			expect.unreachable();
		} catch (e) {
			expect((e as { code?: string }).code).toBe("EVENT_TYPE_NOT_OWNED");
		}
	});
});
