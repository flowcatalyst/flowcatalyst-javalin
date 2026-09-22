// @vitest-environment jsdom
/**
 * U5 (docs/spec/function-ui.md §2.1): Promote is enabled only for a READY
 * version; Retire is disabled for the live version (and never available for
 * an already-RETIRED one). Mutant: enable always.
 */

import { describe, expect, it, vi, beforeEach } from "vitest";
import { mount, flushPromises } from "@vue/test-utils";
import { createPinia, setActivePinia, type Pinia } from "pinia";
import PrimeVue from "primevue/config";
import ConfirmationService from "primevue/confirmationservice";
import { useAuthStore } from "@/stores/auth";
import type { VersionResponse } from "@/api/functions";

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
	listVersions: vi.fn(),
	getVersion: vi.fn(),
	promoteAlias: vi.fn(),
	retireVersion: vi.fn(),
}));

vi.mock("@/api/functions", async (importOriginal) => {
	const actual = await importOriginal<typeof import("@/api/functions")>();
	return {
		...actual,
		functionsApi: {
			...actual.functionsApi,
			listVersions: mocks.listVersions,
			getVersion: mocks.getVersion,
			promoteAlias: mocks.promoteAlias,
			retireVersion: mocks.retireVersion,
		},
	};
});

const readyNotLive: VersionResponse = {
	id: "ver_1",
	version: 1,
	state: "READY",
	digest: "sha256:aaaa",
	artifactRef: "platform://store/1",
	pool: "default",
	warm: false,
	publishedBy: "user_1",
	publishedAt: "2026-09-01T00:00:00Z",
	live: false,
};

const publishedNotReady: VersionResponse = {
	id: "ver_2",
	version: 2,
	state: "PUBLISHED",
	digest: "sha256:bbbb",
	artifactRef: "platform://store/2",
	pool: "default",
	warm: false,
	publishedBy: "user_1",
	publishedAt: "2026-09-02T00:00:00Z",
	live: false,
};

const liveVersion: VersionResponse = {
	id: "ver_3",
	version: 3,
	state: "READY",
	digest: "sha256:cccc",
	artifactRef: "platform://store/3",
	pool: "default",
	warm: false,
	publishedBy: "user_1",
	publishedAt: "2026-09-03T00:00:00Z",
	live: true,
};

let pinia: Pinia;

async function mountTab() {
	const { default: FunctionVersionsTab } = await import(
		"@/pages/functions/FunctionVersionsTab.vue"
	);
	const wrapper = mount(FunctionVersionsTab, {
		props: { address: "acme.default.hello" },
		global: {
			// Reuse the SAME pinia instance the authStore was populated on in
			// beforeEach — passing a fresh createPinia() here would give the
			// mounted component an empty, unauthenticated store instead.
			plugins: [pinia, PrimeVue, ConfirmationService],
			stubs: { Teleport: true },
		},
	});
	await flushPromises();
	return wrapper;
}

describe("FunctionVersionsTab — Promote/Retire gating (U5)", () => {
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
			permissions: [
				"platform:function:version:publish",
				"platform:function:alias:promote",
			],
			ssoManaged: false,
		});

		mocks.listVersions.mockReset();
		mocks.getVersion.mockReset();
		mocks.promoteAlias.mockReset();
		mocks.retireVersion.mockReset();
		mocks.listVersions.mockResolvedValue([readyNotLive, publishedNotReady, liveVersion]);
	});

	it("enables Promote only for the READY, non-live version and disables it for a PUBLISHED (not-ready) version", async () => {
		const wrapper = await mountTab();

		const rows = wrapper.findAll("tbody > tr");
		expect(rows.length).toBe(3);

		// v1 = READY, not live → Promote enabled.
		const row1 = rows.find((r) => r.text().includes("v1"));
		const promote1 = row1?.findAll("button").find((b) => b.text() === "Promote");
		expect(promote1).toBeTruthy();
		expect(promote1?.attributes("disabled")).toBeUndefined();

		// v2 = PUBLISHED, not READY → Promote disabled.
		const row2 = rows.find((r) => r.text().includes("v2"));
		const promote2 = row2?.findAll("button").find((b) => b.text() === "Promote");
		expect(promote2).toBeTruthy();
		expect(promote2?.attributes("disabled")).toBeDefined();
	});

	it("disables Promote for the version that is already live — the platform would answer ALIAS_UNCHANGED", async () => {
		const wrapper = await mountTab();
		const liveRow = wrapper.findAll("tbody > tr").find((r) => r.text().includes("v3"));
		const promoteLive = liveRow?.findAll("button").find((b) => b.text() === "Promote");
		expect(promoteLive).toBeTruthy();
		expect(promoteLive?.attributes("disabled")).toBeDefined();
	});

	it("disables Retire for the live version even though it is READY", async () => {
		const wrapper = await mountTab();

		const rows = wrapper.findAll("tbody > tr");
		const liveRow = rows.find((r) => r.text().includes("v3"));
		const retireLive = liveRow?.findAll("button").find((b) => b.text() === "Retire");
		expect(retireLive).toBeTruthy();
		expect(retireLive?.attributes("disabled")).toBeDefined();

		// v1 is READY and not live → Retire enabled.
		const row1 = rows.find((r) => r.text().includes("v1"));
		const retire1 = row1?.findAll("button").find((b) => b.text() === "Retire");
		expect(retire1).toBeTruthy();
		expect(retire1?.attributes("disabled")).toBeUndefined();
	});
});
