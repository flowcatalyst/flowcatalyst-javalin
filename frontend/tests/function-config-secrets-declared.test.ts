// @vitest-environment jsdom
/**
 * Gap 2 (docs/spec/function-ui.md §2.1, docs/functions.md §12): before a
 * function's first promote, the LIVE manifest has no declared keys at all —
 * the tab must derive them from the union of the live manifest and every
 * non-retired version's own manifest instead (`listVersions` + `getVersion`
 * per row, same call FunctionVersionsTab.vue makes on expand).
 *
 * - with no live version and one READY version whose manifest declares
 *   GREETING/API_KEY, the tab renders both keys (marked from that version)
 *   and the SETTINGS_MISSING banner (mutant: read only the live manifest —
 *   configRows/secretRows would be empty and this test's "GREETING"/
 *   "API_KEY"/banner assertions would fail; mutant: banner ignores the
 *   candidate — missingCount would be 0 and the banner assertion fails)
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
import type { ConfigResponse, Manifest, SecretListResponse, VersionResponse } from "@/api/functions";

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
	listVersions: vi.fn(),
	getVersion: vi.fn(),
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
			listVersions: mocks.listVersions,
			getVersion: mocks.getVersion,
		},
	};
});

const noLiveConfig: ConfigResponse = { values: {}, declared: [], missing: [] };
const noLiveSecrets: SecretListResponse = { keys: [], declared: [], missing: [] };

const readyVersion: VersionResponse = {
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

const readyManifest: Manifest = {
	runtime: "jvm",
	entrypoint: "io.flowcatalyst.example.hello.HelloFunction",
	pool: "default",
	warm: true,
	limits: { maxDurationMs: 10000, maxConcurrency: 8 },
	endpoints: [],
	subscriptions: [],
	schedules: [],
	public: [],
	config: ["GREETING"],
	secrets: ["API_KEY"],
	db: [],
	httpAllow: [],
};

let pinia: Pinia;

async function mountTab() {
	const { default: FunctionConfigSecretsTab } = await import(
		"@/pages/functions/FunctionConfigSecretsTab.vue"
	);
	const wrapper = mount(FunctionConfigSecretsTab, {
		props: { address: "hello.default.hello" },
		global: {
			plugins: [pinia, PrimeVue, ConfirmationService],
			stubs: { Teleport: true },
		},
	});
	await flushPromises();
	return wrapper;
}

describe("FunctionConfigSecretsTab — declared keys from the candidate version (gap 2)", () => {
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
		mocks.listVersions.mockReset();
		mocks.getVersion.mockReset();

		mocks.getConfig.mockResolvedValue(noLiveConfig);
		mocks.listSecrets.mockResolvedValue(noLiveSecrets);
		mocks.listVersions.mockResolvedValue([readyVersion]);
		mocks.getVersion.mockResolvedValue({ ...readyVersion, manifest: readyManifest });
	});

	it("renders GREETING/API_KEY from the READY candidate and shows the SETTINGS_MISSING banner", async () => {
		const wrapper = await mountTab();

		expect(mocks.listVersions).toHaveBeenCalledWith("hello.default.hello");
		expect(mocks.getVersion).toHaveBeenCalledWith("hello.default.hello", 1);

		const text = wrapper.text();
		expect(text).toContain("GREETING");
		expect(text).toContain("API_KEY");
		// Marked as coming from the candidate version, not "live" (no live
		// version exists in this scenario).
		expect(text).toContain("v1 (ready)");
		expect(text).not.toContain("not declared");

		// Both declared keys have no value/aren't set — SETTINGS_MISSING.
		expect(text).toContain("SETTINGS_MISSING");
		expect(text).toContain("2 declared keys have no value");
	});

	it("setting a config value calls setConfig with the whole map", async () => {
		mocks.setConfig.mockResolvedValue({
			values: { GREETING: "hi" },
			declared: [],
			missing: [],
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
