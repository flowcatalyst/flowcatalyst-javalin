// @vitest-environment jsdom
/**
 * Create Service Account: application access is a three-way choice that
 * matches the server's CreateCommand — none (the default: a new account
 * starts with no application access, Go a8ff165), every application
 * (`allApplications`), or one application it is confined to
 * (`applicationId`). Owner requests 2026-09-24.
 *
 * - default "None": neither `applicationId` nor `allApplications` is sent
 *   (mutant: default to "ALL" — fails on the `allApplications` assertion)
 * - "All applications" sends `allApplications: true` and no applicationId
 * - "One application" requires picking an application before Create is
 *   enabled (mutant: drop the validity check — the button stays enabled)
 *   and then sends exactly that `applicationId`
 * - leaving "One application" hides the field and never resurrects a prior
 *   pick on return (mutant: drop the clear-on-leave watcher — the Select
 *   would still carry the stale value and Create would be enabled)
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

// Exact match (asterisk stripped) — "Application" and "Application Access"
// are two different fields, and a substring match would confuse them.
function fieldByLabel(wrapper: VueWrapper, labelText: string) {
	return wrapper.findAll(".fc-form-field").find((f) => {
		const label = f.find(".fc-field-label").text().replace(/\*/g, "").trim();
		return label === labelText;
	});
}

async function chooseAccess(wrapper: VueWrapper, access: "NONE" | "ALL" | "ONE") {
	await fieldByLabel(wrapper, "Application Access")!
		.findComponent({ name: "Select" })
		.vm.$emit("update:modelValue", access);
	await flushPromises();
}

function applicationSelect(wrapper: VueWrapper) {
	return fieldByLabel(wrapper, "Application")!.findComponent({ name: "Select" });
}

function createButton(wrapper: VueWrapper) {
	return wrapper.findAll("button").find((b) => b.text() === "Create Service Account")!;
}

describe("ServiceAccountCreateDrawer — application access", () => {
	beforeEach(() => {
		pinia = createPinia();
		setActivePinia(pinia);
		mocks.create.mockReset();
		mocks.listClients.mockReset().mockResolvedValue({ clients: CLIENTS, total: 0 });
		mocks.listApplications.mockReset().mockResolvedValue({ applications: APPLICATIONS, total: 1 });
		mocks.push.mockReset();
		mocks.replace.mockReset();
	});

	async function submit(wrapper: VueWrapper) {
		await createButton(wrapper).trigger("click");
		await flushPromises();
		expect(mocks.create).toHaveBeenCalledTimes(1);
		return mocks.create.mock.calls[0][0];
	}

	it("defaults to none: no application field shown, and create sends neither applicationId nor allApplications", async () => {
		mocks.create.mockResolvedValue(createdServiceAccount);
		const wrapper = await mountDrawer();

		expect(fieldByLabel(wrapper, "Application")).toBeUndefined();

		await fillRequiredFields(wrapper);
		const request = await submit(wrapper);
		expect(request.applicationId).toBeUndefined();
		expect(request.allApplications).toBeUndefined();
	});

	it("all applications sends allApplications and no applicationId", async () => {
		mocks.create.mockResolvedValue(createdServiceAccount);
		const wrapper = await mountDrawer();
		await fillRequiredFields(wrapper);

		await chooseAccess(wrapper, "ALL");
		expect(fieldByLabel(wrapper, "Application")).toBeUndefined();

		const request = await submit(wrapper);
		expect(request.allApplications).toBe(true);
		expect(request.applicationId).toBeUndefined();
	});

	it("one application disables Create until one is picked, then enables it", async () => {
		const wrapper = await mountDrawer();
		await fillRequiredFields(wrapper);
		expect(createButton(wrapper).attributes("disabled")).toBeUndefined();

		await chooseAccess(wrapper, "ONE");

		expect(fieldByLabel(wrapper, "Application")).toBeTruthy();
		expect(createButton(wrapper).attributes("disabled")).toBeDefined();

		await applicationSelect(wrapper).vm.$emit("update:modelValue", "app_1");
		await flushPromises();

		expect(createButton(wrapper).attributes("disabled")).toBeUndefined();
	});

	it("one application with an application picked sends exactly that applicationId and not allApplications", async () => {
		mocks.create.mockResolvedValue(createdServiceAccount);
		const wrapper = await mountDrawer();
		await fillRequiredFields(wrapper);

		await chooseAccess(wrapper, "ONE");
		await applicationSelect(wrapper).vm.$emit("update:modelValue", "app_1");
		await flushPromises();

		const request = await submit(wrapper);
		expect(request.applicationId).toBe("app_1");
		expect(request.allApplications).toBeUndefined();
	});

	it("leaving one application hides the field and a later return does not resurrect the pick (mutant: skip the clear-on-leave watcher)", async () => {
		const wrapper = await mountDrawer();
		await fillRequiredFields(wrapper);

		await chooseAccess(wrapper, "ONE");
		await applicationSelect(wrapper).vm.$emit("update:modelValue", "app_1");
		await flushPromises();

		await chooseAccess(wrapper, "NONE");
		expect(fieldByLabel(wrapper, "Application")).toBeUndefined();
		expect(createButton(wrapper).attributes("disabled")).toBeUndefined();

		await chooseAccess(wrapper, "ONE");
		// Without the clearing watcher, the Select would still be bound to the
		// stale "app_1" and Create would already be enabled here.
		expect(createButton(wrapper).attributes("disabled")).toBeDefined();
		expect(applicationSelect(wrapper).props("modelValue")).toBeFalsy();
	});
});
