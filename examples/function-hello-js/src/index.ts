import { handler, HttpDenied, Result } from "@flowcatalyst/function";
import type { Context, FunctionRequest, FunctionResult, PrincipalCaller } from "@flowcatalyst/function";

/// The JavaScript twin of `examples/function-hello` (Java) — the reference
/// every JS function author reads first (`docs/spec/function-js-guest.md`
/// §2, `docs/functions.md`). Deliberately small, same shape as the Java
/// example:
///
/// - a **`webhook`** endpoint (`/events/greeting-requested`) — parses the
///   platform's own delivery envelope (the same JSON `Webhook.event` reads
///   in Java — `@flowcatalyst/function` carries no parser for it, an author
///   reads the fields off `req.json()` directly), reads a config value and
///   checks a secret's PRESENCE without ever logging its VALUE, emits an
///   event this function's own application owns, and acks — or, on a
///   platform 5xx, asks for a retry rather than failing outright.
/// - a **`platform`** endpoint (`GET /api/hello/{name}`) — checks the
///   caller's own `hasPermission` for the `hello` application's
///   `hello:greeting:greet` permission BEFORE doing anything else.
/// - a **`none`** endpoint (`GET /healthz`) — the host checks nothing.
/// - a **`none`** endpoint (`GET /api/proxy?url=`) — beyond the Java
///   example: demonstrates `ctx.http.request` and its `HttpDenied` on a
///   host outside `manifest.httpAllow` (the host integration test's
///   allowlist-denial mutant, `docs/spec/function-js-guest.md` §4).

const EVENT_TYPE = "hello:greeting:greeting:sent";
/// The `hello` application's own permission — not one of the platform's
/// `platform:*:*:*` codes, an application-defined one a caller's token
/// carries the same way.
const GREET_PERMISSION = "hello:greeting:greet";

interface DeliveryEnvelope {
	id: string;
	type: string;
	attemptNumber: number;
	subject?: string;
	correlationId?: string;
	messageGroup?: string;
	data?: { name?: string };
}

export const handle = handler((req: FunctionRequest, ctx: Context): FunctionResult => {
	if (req.path === "/healthz") {
		return handleHealth();
	}
	if (req.path === "/events/greeting-requested") {
		return handleGreetingRequested(req, ctx);
	}
	if (req.path.startsWith("/api/hello/")) {
		return handleHello(req, ctx);
	}
	if (req.path === "/api/proxy") {
		return handleProxy(req, ctx);
	}
	// Unreachable in production: the host never delivers a path the manifest
	// does not declare an endpoint for.
	return Result.fail(`no route for path ${req.path}`);
});

/// A dedup id only needs to be unique per emit, not cryptographically
/// random — avoids depending on `crypto.randomUUID()`, whose ambient type
/// is not in every `@extism/js-pdk` types release.
function randomId(): string {
	return `greeting-${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;
}

function handleHealth(): FunctionResult {
	return Result.json(200, { status: "ok" });
}

/// `auth: platform` guarantees `req.caller.kind === "principal"` once this
/// arrives through the real host; the check below still fails closed for
/// anything else rather than assuming it. `hasPermission` is checked BEFORE
/// anything else runs, so a caller without it never reaches the
/// response-building code below.
function handleHello(req: FunctionRequest, ctx: Context): FunctionResult {
	const caller = req.caller;
	if (caller.kind !== "principal" || !(caller as PrincipalCaller).hasPermission(GREET_PERMISSION)) {
		return Result.json(403, { error: "PERMISSION_REQUIRED" });
	}
	const principal = caller as PrincipalCaller;
	// Observable-from-outside marker that the handler body itself ran, for a
	// test to assert absence of on the permission-denied path.
	ctx.logger.info(`hello handled (principal=${principal.id})`);
	const name = req.pathParams.name ?? "world";
	return Result.json(200, { message: `hello, ${name}!`, principalId: principal.id });
}

function handleGreetingRequested(req: FunctionRequest, ctx: Context): FunctionResult {
	const event = req.json<DeliveryEnvelope>();

	const greeting = ctx.config.get("GREETING") ?? "Hello";
	// Presence only — the value itself is never read into a log line, a
	// response body or the emitted event.
	const apiKeyPresent = ctx.secrets.get("API_KEY") !== undefined;
	ctx.logger.info(`greeting requested (subject=${event.subject}, apiKeyPresent=${apiKeyPresent})`);

	const name = event.data?.name ?? "world";

	try {
		ctx.events.emit({
			type: EVENT_TYPE,
			source: `function:${req.address}`,
			subject: event.subject,
			dataContentType: "application/json",
			data: { name, greeting: `${greeting}, ${name}!` },
			correlationId: event.correlationId,
			causationId: event.id,
			messageGroup: event.messageGroup,
			dedupId: randomId(),
		});
	} catch (e) {
		const status = (e as { status?: number }).status ?? 0;
		if (Math.floor(status / 100) === 5) {
			// A transport/availability problem on the platform's side — worth
			// asking the delivery to come back.
			return Result.retry(5);
		}
		// A genuine rejection (e.g. EVENT_TYPE_NOT_OWNED) — retrying would
		// never succeed, so this is reported, not retried.
		const code = (e as { code?: string }).code ?? String(e);
		return Result.fail(`event emit refused: ${code}`);
	}
	return Result.ok();
}

/// Not in the Java example — exercises `ctx.http.request` for the guest
/// library's own sake, and gives the host integration test's allowlist
/// denial something to call against the committed module itself
/// (`docs/spec/function-js-guest.md` §4).
function handleProxy(req: FunctionRequest, ctx: Context): FunctionResult {
	const url = req.query.url?.[0];
	if (!url) {
		return Result.json(400, { error: "missing 'url' query parameter" });
	}
	try {
		const reply = ctx.http.request({ method: "GET", url });
		return Result.json(200, { denied: false, status: reply.status, body: reply.body });
	} catch (e) {
		if (e instanceof HttpDenied) {
			return Result.json(200, { denied: true, reason: e.message });
		}
		throw e;
	}
}
