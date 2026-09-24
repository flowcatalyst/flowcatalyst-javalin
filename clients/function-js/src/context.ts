/// <reference path="./extism-globals.d.ts" />
import { HttpDenied } from "./http-denied.js";
import type {
	Config,
	Context,
	Events,
	HttpCaller,
	HttpReply,
	HttpRequestInit,
	Logger,
	OutboundEvent,
	Secrets,
} from "./types.js";
import { EventEmitException } from "./types.js";

const decoder = new TextDecoder("utf-8");

/// Calls `fc_secret_get` through `extism:host/user`
/// (`docs/spec/function-wasm-runtime.md` §4, mirrors
/// `HostFunctions#secretGet`) — the value, or `null` for an undeclared or
/// unset key (the host answers offset `0`).
function callSecretGet(key: string): string | null {
	const request = Memory.fromString(key);
	const resultOffset = Host.getFunctions().fc_secret_get(request.offset);
	request.free();
	if (resultOffset === 0 || resultOffset === 0n) {
		return null;
	}
	const result = Memory.find(resultOffset);
	const value = result.readString();
	result.free();
	return value;
}

/// Calls `fc_emit_event` through `extism:host/user`
/// (mirrors `HostFunctions#emitEvent`) — the `{"ok":true}` /
/// `{"ok":false,"error":"..."}` JSON the host always answers with.
function callEmitEvent(eventJson: string): { ok: boolean; error?: string } {
	const request = Memory.fromString(eventJson);
	const resultOffset = Host.getFunctions().fc_emit_event(request.offset);
	request.free();
	const result = Memory.find(resultOffset);
	const json = result.readString();
	result.free();
	return JSON.parse(json) as { ok: boolean; error?: string };
}

class ConfigImpl implements Config {
	get(key: string): string | undefined {
		return Config.get(key) ?? undefined;
	}

	require(key: string): string {
		const value = this.get(key);
		if (value === undefined) {
			throw new Error(`config key not declared: ${key}`);
		}
		return value;
	}
}

class SecretsImpl implements Secrets {
	get(key: string): string | undefined {
		return callSecretGet(key) ?? undefined;
	}

	require(key: string): string {
		const value = this.get(key);
		if (value === undefined) {
			throw new Error(`secret key not declared: ${key}`);
		}
		return value;
	}
}

class HttpCallerImpl implements HttpCaller {
	request(init: HttpRequestInit): HttpReply {
		const headers: Record<string, string> = {};
		if (init.headers) {
			for (const [name, value] of Object.entries(init.headers)) {
				headers[name] = typeof value === "string" ? value : value.join(", ");
			}
		}
		const body =
			init.body === undefined
				? undefined
				: typeof init.body === "string"
					? init.body
					: decoder.decode(init.body);
		// Cast, not the ambient `HttpResponse` type directly: the published
		// `@extism/js-pdk@1.1.1` types lag the `extism-js` CLI's actual
		// runtime (no `headers` field there yet), even though the PDK's own
		// README documents it -- confirmed against upstream's prelude source
		// (`crates/core/src/prelude/src/http.ts`, v1.6.0).
		const reply = Http.request({ url: init.url, method: init.method ?? "GET", headers }, body) as {
			status: number;
			body: string;
			headers?: Record<string, string>;
		};
		if (reply.status === 0) {
			let reason = reply.body;
			try {
				const parsed = JSON.parse(reply.body) as { error?: string };
				if (typeof parsed.error === "string") reason = parsed.error;
			} catch {
				// the body was not the host's own {"error": "..."} JSON — surface it verbatim
			}
			throw new HttpDenied(reason);
		}
		return { status: reply.status, headers: Object.freeze({ ...(reply.headers ?? {}) }), body: reply.body };
	}
}

class EventsImpl implements Events {
	emit(event: OutboundEvent): void {
		// `data` rides as an embedded JSON value (see the OutboundEvent doc) —
		// `undefined` is dropped by JSON.stringify, matching the host's "data
		// absent" case exactly (HostFunctions#emit: `data.isMissingNode()`).
		const wire = {
			type: event.type,
			source: event.source,
			subject: event.subject,
			dataContentType: event.dataContentType,
			data: event.data,
			correlationId: event.correlationId,
			causationId: event.causationId,
			messageGroup: event.messageGroup,
			dedupId: event.dedupId,
		};
		const answer = callEmitEvent(JSON.stringify(wire));
		if (!answer.ok) {
			throw new EventEmitException(answer.error ?? "EMIT_FAILED", 0);
		}
	}
}

function consoleLogger(): Logger {
	return {
		debug: (message: string) => console.debug(message),
		info: (message: string) => console.info(message),
		warn: (message: string) => console.warn(message),
		error: (message: string) => console.error(message),
	};
}

/// Builds the [Context] a handler is called with — reads the ambient Extism
/// globals `extism-js` provides inside the guest
/// (`extism-globals.d.ts`). A test injects fakes by setting `globalThis`'s
/// `Host`/`Config`/`Http`/`Memory` before calling this, rather than this
/// function taking them as parameters — the same globals a bundled handler
/// itself reads, so a test exercises the exact code path the guest runs.
export function buildContext(): Context {
	const logger = consoleLogger();
	const config = new ConfigImpl();
	const secrets = new SecretsImpl();
	const http = new HttpCallerImpl();
	const events = new EventsImpl();
	return {
		logger,
		config,
		secrets,
		http,
		events,
		now(): Date {
			return new Date();
		},
	};
}
