/**
 * The global route-permission guard runs BEFORE a route's authGuard. On a
 * cold page load the session isn't hydrated yet, and the guard used to wave
 * every such navigation through ("authGuard will handle") — so a role-less
 * user who typed /dashboard stayed there instead of landing on /profile.
 * The guard now settles the session first on authenticated routes.
 */

import { beforeEach, describe, expect, it, vi } from "vitest";
import { createPinia, setActivePinia } from "pinia";
import { START_LOCATION, type RouteLocationNormalized } from "vue-router";
import type { User } from "@/stores/auth";

const mocks = vi.hoisted(() => ({ sessionUser: null as User | null }));

// checkSession stands in for GET /auth/me: hydrates the store from the
// session the test sets up, exactly as the real one does.
vi.mock("@/api/auth", () => ({
	checkSession: vi.fn(async () => {
		const { useAuthStore } = await import("@/stores/auth");
		const store = useAuthStore();
		if (mocks.sessionUser) {
			store.setUser(mocks.sessionUser);
			return true;
		}
		store.clearAuth();
		return false;
	}),
	oauthAuthorizeUrl: vi.fn(),
}));

import { checkSession } from "@/api/auth";
import { authGuard, createRoutePermissionGuard } from "@/router/guards";
import { ROUTE_PERMISSIONS } from "@/stores/permissions";

function route(path: string, authenticated = true): RouteLocationNormalized {
	return {
		path,
		fullPath: path,
		name: undefined,
		params: {},
		query: {},
		hash: "",
		meta: {},
		matched: authenticated ? [{ beforeEnter: authGuard }] : [],
		redirectedFrom: undefined,
	} as unknown as RouteLocationNormalized;
}

function user(over: Partial<User>): User {
	return {
		id: "prn_1",
		email: "u@x.test",
		name: "U",
		clientId: "clt_1",
		roles: [],
		permissions: [],
		ssoManaged: false,
		...over,
	};
}

describe("createRoutePermissionGuard on a cold load", () => {
	beforeEach(() => {
		setActivePinia(createPinia());
		vi.mocked(checkSession).mockClear();
		mocks.sessionUser = null;
	});

	it("sends a role-less user who typed /dashboard to /profile", async () => {
		mocks.sessionUser = user({});
		const next = vi.fn();
		await createRoutePermissionGuard()(route("/dashboard"), START_LOCATION, next);
		expect(checkSession).toHaveBeenCalledTimes(1);
		expect(next).toHaveBeenCalledWith({ path: "/profile", replace: true });
	});

	it("lets a platform admin through to /dashboard", async () => {
		mocks.sessionUser = user({
			clientId: null,
			roles: ["platform:super-admin"],
			permissions: ["platform:*:*:*"],
		});
		const next = vi.fn();
		await createRoutePermissionGuard()(route("/dashboard"), START_LOCATION, next);
		expect(next).toHaveBeenCalledWith();
	});

	it("passes a signed-out visitor through for authGuard to redirect", async () => {
		const next = vi.fn();
		await createRoutePermissionGuard()(route("/dashboard"), START_LOCATION, next);
		expect(next).toHaveBeenCalledWith();
	});

	it("never checks the session for a public route", async () => {
		const next = vi.fn();
		await createRoutePermissionGuard()(route("/portal/login", false), START_LOCATION, next);
		expect(checkSession).not.toHaveBeenCalled();
		expect(next).toHaveBeenCalledWith();
	});
});

// U1 (docs/spec/function-ui.md §6, §4): the function service's three routes
// map to the three permissions the spec names, and the guard actually
// enforces the /functions mapping end to end.
describe("the function service's route permissions", () => {
	beforeEach(() => {
		setActivePinia(createPinia());
		vi.mocked(checkSession).mockClear();
		mocks.sessionUser = null;
	});

	it("maps each of the three routes to the permission the spec calls for", () => {
		expect(ROUTE_PERMISSIONS["/functions"]).toBe(
			"platform:function:function:view",
		);
		expect(ROUTE_PERMISSIONS["/function-domains"]).toBe(
			"platform:function:domain:manage",
		);
		expect(ROUTE_PERMISSIONS["/function-policies"]).toBe(
			"platform:function:policy:manage",
		);
	});

	it("refuses /functions to a user without platform:function:function:view", async () => {
		mocks.sessionUser = user({
			roles: ["some-role"],
			permissions: ["platform:messaging:event:view"],
		});
		const next = vi.fn();
		await createRoutePermissionGuard()(route("/functions"), START_LOCATION, next);
		expect(next).toHaveBeenCalledWith({ path: "/profile", replace: true });
	});

	it("admits a user holding platform:function:function:view to /functions", async () => {
		mocks.sessionUser = user({
			roles: ["some-role"],
			permissions: ["platform:function:function:view"],
		});
		const next = vi.fn();
		await createRoutePermissionGuard()(route("/functions"), START_LOCATION, next);
		expect(next).toHaveBeenCalledWith();
	});
});
