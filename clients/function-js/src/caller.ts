import type { AnonymousCaller, Caller, PlatformCaller, PrincipalCaller } from "./types.js";

const PLATFORM: PlatformCaller = Object.freeze({ kind: "platform" });
const ANONYMOUS: AnonymousCaller = Object.freeze({ kind: "anonymous" });

/// `io.flowcatalyst.function.Caller.Principal#matches`, copied verbatim —
/// equal strings, or same segment count with every held segment either `*`
/// or equal to the required one. `test/fixtures/caller-cases.json` carries
/// the exact table the Java `CallerTest` uses, so the two never drift apart.
function segmentsMatch(held: string, required: string): boolean {
	if (held === required) return true;
	const h = held.split(":");
	const r = required.split(":");
	if (h.length !== r.length) return false;
	for (let i = 0; i < h.length; i++) {
		if (h[i] !== "*" && h[i] !== r[i]) return false;
	}
	return true;
}

class PrincipalImpl implements PrincipalCaller {
	readonly kind = "principal" as const;
	readonly id: string;
	readonly type: string;
	readonly tier: string | null;
	readonly clients: readonly string[];
	readonly roles: readonly string[];
	readonly applications: readonly string[];
	readonly allApplications: boolean;
	readonly permissions: readonly string[];

	constructor(json: {
		id: string;
		type: string;
		tier?: string | null;
		clients?: readonly string[];
		roles?: readonly string[];
		applications?: readonly string[];
		allApplications?: boolean;
		permissions?: readonly string[];
	}) {
		if (typeof json.id !== "string" || typeof json.type !== "string") {
			throw new Error("a principal caller requires 'id' and 'type'");
		}
		this.id = json.id;
		this.type = json.type;
		this.tier = json.tier ?? null;
		this.clients = Object.freeze([...(json.clients ?? [])]);
		this.roles = Object.freeze([...(json.roles ?? [])]);
		this.applications = Object.freeze([...(json.applications ?? [])]);
		this.allApplications = json.allApplications === true;
		this.permissions = Object.freeze([...(json.permissions ?? [])]);
	}

	hasPermission(required: string | null | undefined): boolean {
		if (required == null) return false;
		return this.permissions.some((held) => segmentsMatch(held, required));
	}

	hasAnyPermission(...required: string[]): boolean {
		return required.some((r) => this.hasPermission(r));
	}

	hasAllPermissions(...required: string[]): boolean {
		return required.every((r) => this.hasPermission(r));
	}

	hasRole(code: string): boolean {
		return this.roles.includes(code);
	}

	isAnchor(): boolean {
		return this.tier === "ANCHOR";
	}

	canAccessClient(clientId: string): boolean {
		return this.isAnchor() || this.clients.includes(clientId);
	}

	canAccessApplication(applicationId: string): boolean {
		return this.allApplications || this.applications.includes(applicationId);
	}

	clientId(): string | undefined {
		if (this.clients.length === 1 && this.clients[0] !== "*") {
			return this.clients[0];
		}
		return undefined;
	}
}

/// Parses the ABI's `caller` object into a [Caller].
export function parseCaller(json: unknown): Caller {
	if (json === null || typeof json !== "object") {
		throw new Error(`caller must be an object, got ${JSON.stringify(json)}`);
	}
	const kind = (json as { kind?: unknown }).kind;
	switch (kind) {
		case "platform":
			return PLATFORM;
		case "anonymous":
			return ANONYMOUS;
		case "principal":
			// biome-ignore lint: constructed from the ABI's own shape, validated inside PrincipalImpl
			return new PrincipalImpl(json as any);
		default:
			throw new Error(`unknown caller kind: ${JSON.stringify(kind)}`);
	}
}
