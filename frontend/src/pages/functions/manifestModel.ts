// Pure (no-Vue) helpers behind the manifest editor (docs/spec/
// function-manifest-authoring.md M4, docs/spec/function-ui.md §2.6). Kept
// framework-free so the round-trip/ordering behaviour can be pinned directly
// without mounting a component.
import type { PublishManifestRequest } from "@/api/functions";

export type ManifestModel = PublishManifestRequest;

// The JSON Schema's own property order (server/src/main/resources/schemas/
// function-manifest.schema.json, M1 §1) — Export must reproduce it so the
// downloaded file reads the same way the schema itself is organised.
export const TOP_ORDER = [
	"runtime",
	"entrypoint",
	"pool",
	"warm",
	"limits",
	"endpoints",
	"subscriptions",
	"schedules",
	"public",
	"config",
	"secrets",
	"db",
	"httpAllow",
] as const;

export const LIMITS_ORDER = ["maxDurationMs", "maxConcurrency", "wasmMemoryMb"];
export const ENDPOINT_ORDER = ["path", "auth", "methods", "cors", "maxBodyBytes", "timeoutMs"];
export const CORS_ORDER = ["origins", "methods", "headers", "allowCredentials"];
export const SUBSCRIPTION_ORDER = ["eventType", "path", "mode", "maxRetries", "timeoutSeconds", "dataOnly"];
export const SCHEDULE_ORDER = ["cron", "timezone", "path", "payload"];
export const PUBLIC_ROUTE_ORDER = ["hostname", "pathPrefix", "aliasPrefixes"];
export const DB_ORDER = ["name", "secretRef", "poolSize"];

function pick(
	obj: Record<string, unknown> | undefined,
	order: string[],
): Record<string, unknown> {
	const out: Record<string, unknown> = {};
	if (!obj) return out;
	for (const key of order) {
		if (obj[key] !== undefined) out[key] = obj[key];
	}
	// Never drop a key the order list does not know (a field the schema gained after this
	// list was written): it follows the ordered ones, so Export cannot delete the author's data.
	for (const key of Object.keys(obj)) {
		if (!(key in out) && obj[key] !== undefined) out[key] = obj[key];
	}
	return out;
}

function orderEndpoint(e: Record<string, unknown>): Record<string, unknown> {
	const out = pick(e, ENDPOINT_ORDER);
	if (e["cors"] !== undefined) {
		out["cors"] = pick(e["cors"] as Record<string, unknown>, CORS_ORDER);
	}
	return out;
}

function orderField(key: string, value: unknown): unknown {
	switch (key) {
		case "limits":
			return pick(value as Record<string, unknown>, LIMITS_ORDER);
		case "endpoints":
			return (value as Record<string, unknown>[]).map(orderEndpoint);
		case "subscriptions":
			return (value as Record<string, unknown>[]).map((s) => pick(s, SUBSCRIPTION_ORDER));
		case "schedules":
			return (value as Record<string, unknown>[]).map((s) => pick(s, SCHEDULE_ORDER));
		case "public":
			return (value as Record<string, unknown>[]).map((p) => pick(p, PUBLIC_ROUTE_ORDER));
		case "db":
			return (value as Record<string, unknown>[]).map((d) => pick(d, DB_ORDER));
		default:
			// scalars (runtime/entrypoint/pool/warm) and plain string arrays
			// (config/secrets/httpAllow) need no reordering of their own.
			return value;
	}
}

/** Where the served JSON Schema lives (M1 §3) — same-origin as the API the
 * SPA already talks to, so this works under the dev proxy and in production
 * alike without a configured platform URL (unlike `fn init`, which has no
 * browser origin to infer from). */
export function manifestSchemaUrl(): string {
	return `${window.location.origin}/api/schemas/function-manifest.json`;
}

/** "New manifest" template — the exact shape `fn init --manifest-only`
 * writes (fcdev InitCommand#manifestJson): runtime jvm, the default handler
 * entrypoint, one platform-authenticated GET /hello endpoint. */
export function newManifestModel(): ManifestModel {
	return {
		runtime: "jvm",
		entrypoint: "com.example.fn.Handler",
		endpoints: [{ path: "/hello", auth: "platform", methods: ["GET"] }],
	};
}

export function cloneManifestModel(model: ManifestModel): ManifestModel {
	return JSON.parse(JSON.stringify(model)) as ManifestModel;
}

/** Parses raw manifest JSON text into a model. A top-level "$schema" is
 * discarded — it is an editor hint only (M1 §2: accepted and ignored by
 * `parseStrict`, never written by `toJson`), not part of the wire shape
 * (`PublishManifestRequest` has no such field) — keeping it would make it
 * silently round-trip back out as if it were a real manifest property.
 * Throws (the caller's job to catch) on invalid JSON. */
export function parseManifestText(text: string): ManifestModel {
	const parsed = JSON.parse(text) as Record<string, unknown>;
	const { $schema: _drop, ...rest } = parsed;
	return rest as ManifestModel;
}

/** The exported, JSON.stringify-ready object: "$schema" first, then every
 * field the model carries in the schema's own property order, with absent
 * optional fields left out entirely (never defaulted in) — an import/export
 * round trip must reproduce exactly what was imported, plus "$schema". */
export function exportManifest(model: ManifestModel): Record<string, unknown> {
	const m = model as unknown as Record<string, unknown>;
	const out: Record<string, unknown> = { $schema: manifestSchemaUrl() };
	for (const key of TOP_ORDER) {
		const value = m[key];
		if (value === undefined) continue;
		out[key] = orderField(key, value);
	}
	for (const key of Object.keys(m)) {
		if (!(key in out) && m[key] !== undefined) out[key] = m[key];
	}
	return out;
}

export function manifestToPrettyJson(model: ManifestModel): string {
	return JSON.stringify(exportManifest(model), null, 2);
}
