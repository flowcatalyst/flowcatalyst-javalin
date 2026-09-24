import { describe, expect, it } from "vitest";
import { Result } from "../src/result.js";

const decoder = new TextDecoder();

describe("Result.ok", () => {
	it("is 200 with an empty body", () => {
		const r = Result.ok();
		expect(r.status).toBe(200);
		expect(r.body).toEqual(new Uint8Array(0));
	});
});

describe("Result.json", () => {
	it("serialises the value and sets Content-Type", () => {
		const r = Result.json(201, { hello: "world" });
		expect(r.status).toBe(201);
		expect(r.headers["Content-Type"]).toEqual(["application/json"]);
		expect(JSON.parse(decoder.decode(r.body))).toEqual({ hello: "world" });
	});

	it("rejects an out-of-range status", () => {
		expect(() => Result.json(99, {})).toThrow();
		expect(() => Result.json(600, {})).toThrow();
	});
});

describe("Result.text", () => {
	it("carries the text verbatim", () => {
		const r = Result.text(200, "hello");
		expect(decoder.decode(r.body)).toBe("hello");
		expect(r.headers["Content-Type"]).toEqual(["text/plain; charset=utf-8"]);
	});
});

describe("Result.status", () => {
	it("is a direct, verbatim answer", () => {
		const r = Result.status(418, { "X-Teapot": "yes" }, "short and stout");
		expect(r.status).toBe(418);
		expect(r.headers["X-Teapot"]).toEqual(["yes"]);
		expect(decoder.decode(r.body)).toBe("short and stout");
	});

	it("defaults headers and body when omitted", () => {
		const r = Result.status(204);
		expect(r.headers).toEqual({});
		expect(r.body).toEqual(new Uint8Array(0));
	});
});

describe("Result.fail", () => {
	it("is 500 with {\"error\": reason}", () => {
		const r = Result.fail("boom");
		expect(r.status).toBe(500);
		expect(JSON.parse(decoder.decode(r.body))).toEqual({ error: "boom" });
	});

	// mutant: drop the blank check — Java's Result#fail throws IllegalArgumentException
	// for the same input; the JS builder must refuse it identically, not silently accept "".
	it("rejects a blank reason", () => {
		expect(() => Result.fail("")).toThrow();
		expect(() => Result.fail("   ")).toThrow();
	});
});

describe("Result.retry", () => {
	it("is 429 with a Retry-After header", () => {
		const r = Result.retry(5);
		expect(r.status).toBe(429);
		expect(r.headers["Retry-After"]).toEqual(["5"]);
	});

	// mutant: floor instead of ceil — a sub-second remainder must round UP, never down,
	// same as Java's Result#retry doc (asking for less delay than requested is wrong).
	it("rounds a sub-second remainder UP to a whole second", () => {
		expect(Result.retry(0.1).headers["Retry-After"]).toEqual(["1"]);
		expect(Result.retry(5).headers["Retry-After"]).toEqual(["5"]);
	});

	it("allows a zero delay (immediate retry)", () => {
		expect(Result.retry(0).headers["Retry-After"]).toEqual(["0"]);
	});

	it("rejects a negative delay", () => {
		expect(() => Result.retry(-1)).toThrow();
	});
});
