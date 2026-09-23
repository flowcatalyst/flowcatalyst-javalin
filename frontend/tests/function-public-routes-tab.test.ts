// @vitest-environment jsdom
/**
 * Package J3 (docs/spec/function-zones-and-aliases.md §3-§4): the Public
 * Routes tab shows each route's opt-in `aliasPrefixes` and, for each, the
 * derived hostname (`qa-myapp.acme.com → alias qa`). Mutant: never render
 * the derived hostname at all.
 */

import { describe, expect, it, vi, beforeEach } from "vitest";
import { mount, flushPromises } from "@vue/test-utils";
import { createPinia, setActivePinia, type Pinia } from "pinia";
import PrimeVue from "primevue/config";
import { useAuthStore } from "@/stores/auth";
import type { DomainResponse, FunctionRouteResponse } from "@/api/functions";

if (typeof window !== "undefined" && !window.matchMedia) {
	window.matchMedia = ((query: string) => ({
		matches: false,
		media: query,
		onchange: null,
		addListener: () => {},
		removeListener: () => {},
		addEventListener: () => {},
		removeEventListener: () => {},
		dispatchEvent: () => false,
	})) as unknown as typeof window.matchMedia;
}

const mocks = vi.hoisted(() => ({
	listRoutes: vi.fn(),
	listDomains: vi.fn(),
}));

vi.mock("@/api/functions", async (importOriginal) => {
	const actual = await importOriginal<typeof import("@/api/functions")>();
	return {
		...actual,
		functionsApi: {
			...actual.functionsApi,
			listRoutes: mocks.listRoutes,
			listDomains: mocks.listDomains,
		},
	};
});

const routeWithAliasPrefixes: FunctionRouteResponse = {
	hostname: "myapp.acme.com",
	pathPrefix: "/",
	address: "acme.default.myapp",
	aliasPrefixes: ["qa", "staging"],
};

const claimedDomain: DomainResponse = {
	id: "dom_1",
	hostname: "acme.com",
	owner: "clt_1",
	createdAt: "2026-01-01T00:00:00Z",
};

let pinia: Pinia;

async function mountTab() {
	const { default: FunctionPublicRoutesTab } = await import(
		"@/pages/functions/FunctionPublicRoutesTab.vue"
	);
	const wrapper = mount(FunctionPublicRoutesTab, {
		props: { address: "acme.default.myapp", hasLiveVersion: true },
		global: {
			plugins: [pinia, PrimeVue],
		},
	});
	await flushPromises();
	return wrapper;
}

describe("FunctionPublicRoutesTab — alias prefixes and derived hostnames (package J3)", () => {
	beforeEach(() => {
		pinia = createPinia();
		setActivePinia(pinia);
		const authStore = useAuthStore();
		authStore.setUser({
			id: "u1",
			email: "a@example.com",
			name: "A",
			clientId: null,
			roles: ["platform:anchor"],
			permissions: [],
			ssoManaged: false,
		});

		mocks.listRoutes.mockReset();
		mocks.listDomains.mockReset();
		mocks.listRoutes.mockResolvedValue([routeWithAliasPrefixes]);
		mocks.listDomains.mockResolvedValue([claimedDomain]);
	});

	it("renders each opted-in alias prefix's derived hostname", async () => {
		const wrapper = await mountTab();

		const text = wrapper.text();
		// mutant: never render the derived hostname — this assertion fails without it.
		expect(text).toContain("qa-myapp.acme.com");
		expect(text).toContain("staging-myapp.acme.com");
	});

	it("a route under a zone claim is 'claimed' (a claim covers every hostname under it)", async () => {
		// The claim is `acme.com`; the route is `myapp.acme.com`. Mutant: match the
		// hostname exactly — the route would read "not claimed".
		const wrapper = await mountTab();
		expect(wrapper.text()).toContain("claimed");
		expect(wrapper.text()).not.toContain("not claimed");
	});

	it("a route under nobody's claim is 'not claimed'", async () => {
		mocks.listDomains.mockResolvedValue([{ ...claimedDomain, hostname: "other.com" }]);
		const wrapper = await mountTab();
		expect(wrapper.text()).toContain("not claimed");
	});

	it("shows 'none' for a route with no opted-in alias prefixes", async () => {
		mocks.listRoutes.mockResolvedValue([
			{ ...routeWithAliasPrefixes, aliasPrefixes: [] },
		]);
		const wrapper = await mountTab();

		expect(wrapper.text()).toContain("none");
		expect(wrapper.text()).not.toContain("qa-myapp.acme.com");
	});
});
