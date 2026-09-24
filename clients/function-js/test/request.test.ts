import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import { parseRequest } from "../src/request.js";

const fixturePath = fileURLToPath(new URL("./fixtures/echo-request.json", import.meta.url));
const echoFixture = JSON.parse(readFileSync(fixturePath, "utf-8"));

describe("parseRequest — the real ABI shape (docs/spec/function-wasm-runtime.md §3)", () => {
	it("carries every scalar field intact", () => {
		const req = parseRequest(echoFixture);
		expect(req.address).toBe("fnc_a");
		expect(req.version).toBe(1);
		expect(req.invocationId).toBe("inv-1");
		expect(req.method).toBe("POST");
		expect(req.path).toBe("/echo/42");
		expect(req.originalHost).toBe("127.0.0.1:8080");
		expect(req.originalPath).toBe("/functions/fnc_a/echo/42");
		expect(req.remoteAddress).toBe("127.0.0.1");
	});

	// mutant: drop pathParams from parsing — the exact regression named in
	// function-wasm-runtime.md §6 test 1's own mutant note.
	it("carries pathParams", () => {
		const req = parseRequest(echoFixture);
		expect(req.pathParams.id).toBe("42");
	});

	it("carries multi-valued query params", () => {
		const req = parseRequest(echoFixture);
		expect(req.query.y).toEqual(["hello world", "again"]);
	});

	it("decodes the UTF-8 body from bodyBase64", () => {
		const req = parseRequest(echoFixture);
		expect(req.text()).toBe("héllo body");
		expect(new TextDecoder().decode(req.body())).toBe("héllo body");
	});

	it("parses an anonymous caller", () => {
		const req = parseRequest(echoFixture);
		expect(req.caller).toEqual({ kind: "anonymous" });
	});

	it("looks headers up case-insensitively", () => {
		const req = parseRequest(echoFixture);
		expect(req.header("x-test-custom")).toBe("hi");
		expect(req.header("X-TEST-CUSTOM")).toBe("hi");
		expect(req.headerValues("X-Test-Custom")).toEqual(["hi"]);
		expect(req.header("missing")).toBeUndefined();
		expect(req.headerValues("missing")).toEqual([]);
	});

	it("parses a JSON body with json()", () => {
		const req = parseRequest({
			...echoFixture,
			bodyBase64: Buffer.from(JSON.stringify({ a: 1 }), "utf-8").toString("base64"),
		});
		expect(req.json<{ a: number }>()).toEqual({ a: 1 });
	});

	it("defaults an absent body to empty", () => {
		const { bodyBase64: _drop, ...rest } = echoFixture;
		const req = parseRequest(rest);
		expect(req.body()).toEqual(new Uint8Array(0));
		expect(req.text()).toBe("");
	});

	it("rejects a non-object request", () => {
		expect(() => parseRequest("not an object")).toThrow();
		expect(() => parseRequest(null)).toThrow();
	});

	it("rejects a request missing a required field", () => {
		const { method: _drop, ...rest } = echoFixture;
		expect(() => parseRequest(rest)).toThrow(/method/);
	});
});
