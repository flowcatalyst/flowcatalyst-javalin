// @vitest-environment jsdom
/**
 * Gap 2 (docs/spec/function-ui.md §2.1, docs/functions.md §12; fixed
 * server-side by S1, function-context.md §1): before a function's first
 * promote, the LIVE manifest has no declared keys at all — `declared` is now
 * server-computed as the union of the live manifest's keys and the newest
 * non-retired version's ("the candidate"), and `declaredBy` names, per key,
 * which version(s) declared it. The tab no longer fans out `listVersions` +
 * `getVersion` itself to compute this.
 *
 * - with no live version and `declaredBy` naming version 1 for GREETING/
 *   API_KEY, the tab renders both keys and the SETTINGS_MISSING banner
 *   (mutant: ignore `declaredBy`/`declared` from the response — the rows
 *   would be empty and the GREETING/API_KEY/banner assertions fail)
 * - a key declared only by a non-live version is tagged "declared by v<n>";
 *   a key the live version itself declares is not (mutant: drop the
 *   `liveVersion` comparison — GREETING, declared by live v1, would wrongly
 *   grow a "declared by v1" tag too, and the presence/absence assertions
 *   below would both fail)
 * - setting a value calls functionsApi.setConfig with the whole map
 * - the "Add key" row: a key typed there is sent (mutant: drop it from the
 *   request — the toHaveBeenCalledWith assertion below fails)
 */

import { describe, expect, it, vi, beforeEach } from "vitest";
import { mount, flushPromises } from "@vue/test-utils";
import { createPinia, setActivePinia, type Pinia } from "pinia";
import PrimeVue from "primevue/config";
import ConfirmationService from "primevue/confirmationservice";
import { useAuthStore } from "@/stores/auth";
import type { ConfigResponse, SecretListResponse } from "@/api/functions";

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
	getConfig: vi.fn(),
	setConfig: vi.fn(),
	listSecrets: vi.fn(),
	setSecret: vi.fn(),
	deleteSecret: vi.fn(),
}));

vi.mock("@/api/functions", async (importOriginal) => {
	const actual = await importOriginal<typeof import("@/api/functions")>();
	return {
		...actual,
		functionsApi: {
			...actual.functionsApi,
			getConfig: mocks.getConfig,
			setConfig: mocks.setConfig,
			listSecrets: mocks.listSecrets,
			setSecret: mocks.setSecret,
			deleteSecret: mocks.deleteSecret,
		},
	};
});

// No live version: `declared`/`declaredBy` come from the candidate (v1)
// alone.
const noLiveConfig: ConfigResponse = {
	values: {},
	declared: ["GREETING"],
	missing: ["GREETING"],
	declaredBy: [{ version: 1, keys: ["GREETING"] }],
};
const noLiveSecrets: SecretListResponse = {
	keys: [],
	declared: ["API_KEY"],
	missing: ["API_KEY"],
	declaredBy: [{ version: 1, keys: ["API_KEY"] }],
};

// A live version (1) that declares GREETING, plus a candidate (2) that adds
// EXTRA — the "declared by v2" tag scenario.
const liveAndCandidateConfig: ConfigResponse = {
	values: { GREETING: "hi" },
	declared: ["GREETING", "EXTRA"],
	missing: ["EXTRA"],
	declaredBy: [
		{ version: 1, keys: ["GREETING"] },
		{ version: 2, keys: ["EXTRA"] },
	],
};

let pinia: Pinia;

async function mountTab(liveVersion?: number) {
	const { default: FunctionConfigSecretsTab } = await import(
		"@/pages/functions/FunctionConfigSecretsTab.vue"
	);
	const wrapper = mount(FunctionConfigSecretsTab, {
		props: { address: "hello.default.hello", liveVersion },
		global: {
			plugins: [pinia, PrimeVue, ConfirmationService],
			stubs: { Teleport: true },
		},
	});
	await flushPromises();
	return wrapper;
}

describe("FunctionConfigSecretsTab — declared keys from the server (gap 2 / S1)", () => {
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
			permissions: ["platform:function:secret:manage"],
			ssoManaged: false,
		});

		mocks.getConfig.mockReset();
		mocks.setConfig.mockReset();
		mocks.listSecrets.mockReset();
		mocks.setSecret.mockReset();
		mocks.deleteSecret.mockReset();

		mocks.getConfig.mockResolvedValue(noLiveConfig);
		mocks.listSecrets.mockResolvedValue(noLiveSecrets);
	});

	it("renders GREETING/API_KEY from `declared` and shows the SETTINGS_MISSING banner", async () => {
		const wrapper = await mountTab();

		const text = wrapper.text();
		expect(text).toContain("GREETING");
		expect(text).toContain("API_KEY");
		expect(text).not.toContain("not declared");

		// Both declared keys have no value/aren't set — SETTINGS_MISSING,
		// taken straight from the server's `missing` (config: 1 + secrets: 1).
		expect(text).toContain("SETTINGS_MISSING");
		expect(text).toContain("2 declared keys have no value");
	});

	it('tags a key declared only by a non-live version "declared by v<n>", and never a key the live version declares', async () => {
		mocks.getConfig.mockResolvedValue(liveAndCandidateConfig);
		mocks.listSecrets.mockResolvedValue({ keys: [], declared: [], missing: [], declaredBy: [] });

		const wrapper = await mountTab(1);
		const text = wrapper.text();

		// EXTRA is declared only by the candidate (v2, not the live v1).
		expect(text).toContain("declared by v2");
		// GREETING is declared by the live version (1) — must not be tagged.
		expect(text).not.toContain("declared by v1");
	});

	it("setting a config value calls setConfig with the whole map", async () => {
		mocks.setConfig.mockResolvedValue({
			values: { GREETING: "hi" },
			declared: [],
			missing: [],
			declaredBy: [],
		});
		const wrapper = await mountTab();

		const editButtons = wrapper.findAll("button").filter((b) => b.find("span.pi-pencil").exists());
		expect(editButtons.length).toBeGreaterThan(0);
		await editButtons[0]!.trigger("click");
		await flushPromises();

		const valueInput = wrapper.find(".kv-table tbody input");
		await valueInput.setValue("hi");
		await flushPromises();

		const saveButton = wrapper.findAll("button").find((b) => b.find("span.pi-check").exists());
		await saveButton!.trigger("click");
		await flushPromises();

		expect(mocks.setConfig).toHaveBeenCalledWith("hello.default.hello", {
			values: { GREETING: "hi" },
		});
	});

	it('the "Add key" row sends a key typed there', async () => {
		mocks.setConfig.mockResolvedValue({
			values: { EXTRA_KEY: "extra-value" },
			declared: [],
			missing: [],
			declaredBy: [],
		});
		const wrapper = await mountTab();

		await wrapper.get('[data-testid="add-config-key-input"]').setValue("EXTRA_KEY");
		await wrapper.get('[data-testid="add-config-value-input"]').setValue("extra-value");
		await wrapper.get('[data-testid="add-config-key-button"]').trigger("click");
		await flushPromises();

		expect(mocks.setConfig).toHaveBeenCalledWith("hello.default.hello", {
			values: { EXTRA_KEY: "extra-value" },
		});
	});
});
