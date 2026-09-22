// @vitest-environment jsdom
/**
 * U7 (docs/spec/function-ui.md §2.1): after setting a secret, the DOM
 * contains "set" for that key and never contains the value string.
 * Mutant: echo the value back into the DOM.
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

const SECRET_VALUE = "sk_live_totally-secret-value-12345";

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

const emptyConfig: ConfigResponse = { values: {}, declared: [], missing: [] };

const secretsBeforeSet: SecretListResponse = {
	keys: [],
	declared: ["API_KEY"],
	missing: ["API_KEY"],
};

const secretsAfterSet: SecretListResponse = {
	keys: [{ key: "API_KEY", updatedAt: "2026-09-22T00:00:00Z", updatedBy: "user_1" }],
	declared: ["API_KEY"],
	missing: [],
};

let pinia: Pinia;

async function mountTab() {
	const { default: FunctionConfigSecretsTab } = await import(
		"@/pages/functions/FunctionConfigSecretsTab.vue"
	);
	const wrapper = mount(FunctionConfigSecretsTab, {
		props: { address: "acme.default.hello" },
		global: {
			plugins: [pinia, PrimeVue, ConfirmationService],
			stubs: { Teleport: true },
		},
	});
	await flushPromises();
	return wrapper;
}

describe("FunctionConfigSecretsTab — secret value never rendered (U7)", () => {
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

		mocks.getConfig.mockResolvedValue(emptyConfig);
		mocks.listSecrets.mockResolvedValue(secretsBeforeSet);
		// No versions in this test — API_KEY's declared/missing come from the
		// live manifest alone (secretsBeforeSet above); the union logic under
		// test elsewhere (function-config-secrets-declared.test.ts) covers the
		// candidate-version path.
		mocks.listVersions.mockResolvedValue([]);
		mocks.getVersion.mockResolvedValue({});
	});

	it('reads "not set" before, "set" after saving, and the typed value never appears in the DOM', async () => {
		mocks.setSecret.mockResolvedValue(undefined);

		const wrapper = await mountTab();

		expect(wrapper.text()).toContain("not set");
		expect(wrapper.text()).not.toContain(SECRET_VALUE);

		// Open the set/replace form for API_KEY.
		const setButtons = wrapper.findAll("button").filter((b) => b.text() === "Set");
		expect(setButtons.length).toBeGreaterThan(0);
		await setButtons[0]!.trigger("click");
		await flushPromises();

		const input = wrapper.get('[data-testid="secret-value-input"]');
		await input.setValue(SECRET_VALUE);
		await flushPromises();

		// The value is present in the (untyped-as-text) password input's model
		// while editing, but must never leak into rendered text content.
		expect(wrapper.text()).not.toContain(SECRET_VALUE);

		// After save, the API returns the updated listing (no `keys[].value`
		// field exists on the wire at all — SecretKeyResponse never carries one).
		mocks.listSecrets.mockResolvedValue(secretsAfterSet);

		await wrapper.get('[data-testid="secret-save-button"]').trigger("click");
		await flushPromises();

		expect(mocks.setSecret).toHaveBeenCalledWith(
			"acme.default.hello",
			"API_KEY",
			{ value: SECRET_VALUE },
			expect.anything(),
		);

		// The edit form must be torn down — its value input is gone.
		expect(wrapper.find('[data-testid="secret-value-input"]').exists()).toBe(false);

		expect(wrapper.text()).toContain("set");
		expect(wrapper.text()).not.toContain(SECRET_VALUE);
		expect(wrapper.html()).not.toContain(SECRET_VALUE);
	});
});
