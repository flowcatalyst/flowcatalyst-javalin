import { base64ToBytes } from "./base64.js";
import { parseCaller } from "./caller.js";
import type { FunctionRequest } from "./types.js";

const decoder = new TextDecoder("utf-8");

class FunctionRequestImpl implements FunctionRequest {
	readonly address: string;
	readonly version: number;
	readonly invocationId: string;
	readonly method: string;
	readonly path: string;
	readonly originalHost: string | null;
	readonly originalPath: string | null;
	readonly pathParams: Readonly<Record<string, string>>;
	readonly query: Readonly<Record<string, readonly string[]>>;
	readonly headers: Readonly<Record<string, readonly string[]>>;
	readonly remoteAddress: string | null;
	readonly caller: FunctionRequest["caller"];
	private readonly bodyBase64: string;

	constructor(json: Record<string, unknown>) {
		this.address = requireString(json, "address");
		this.version = requireNumber(json, "version");
		this.invocationId = requireString(json, "invocationId");
		this.method = requireString(json, "method");
		this.path = requireString(json, "path");
		this.originalHost = optionalString(json, "originalHost");
		this.originalPath = optionalString(json, "originalPath");
		this.pathParams = Object.freeze({ ...(json.pathParams as Record<string, string> | undefined) });
		this.query = freezeMulti(json.query);
		this.headers = freezeMulti(json.headers);
		this.remoteAddress = optionalString(json, "remoteAddress");
		this.caller = parseCaller(json.caller);
		this.bodyBase64 = typeof json.bodyBase64 === "string" ? json.bodyBase64 : "";
	}

	body(): Uint8Array {
		return base64ToBytes(this.bodyBase64);
	}

	text(): string {
		return decoder.decode(this.body());
	}

	json<T = unknown>(): T {
		return JSON.parse(this.text()) as T;
	}

	header(name: string): string | undefined {
		return this.headerValues(name)[0];
	}

	headerValues(name: string): readonly string[] {
		const lower = name.toLowerCase();
		for (const key of Object.keys(this.headers)) {
			if (key.toLowerCase() === lower) {
				return this.headers[key] ?? [];
			}
		}
		return [];
	}
}

function freezeMulti(value: unknown): Readonly<Record<string, readonly string[]>> {
	const out: Record<string, readonly string[]> = {};
	if (value && typeof value === "object") {
		for (const [key, values] of Object.entries(value as Record<string, unknown>)) {
			out[key] = Object.freeze(Array.isArray(values) ? values.map(String) : []);
		}
	}
	return Object.freeze(out);
}

function requireString(json: Record<string, unknown>, key: string): string {
	const value = json[key];
	if (typeof value !== "string") {
		throw new Error(`request field '${key}' must be a string`);
	}
	return value;
}

function requireNumber(json: Record<string, unknown>, key: string): number {
	const value = json[key];
	if (typeof value !== "number") {
		throw new Error(`request field '${key}' must be a number`);
	}
	return value;
}

function optionalString(json: Record<string, unknown>, key: string): string | null {
	const value = json[key];
	return typeof value === "string" ? value : null;
}

/// Parses the ABI's request JSON (`docs/spec/function-wasm-runtime.md` §3)
/// into a [FunctionRequest].
export function parseRequest(json: unknown): FunctionRequest {
	if (json === null || typeof json !== "object") {
		throw new Error("request body must be a JSON object");
	}
	return new FunctionRequestImpl(json as Record<string, unknown>);
}
