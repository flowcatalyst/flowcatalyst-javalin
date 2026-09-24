// @vitest-environment jsdom
/**
 * Create Service Account: application scope was previously not settable at
 * all — every account was created unscoped ("all applications"), silently.
 * A toggle now lets the operator confine the account to one application;
 * off (the default) still creates an unscoped account, matching the
 * server's own `CreateCommand.applicationId` default (owner request,
 * 2026-09-24).
 *
 * - off by default: `applicationId` is NOT sent, and no application is
 *   selected (mutant: send a stray applicationId even when off — this
 *   fails because `toHaveBeenCalledWith` no longer matches)
 * - switching on requires picking an application before Create is enabled
 *   (mutant: drop the validity check — this fails because the button would
 *   stay enabled with no selection)
 * - switching on then picking an application sends exactly that
 *   `applicationId`
 * - switching back off hides the Application field and re-validates
 * - switching off then on again never resurrects a prior pick (mutant: drop
 *   the clear-on-toggle-off watcher — the Select would still carry the
 *   stale value and Create would be enabled with nothing actually chosen
 *   in the now-fresh field)
 */

import { describe, expect, it, vi, beforeEach } from "vitest";
import { mount, flushPromises, type VueWrapper } from "@vue/test-utils";
import { createPinia, setActivePinia, type Pinia } from "pinia";
import PrimeVue from "primevue/config";
import ConfirmationService from "primevue/confirmationservice";
import type { CreateServiceAccountResponse } from "@/api/service-accounts";
import type { Application } from "@/api/applications";
import type { Client } from "@/api/clients";

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
	create: vi.fn(),
	listClients: vi.fn(),
	listApplications: vi.fn(),
	push: vi.fn(),
	replace: vi.fn(),
}));

vi.mock("@/api/service-accounts", async (importOriginal) => {
	const actual = await importOriginal<typeof import("@/api/service-accounts")>();
	return {
		...actual,
		serviceAccountsApi: { ...actual.serviceAccountsApi, create: mocks.create },
	};
});

vi.mock("@/api/clients", async (importOriginal) => {
	const actual = await importOriginal<typeof import("@/api/clients")>();
	return {
		...actual,
		clientsApi: { ...actual.clientsApi, list: mocks.listClients },
	};
});

vi.mock("@/api/applications", async (importOriginal) => {
	const actual = await importOriginal<typeof import("@/api/applications")>();
	return {
		...actual,
		applicationsApi: { ...actual.applicationsApi, list: mocks.listApplications },
	};
});

vi.mock("vue-router", async (importOriginal) => {
	const actual = await importOriginal<typeof import("vue-router")>();
	return {
		...actual,
		useRoute: () => ({ query: {}, params: {} }),
		useRouter: () => ({ push: mocks.push, replace: mocks.replace }),
		onBeforeRouteLeave: () => {},
		onBeforeRouteUpdate: () => {},
	};
});

const APPLICATIONS: Application[] = [
	{
		id: "app_1",
		code: "acme",
		name: "Acme",
		active: true,
		hasLoginClient: false,
		type: "APPLICATION",
		createdAt: "2026-09-01T00:00:00Z",
		updatedAt: "2026-09-01T00:00:00Z",
	} as Application,
];

const CLIENTS: Client[] = [];

const createdServiceAccount = {
	serviceAccount: { id: "sac_1", code: "svc-1", name: "Svc", authType: "BEARER_TOKEN" },
	principalId: "prn_1",
	oauth: { clientId: "oac_1", clientSecret: "secret" },
	webhook: { authToken: "tok", signingSecret: "sig" },
} as unknown as CreateServiceAccountResponse;

let pinia: Pinia;

async function mountDrawer(): Promise<VueWrapper> {
	const { default: ServiceAccountCreateDrawer } = await import(
		"@/pages/service-accounts/ServiceAccountCreateDrawer.vue"
	);
	const wrapper = mount(ServiceAccountCreateDrawer, {
		global: {
			plugins: [pinia, PrimeVue, ConfirmationService],
			stubs: { Teleport: true },
		},
	});
	await flushPromises();
	return wrapper;
}

function fillRequiredFields(wrapper: VueWrapper) {
	return Promise.all([
		wrapper.find('input[placeholder="My Service Account"]').setValue("My Account"),
		wrapper.find('input[placeholder="my-service-account"]').setValue("my-account"),
	]);
}

