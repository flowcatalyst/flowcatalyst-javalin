import type { FunctionResult } from "./types.js";

const encoder = new TextEncoder();
const EMPTY = new Uint8Array(0);

function toBody(value: Uint8Array | string | undefined): Uint8Array {
	if (value === undefined) return EMPTY;
	return typeof value === "string" ? encoder.encode(value) : value;
}

function checkStatus(status: number): number {
	if (!Number.isInteger(status) || status < 100 || status > 599) {
		throw new Error(`status must be an integer 100-599, was ${status}`);
	}
	return status;
}

function freezeHeaders(
	headers: Readonly<Record<string, string | readonly string[]>> | undefined,
): Readonly<Record<string, readonly string[]>> {
	const out: Record<string, readonly string[]> = {};
	if (headers) {
		for (const [key, value] of Object.entries(headers)) {
			out[key] = Object.freeze(Array.isArray(value) ? [...value] : [value]);
		}
	}
	return Object.freeze(out);
}

/// The outcome of one handler call — the wire shape the host turns into an
/// HTTP response (`docs/spec/function-js-guest.md` §1, mirroring Java's
/// `function-api` `Result` factories). Built only through these functions,
/// which enforce the same invariants the Java factories do.
export const Result = {
	/// The invocation succeeded; nothing further to say. `200`, empty body.
	ok(): FunctionResult {
		return { status: 200, headers: Object.freeze({}), body: EMPTY };
	},

	/// A direct HTTP answer whose body is `value` serialised as JSON, with
	/// `Content-Type: application/json`.
	json(status: number, value: unknown): FunctionResult {
		return {
			status: checkStatus(status),
			headers: Object.freeze({ "Content-Type": Object.freeze(["application/json"]) }),
			body: encoder.encode(JSON.stringify(value)),
		};
	},

	/// A direct HTTP answer whose body is `value` verbatim, with
	/// `Content-Type: text/plain; charset=utf-8`.
	text(status: number, value: string): FunctionResult {
		return {
			status: checkStatus(status),
			headers: Object.freeze({ "Content-Type": Object.freeze(["text/plain; charset=utf-8"]) }),
			body: encoder.encode(value),
		};
	},

	/// A direct HTTP answer, verbatim.
	status(
		status: number,
		headers?: Readonly<Record<string, string | readonly string[]>>,
		body?: Uint8Array | string,
	): FunctionResult {
		return { status: checkStatus(status), headers: freezeHeaders(headers), body: toBody(body) };
	},

	/// The invocation failed. `reason` becomes the audit/metrics detail,
	/// carried in a `{"error": "<reason>"}` body. `500`.
	///
	/// @throws Error if `reason` is blank
	fail(reason: string): FunctionResult {
		if (!reason || reason.trim() === "") {
			throw new Error("reason must not be blank");
		}
		return {
			status: 500,
			headers: Object.freeze({ "Content-Type": Object.freeze(["application/json"]) }),
			body: encoder.encode(JSON.stringify({ error: reason })),
		};
	},

	/// The invocation should be redelivered after `seconds`, rounded up to a
	/// whole second — same delivery-path caveats as Java's
	/// `function-api` `Result#retry` doc: honoured by a subscription/direct
	/// dispatch job, advisory only for a scheduled job.
	///
	/// @throws Error if `seconds` is negative or not finite
	retry(seconds: number): FunctionResult {
		if (!Number.isFinite(seconds) || seconds < 0) {
			throw new Error(`seconds must be a non-negative finite number, was ${seconds}`);
		}
		const ceil = Math.ceil(seconds);
		return {
			status: 429,
			headers: Object.freeze({ "Retry-After": Object.freeze([String(ceil)]) }),
			body: EMPTY,
		};
	},
};
