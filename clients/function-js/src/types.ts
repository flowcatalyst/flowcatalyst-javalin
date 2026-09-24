/// Types mirroring the invocation ABI exactly (`docs/spec/function-wasm-runtime.md`
/// §3, `docs/spec/function-js-guest.md` §1) — the same shapes `function-api`'s
/// Java records carry, so a Java and a JavaScript function author read the
/// same concepts under the same names.

/// Who a [FunctionRequest] came from — the JS mirror of `function-api`'s
/// `Caller` sealed interface. One `kind` field a `switch`/`if` narrows on,
/// exactly the three cases the host can ever hand a function
/// (`docs/spec/function-invocation.md` §7).
export type Caller = PlatformCaller | AnonymousCaller | PrincipalCaller;

/// `auth: "webhook"` — a verified delivery: subscription, direct dispatch
/// job or scheduled job.
export interface PlatformCaller {
	readonly kind: "platform";
}

/// `auth: "none"` — the host checked nothing; the function authenticates
/// itself, if at all.
export interface AnonymousCaller {
	readonly kind: "anonymous";
}

/// `auth: "platform"` — an authenticated platform principal, verified
/// against the platform's JWKS before this call ever reached the handler.
/// Every field comes straight off the verified token; `email`/`name` are
/// deliberately NOT carried, same reasoning as Java's `Caller.Principal`.
///
/// The methods below restate the platform's own authorisation rules
/// exactly, same semantics as `Caller.Principal` in `function-api` (Java) —
/// tested against the same cases (`test/fixtures/caller-cases.json`).
export interface PrincipalCaller {
	readonly kind: "principal";
	readonly id: string;
	readonly type: string;
	readonly tier: string | null;
	readonly clients: readonly string[];
	readonly roles: readonly string[];
	readonly applications: readonly string[];
	readonly allApplications: boolean;
	readonly permissions: readonly string[];

	/// Whether a held permission satisfies `required` — exact match, or a
	/// held code with the same segment count whose non-wildcard (`*`)
	/// segments equal `required`'s. `null`/`undefined` is never satisfied.
	hasPermission(required: string | null | undefined): boolean;

	/// Any of `required` is held.
	hasAnyPermission(...required: string[]): boolean;

	/// Every one of `required` is held.
	hasAllPermissions(...required: string[]): boolean;

	/// `roles` carries role codes verbatim (no matching rule needed).
	hasRole(code: string): boolean;

	/// `tier === "ANCHOR"`.
	isAnchor(): boolean;

	/// An anchor always; otherwise `clientId` must be in `clients`.
	canAccessClient(clientId: string): boolean;

	/// [#allApplications], or `applicationId` is in `applications`.
	canAccessApplication(applicationId: string): boolean;

	/// The one client this principal is scoped to, when unambiguous: exactly
	/// one entry in `clients` that is not the anchor wildcard `*`.
	/// `undefined` for zero, two-or-more, or a lone `*` entry.
	clientId(): string | undefined;
}

/// One invocation (`docs/spec/function-wasm-runtime.md` §3's request JSON,
/// decoded). `pathParams`/`query`/`headers` are frozen; `body()`/`text()`/
/// `json()` read the base64-encoded wire body.
export interface FunctionRequest {
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
	readonly caller: Caller;

	/// The raw request body.
	body(): Uint8Array;

	/// The body decoded as UTF-8 text.
	text(): string;

	/// The body parsed as JSON.
	json<T = unknown>(): T;

	/// The first value of the header named `name`, matched
	/// case-insensitively.
	header(name: string): string | undefined;

	/// Every value of the header named `name` from the first
	/// case-insensitively matching key — `[]` when there is none.
	headerValues(name: string): readonly string[];
}

/// What a handler answers with — the wire shape
/// (`docs/spec/function-wasm-runtime.md` §3's guest output JSON). Built only
/// through the [FunctionResultBuilders] in `result.ts`.
export interface FunctionResult {
	readonly status: number;
	readonly headers: Readonly<Record<string, readonly string[]>>;
	readonly body: Uint8Array;
}

/// Manifest-declared, non-secret config keys — the JS mirror of
/// `function-api`'s `Config`.
export interface Config {
	get(key: string): string | undefined;

	/// @throws Error if `key` was not declared
	require(key: string): string;
}

/// Manifest-declared secret references — the JS mirror of `function-api`'s
/// `Secrets`. Never logged by an implementation of this interface.
export interface Secrets {
	get(key: string): string | undefined;

	/// @throws Error if `key` was not declared
	require(key: string): string;
}

export type HttpMethod =
	| "GET"
	| "HEAD"
	| "POST"
	| "PUT"
	| "DELETE"
	| "CONNECT"
	| "OPTIONS"
	| "TRACE"
	| "PATCH";

/// An outbound HTTP request made through [HttpCaller#request].
export interface HttpRequestInit {
	readonly method?: HttpMethod;
	readonly url: string;
	readonly headers?: Readonly<Record<string, string | readonly string[]>>;
	readonly body?: Uint8Array | string;
}

/// The response to an [HttpRequestInit].
export interface HttpReply {
	readonly status: number;
	readonly headers: Readonly<Record<string, string>>;
	readonly body: string;
}

/// The host-mediated outbound HTTP client — never a direct network call, so
/// the host can apply the manifest's `httpAllow` list
/// (`docs/spec/function-wasm-runtime.md` §4). A denied or unreachable host
/// answers with `status: 0` from the Extism runtime itself; this wrapper
/// turns that into a thrown [HttpDenied] rather than a reply a caller must
/// remember to check.
export interface HttpCaller {
	/// @throws HttpDenied the host refused the call, or it could not be reached
	request(init: HttpRequestInit): HttpReply;
}

/// An event a function emits through [Events#emit]. Field-for-field the JS
/// mirror of `function-api`'s `OutboundEvent` — except `data`: the
/// `fc_emit_event` host function's own JSON contract carries it as an
/// embedded JSON value, not base64 bytes (`docs/spec/function-wasm-runtime.md`
/// §4), so it is any JSON-serialisable value here rather than a byte array.
export interface OutboundEvent {
	readonly type: string;
	readonly source?: string;
	readonly subject?: string;
	readonly dataContentType?: string;
	readonly data?: unknown;
	readonly correlationId?: string;
	readonly causationId?: string;
	readonly messageGroup?: string;
	readonly dedupId: string;
}

/// Emits platform events on a function's behalf, through the host — no
/// application credential is ever handed to the function.
export interface Events {
	/// @throws EventEmitException the platform refused the event, or could not be reached
	emit(event: OutboundEvent): void;
}

export class EventEmitException extends Error {
	readonly code: string;
	readonly status: number;

	constructor(code: string, status: number, message?: string) {
		super(message ?? `emit refused: ${code} (${status})`);
		this.name = "EventEmitException";
		this.code = code;
		this.status = status;
	}
}

export type LogLevel = "debug" | "info" | "warn" | "error";

export interface Logger {
	debug(message: string): void;
	info(message: string): void;
	warn(message: string): void;
	error(message: string): void;
}

/// Everything a function may reach outside its own invocation — the JS
/// mirror of `function-api`'s `FunctionContext`.
export interface Context {
	readonly logger: Logger;
	readonly config: Config;
	readonly secrets: Secrets;
	readonly http: HttpCaller;
	readonly events: Events;

	/// The host's clock — never read directly from `Date.now()` in library
	/// code, so a test can fix time.
	now(): Date;
}