// Exact match (asterisk stripped) — "Application" and "Application Scope"
// are two different fields, and a substring match would confuse them.
function fieldByLabel(wrapper: VueWrapper, labelText: string) {
	return wrapper.findAll(".fc-form-field").find((f) => {
		const label = f.find(".fc-field-label").text().replace(/\*/g, "").trim();
		return label === labelText;
	});
}

function applicationScopeToggle(wrapper: VueWrapper) {
	return fieldByLabel(wrapper, "Application Scope")!.find(
		'input[type="checkbox"][role="switch"]',
	);
}

function applicationSelect(wrapper: VueWrapper) {
	return fieldByLabel(wrapper, "Application")!.findComponent({ name: "Select" });
}

function createButton(wrapper: VueWrapper) {
	return wrapper.findAll("button").find((b) => b.text() === "Create Service Account")!;
}

describe("ServiceAccountCreateDrawer — application scope toggle", () => {
	beforeEach(() => {
		pinia = createPinia();
		setActivePinia(pinia);
		mocks.create.mockReset();
		mocks.listClients.mockReset().mockResolvedValue({ clients: CLIENTS, total: 0 });
		mocks.listApplications.mockReset().mockResolvedValue({ applications: APPLICATIONS, total: 1 });
		mocks.push.mockReset();
		mocks.replace.mockReset();
	});

	it("is off by default: no application field shown, and create sends no applicationId", async () => {
		mocks.create.mockResolvedValue(createdServiceAccount);
		const wrapper = await mountDrawer();

		expect(fieldByLabel(wrapper, "Application")).toBeUndefined();

		await fillRequiredFields(wrapper);
		await createButton(wrapper).trigger("click");
		await flushPromises();

		expect(mocks.create).toHaveBeenCalledTimes(1);
		const request = mocks.create.mock.calls[0][0];
		expect(request.applicationId).toBeUndefined();
	});

	it("disables Create when switched on with no application picked, and enables it once one is picked", async () => {
		const wrapper = await mountDrawer();
		await fillRequiredFields(wrapper);
		expect(createButton(wrapper).attributes("disabled")).toBeUndefined();

		await applicationScopeToggle(wrapper).trigger("change");
		await flushPromises();

		expect(fieldByLabel(wrapper, "Application")).toBeTruthy();
		expect(createButton(wrapper).attributes("disabled")).toBeDefined();

		await applicationSelect(wrapper).vm.$emit("update:modelValue", "app_1");
		await flushPromises();

		expect(createButton(wrapper).attributes("disabled")).toBeUndefined();
	});

	it("switched on with an application picked sends exactly that applicationId", async () => {
		mocks.create.mockResolvedValue(createdServiceAccount);
		const wrapper = await mountDrawer();
		await fillRequiredFields(wrapper);

		await applicationScopeToggle(wrapper).trigger("change");
		await flushPromises();
		await applicationSelect(wrapper).vm.$emit("update:modelValue", "app_1");
		await flushPromises();

		await createButton(wrapper).trigger("click");
		await flushPromises();

		expect(mocks.create).toHaveBeenCalledTimes(1);
		const request = mocks.create.mock.calls[0][0];
		expect(request.applicationId).toBe("app_1");
	});

	it("switching back off immediately drops the picked application (the field disappears)", async () => {
		const wrapper = await mountDrawer();
		await fillRequiredFields(wrapper);

		await applicationScopeToggle(wrapper).trigger("change");
		await flushPromises();
		await applicationSelect(wrapper).vm.$emit("update:modelValue", "app_1");
		await flushPromises();

		await applicationScopeToggle(wrapper).trigger("change");
		await flushPromises();

		expect(fieldByLabel(wrapper, "Application")).toBeUndefined();
		// Create is valid again with no application field in play.
		expect(createButton(wrapper).attributes("disabled")).toBeUndefined();
	});

	it("does not resurrect a prior pick when toggled off then on again (mutant: skip the clear-on-toggle-off watcher)", async () => {
		const wrapper = await mountDrawer();
		await fillRequiredFields(wrapper);

		await applicationScopeToggle(wrapper).trigger("change"); // on
		await flushPromises();
		await applicationSelect(wrapper).vm.$emit("update:modelValue", "app_1");
		await flushPromises();

		await applicationScopeToggle(wrapper).trigger("change"); // off
		await flushPromises();
		await applicationScopeToggle(wrapper).trigger("change"); // on again
		await flushPromises();

		// Without the clearing watcher, the Select would still be bound to the
		// stale "app_1" and Create would already be enabled here.
		expect(createButton(wrapper).attributes("disabled")).toBeDefined();
		expect(applicationSelect(wrapper).props("modelValue")).toBeFalsy();
	});
});
