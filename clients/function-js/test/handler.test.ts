import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { afterEach, describe, expect, it } from "vitest";
import { handler } from "../src/handler.js";
import { Result } from "../src/result.js";

const fixturePath = fileURLToPath(new URL("./fixtures/echo-request.json", import.meta.url));
const echoFixture = readFileSync(fixturePath, "utf-8");

function installHost(inputJson: string): { output: () => string } {
	let captured: string | undefined;
	(globalThis as Record<string, unknown>).Host = {
		inputString: () => inputJson,
		outputString: (s: string) => {
			captured = s;
			return true;
		},
		getFunctions: () => ({}),
	};
	return {
		output: () => {
			if (captured === undefined) {
				throw new Error("Host.outputString was never called");
			}
			return captured;
		},
	};
}

afterEach(() => {
	delete (globalThis as Record<string, unknown>).Host;
});

describe("handler()", () => {
	it("parses the request, calls fn, and writes the encoded result", () => {
		const host = installHost(echoFixture);
		const handle = handler((req) => Result.json(200, { sawPath: req.path }));

		const rc = handle();

		expect(rc).toBe(0);
		const written = JSON.parse(host.output());
		expect(written.status).toBe(200);
		const body = JSON.parse(Buffer.from(written.bodyBase64, "base64").toString("utf-8"));
		expect(body).toEqual({ sawPath: "/echo/42" });
	});

	// mutant: let the exception propagate (or swallow it silently) instead of converting it —
	// an uncaught guest exception traps the whole Wasm instance (function-wasm-runtime.md §3:
	// "never a host exception"/"never a trapped instance"), so this is the one thing standing
	// between a bug in a function author's code and every future call on that instance failing.
	it("turns an uncaught exception into Result.fail (a 500), never letting it propagate", () => {
		const host = installHost(echoFixture);
		const handle = handler(() => {
			throw new Error("boom from the handler");
		});

		const rc = handle();

		expect(rc).toBe(0);
		const written = JSON.parse(host.output());
		expect(written.status).toBe(500);
		const body = JSON.parse(Buffer.from(written.bodyBase64, "base64").toString("utf-8"));
		expect(body.error).toBe("boom from the handler");
	});

	it("turns a non-Error throw into a 500 too", () => {
		const host = installHost(echoFixture);
		const handle = handler(() => {
			// biome-ignore lint: deliberately throwing a non-Error value
			throw "a plain string throw";
		});

		handle();

		const written = JSON.parse(host.output());
		expect(written.status).toBe(500);
	});

	it("encodes multi-valued headers as arrays in the wire output", () => {
		const host = installHost(echoFixture);
		const handle = handler(() => Result.status(200, { "X-Multi": ["a", "b"] }));

		handle();

		const written = JSON.parse(host.output());
		expect(written.headers["X-Multi"]).toEqual(["a", "b"]);
	});
});
