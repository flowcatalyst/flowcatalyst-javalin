// @vitest-environment jsdom
/**
 * U6 (docs/spec/function-ui.md §6): a function whose `live` alias is absent
 * (e.g. the live version's manifest is corrupt — function-api.md §4.4, "this
 * exact case took the platform down once") renders "—" instead of crashing
 * the whole list, and the rest of the row (and other rows) still render.
 */

import { describe, expect, it, vi, beforeEach } from "vitest";
import { mount, flushPromises } from "@vue/test-utils";
import { createPinia, setActivePinia } from "pinia";
import PrimeVue from "primevue/config";
import ConfirmationService from "primevue/confirmationservice";
import type { FunctionResponse } from "@/api/functions";

// jsdom has no matchMedia implementation; PrimeVue's Select reads it on
// mount (responsive breakpoints). A minimal stub is enough for a headless
// render — nothing here exercises actual media-query behaviour.
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

vi.mock("vue-router", async (importOriginal) => {
	const actual = await importOriginal<typeof import("vue-router")>();
	return {
		...actual,
		useRoute: () => ({ query: {} }),
		useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
	};
});

const withoutLive: FunctionResponse = {
	id: "fn_1",
	address: "acme.default.hello",
	applicationCode: "acme",
	serviceName: "default",
	name: "hello",
	applicationId: "app_1",
	runtime: "jvm",
	status: "ACTIVE",
	createdAt: "2026-09-01T00:00:00Z",
	updatedAt: "2026-09-02T00:00:00Z",
};

const withLive: FunctionResponse = {
	id: "fn_2",
	address: "acme.default.other",
	applicationCode: "acme",
	serviceName: "default",
	name: "other",
	applicationId: "app_1",
	runtime: "jvm",
	status: "ACTIVE",
	live: { version: 3, versionId: "v_3" },
	createdAt: "2026-09-01T00:00:00Z",
	updatedAt: "2026-09-02T00:00:00Z",
};

const mocks = vi.hoisted(() => ({
	list: vi.fn(),
	pools: vi.fn(),
}));

vi.mock("@/api/functions", async (importOriginal) => {
	const actual = await importOriginal<typeof import("@/api/functions")>();
	return {
		...actual,
		functionsApi: { ...actual.functionsApi, list: mocks.list, pools: mocks.pools },
	};
});

vi.mock("@/api/applications", () => ({
	applicationsApi: {
		list: vi.fn(async () => ({ applications: [], total: 0 })),
	},
}));

vi.mock("@/api/clients", () => ({
	clientsApi: {
		list: vi.fn(async () => ({ clients: [], total: 0 })),
		get: vi.fn(),
	},
}));

async function mountList() {
	const { default: FunctionListPage } = await import(
		"@/pages/functions/FunctionListPage.vue"
	);
	const wrapper = mount(FunctionListPage, {
		global: {
			plugins: [createPinia(), PrimeVue, ConfirmationService],
			stubs: { RouterView: true },
		},
	});
	await flushPromises();
	return wrapper;
}

describe("FunctionListPage — live version column", () => {
	beforeEach(() => {
		setActivePinia(createPinia());
		mocks.list.mockReset();
		mocks.pools.mockReset();
		mocks.list.mockResolvedValue({
			data: [withoutLive, withLive],
			page: 0,
			size: 20,
			total: 2,
			total_pages: 1,
		});
		mocks.pools.mockResolvedValue([]);
	});

	it("renders a row with no live version as —, and still renders the other row", async () => {
		const wrapper = await mountList();

		expect(mocks.list).toHaveBeenCalled();

		const text = wrapper.text();
		// Both addresses rendered — the missing-live row didn't take the list down.
		expect(text).toContain("acme.default.hello");
		expect(text).toContain("acme.default.other");
		// The row with no live version reads em-dash, not a crash / blank.
		expect(text).toContain("—");
		// The row with a live version shows its version number.
		expect(text).toContain("v3");
	});
});
