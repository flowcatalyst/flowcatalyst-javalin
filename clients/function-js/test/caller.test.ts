import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import { parseCaller } from "../src/caller.js";
import type { PrincipalCaller } from "../src/types.js";

const casesPath = fileURLToPath(new URL("./fixtures/caller-cases.json", import.meta.url));
const cases = JSON.parse(readFileSync(casesPath, "utf-8")) as {
	hasPermission: { held: string; required: string; expected: boolean }[];
};

function principal(overrides: Partial<Record<string, unknown>> = {}): PrincipalCaller {
	return parseCaller({
		kind: "principal",
		id: "id",
		type: "user",
		tier: null,
		clients: [],
		roles: [],
		applications: [],
		allApplications: false,
		permissions: [],
		...overrides,
	}) as PrincipalCaller;
}

describe("parseCaller", () => {
	it("parses the platform caller as a shared, frozen value", () => {
		const a = parseCaller({ kind: "platform" });
		const b = parseCaller({ kind: "platform" });
		expect(a).toEqual({ kind: "platform" });
		expect(Object.isFrozen(a)).toBe(true);
		expect(a).toBe(b);
	});

	it("parses the anonymous caller the same way", () => {
		const a = parseCaller({ kind: "anonymous" });
		expect(a).toEqual({ kind: "anonymous" });
		expect(Object.isFrozen(a)).toBe(true);
	});

	it("rejects an unknown kind", () => {
		expect(() => parseCaller({ kind: "bogus" })).toThrow(/unknown caller kind/);
	});

	it("parses every field of a principal caller", () => {
		const p = principal({
			id: "user-1",
			type: "user",
			tier: "CLIENT",
			clients: ["clt_1"],
			roles: ["admin"],
			applications: ["app_1"],
			allApplications: false,
			permissions: ["a:b:c:read"],
		});
		expect(p.id).toBe("user-1");
		expect(p.type).toBe("user");
		expect(p.tier).toBe("CLIENT");
		expect(p.clients).toEqual(["clt_1"]);
		expect(p.roles).toEqual(["admin"]);
		expect(p.applications).toEqual(["app_1"]);
		expect(p.permissions).toEqual(["a:b:c:read"]);
	});

	// mutant: return a live reference instead of a copy — mutating the source array would
	// then change the caller's own `clients`, which a function must never observe.
	it("copies clients/roles/applications/permissions rather than aliasing the input", () => {
		const clients = ["clt_1"];
		const p = principal({ clients });
		clients.push("clt_2");
		expect(p.clients).toEqual(["clt_1"]);
		expect(Object.isFrozen(p.clients)).toBe(true);
	});
});

describe("Principal#hasPermission — the wildcard match table shared with Java's CallerTest", () => {
	for (const { held, required, expected } of cases.hasPermission) {
		it(`held=${held} required=${required} -> ${expected}`, () => {
			const p = principal({ permissions: [held] });
			expect(p.hasPermission(required)).toBe(expected);
		});
	}

	// mutant: drop the null guard — a permission check must fail closed on a missing requirement.
	it("is false for a null/undefined requirement, even with a wide-open permission", () => {
		const p = principal({ permissions: ["*:*:*:*"] });
		expect(p.hasPermission(null)).toBe(false);
		expect(p.hasPermission(undefined)).toBe(false);
	});
});

describe("Principal#hasAnyPermission / hasAllPermissions", () => {
	it("hasAnyPermission is true when at least one matches", () => {
		const p = principal({ permissions: ["a:b:c:read"] });
		expect(p.hasAnyPermission("a:b:c:write", "a:b:c:read")).toBe(true);
		expect(p.hasAnyPermission("a:b:c:write", "a:b:c:delete")).toBe(false);
	});

	it("hasAllPermissions requires every one", () => {
		const p = principal({ permissions: ["a:b:c:read", "a:b:c:write"] });
		expect(p.hasAllPermissions("a:b:c:read", "a:b:c:write")).toBe(true);
		expect(p.hasAllPermissions("a:b:c:read", "a:b:c:delete")).toBe(false);
	});
});

describe("Principal#isAnchor / canAccessClient / canAccessApplication / hasRole", () => {
	it("isAnchor is true only when tier is ANCHOR", () => {
		expect(principal({ tier: "ANCHOR" }).isAnchor()).toBe(true);
		expect(principal({ tier: "CLIENT" }).isAnchor()).toBe(false);
		expect(principal({ tier: null }).isAnchor()).toBe(false);
	});

	// mutant: drop the isAnchor() short-circuit — an anchor with an empty clients list
	// would then be refused access to every client, which is wrong.
	it("canAccessClient is true for an anchor regardless of its own clients list", () => {
		const anchor = principal({ tier: "ANCHOR", clients: [] });
		expect(anchor.canAccessClient("anything")).toBe(true);
	});

	it("canAccessClient checks the list for non-anchors", () => {
		const p = principal({ tier: "CLIENT", clients: ["clt_1"] });
		expect(p.canAccessClient("clt_1")).toBe(true);
		expect(p.canAccessClient("clt_2")).toBe(false);
	});

	it("canAccessApplication is true when allApplications", () => {
		const p = principal({ allApplications: true, applications: [] });
		expect(p.canAccessApplication("anything")).toBe(true);
	});

	it("canAccessApplication checks the list otherwise", () => {
		const p = principal({ allApplications: false, applications: ["app_1"] });
		expect(p.canAccessApplication("app_1")).toBe(true);
		expect(p.canAccessApplication("app_2")).toBe(false);
	});

	it("hasRole checks the role list verbatim", () => {
		const p = principal({ roles: ["admin"] });
		expect(p.hasRole("admin")).toBe(true);
		expect(p.hasRole("viewer")).toBe(false);
	});
});

describe("Principal#clientId — the one non-wildcard client, else undefined", () => {
	it("is undefined with no clients", () => {
		expect(principal({ clients: [] }).clientId()).toBeUndefined();
	});

	it("is the one client when exactly one", () => {
		expect(principal({ clients: ["clt_1"] }).clientId()).toBe("clt_1");
	});

	it("is undefined with two clients", () => {
		expect(principal({ clients: ["clt_1", "clt_2"] }).clientId()).toBeUndefined();
	});

	// mutant: skip the "*" check — an anchor's wildcard entry would wrongly report as a client id.
	it("is undefined when the one entry is the anchor wildcard", () => {
		expect(principal({ clients: ["*"] }).clientId()).toBeUndefined();
	});
});
