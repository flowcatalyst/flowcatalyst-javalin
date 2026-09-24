import { bytesToBase64 } from "./base64.js";
import { buildContext } from "./context.js";
import { parseRequest } from "./request.js";
import { Result } from "./result.js";
import type { Context, FunctionRequest, FunctionResult } from "./types.js";

/// Encodes a [FunctionResult] as the ABI's guest-output JSON
/// (`docs/spec/function-wasm-runtime.md` §3): `{"status", "headers",
/// "bodyBase64"}`.
function encodeResult(result: FunctionResult): string {
	const headers: Record<string, string[]> = {};
	for (const [name, values] of Object.entries(result.headers)) {
		headers[name] = [...values];
	}
	return JSON.stringify({
		status: result.status,
		headers,
		bodyBase64: bytesToBase64(result.body),
	});
}

/// Wraps a handler function as the module's `handle` export
/// (`docs/spec/function-js-guest.md` §1): reads `Host.inputString()`,
/// parses it into a [FunctionRequest], calls `fn`, writes the [FunctionResult]
/// as the ABI's output JSON, and returns `0`. An uncaught exception from `fn`
/// becomes `Result.fail(message)` — a `500`, matching Java's
/// `WasmFunction#handle` doc: "never a trapped instance" — a JS exception
/// that reaches the QuickJS/Extism boundary otherwise traps the whole
/// instance, exactly what a Wasm function is meant to contain.
///
/// Usage — `src/index.ts`:
/// ```ts
/// import { handler, Result } from "@flowcatalyst/function";
///
/// export const handle = handler((req, ctx) => {
///   return Result.json(200, { hello: req.pathParams.name ?? "world" });
/// });
/// ```
export function handler(fn: (req: FunctionRequest, ctx: Context) => FunctionResult): () => number {
	return function handle(): number {
		let result: FunctionResult;
		try {
			const input = JSON.parse(Host.inputString());
			const request = parseRequest(input);
			const ctx = buildContext();
			result = fn(request, ctx);
		} catch (e) {
			const message = e instanceof Error ? e.message : String(e);
			result = Result.fail(message);
		}
		Host.outputString(encodeResult(result));
		return 0;
	};
}
