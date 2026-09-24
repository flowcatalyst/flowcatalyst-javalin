import { bytesToBase64 } from "./base64.js";
import { buildContext } from "./context.js";
import { parseRequest } from "./request.js";
import { Result } from "./result.js";
import type { Context, FunctionRequest, FunctionResult } from "./types.js";

/// The body reason for an uncaught exception — fixed, so nothing the exception
/// carries reaches the caller (and never blank, which `Result.fail` refuses).
export const UNCAUGHT_REASON = "the function failed";

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
/// is logged (`console.error`, message and stack) and becomes
/// `Result.fail(UNCAUGHT_REASON)` — a `500` whose body never carries the
/// exception's message, matching Java's
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
			// Logged for the operator (with the stack), never answered: the caller of a webhook
			// or public endpoint must not read the function's internals — the JVM host answers
			// an uncaught exception the same way (`FnHttpServer`: "the function failed").
			const detail = e instanceof Error ? `${e.message}${e.stack ? `\n${e.stack}` : ""}` : String(e);
			console.error(`uncaught exception in the handler: ${detail}`);
			result = Result.fail(UNCAUGHT_REASON);
		}
		Host.outputString(encodeResult(result));
		return 0;
	};
}
