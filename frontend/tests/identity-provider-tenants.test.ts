// @vitest-environment jsdom
/**
 * Create Identity Provider: allowed tenant IDs for multi-tenant OIDC
 * providers. A multi-tenant provider must pin its accepted Entra tenants
 * (`tid`) either here or on each email-domain mapping — otherwise the server
 * refuses the save with 400 TENANT_PIN_REQUIRED.
 *
 * - multi-tenant on, two tenant ids added: the create request carries
 *   exactly those two ids (mutant: never send allowedTenantIds — fails on
 *   the populated-array assertion; mutant: always send the field — covered
 *   by the "off" case below)
 * - multi-tenant off: `allowedTenantIds` is never sent, even when the field
 *   held values before the checkbox was unticked (mutant: key the payload
 *   off the field's list being non-empty instead of the multi-tenant flag —
 *   the field's data survives being hidden, so this would still send it)
 * - a blank entry, and a duplicate of an entry already added, are silently
 *   ignored — the final sent list has exactly the distinct, trimmed entries
 *   (mutant: drop the blank/duplicate guard in addAllowedTenantId)
 */

import { describe, expect, it, vi, beforeEach } from "vitest";
import { mount, flushPromises, type VueWrapper } from "@vue/test-utils";
import { createPinia, setActivePinia, type Pinia } from "pinia";
import PrimeVue from "primevue/config";
import ConfirmationService from "primevue/confirmationservice";
import type { IdentityProvider } from "@/api/identity-providers";
import type { Client } from "@/api/clients";
import type { RoleListResponse } from "@/api/roles";

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
	listRoles: vi.fn(),
	push: vi.fn(),
	replace: vi.fn(),
}));

vi.mock("@/api/identity-providers", async (importOriginal) => {
	const actual =
		await importOriginal<typeof import("@/api/identity-providers")>();
	return {
		...actual,
		identityProvidersApi: {
			...actual.identityProvidersApi,
			create: mocks.create,
		},
	};
});

vi.mock("@/api/clients", async (importOriginal) => {
	const actual = await importOriginal<typeof import("@/api/clients")>();
	return {
		...actual,
		clientsApi: { ...actual.clientsApi, list: mocks.listClients },
	};
});

vi.mock("@/api/roles", async (importOriginal) => {
	const actual = await importOriginal<typeof import("@/api/roles")>();
	return {
		...actual,
		rolesApi: { ...actual.rolesApi, list: mocks.listRoles },
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

const CLIENTS: Client[] = [];
const ROLES: RoleListResponse = { items: [], total: 0 };

const createdProvider = {
	id: "idp_1",
	code: "azure",
	name: "Azure AD",
} as unknown as IdentityProvider;

let pinia: Pinia;

async function mountDrawer(): Promise<VueWrapper> {
	const { default: IdentityProviderCreateDrawer } = await import(
		"@/pages/authentication/identity-providers/IdentityProviderCreateDrawer.vue"
	);
	const wrapper = mount(IdentityProviderCreateDrawer, {
		global: {
			plugins: [pinia, PrimeVue, ConfirmationService],
			stubs: { Teleport: true },
		},
	});
	await flushPromises();
	return wrapper;
}

async function fillRequiredFields(wrapper: VueWrapper) {
	await wrapper.find("#code").setValue("azure");
	await wrapper.find("#name").setValue("Azure AD");
	await wrapper.find("#clientId").setValue("client-123");
	await wrapper
		.find("#issuerUrl")
		.setValue("https://login.microsoftonline.com/organizations/v2.0");
	await flushPromises();
}

function checkboxField(wrapper: VueWrapper, labelText: string) {
	return wrapper.findAll(".checkbox-field").find((f) => f.text().includes(labelText));
}

async function setMultiTenant(wrapper: VueWrapper, value: boolean) {
	await checkboxField(wrapper, "Multi-Tenant Mode")!
		.findComponent({ name: "Checkbox" })
		.vm.$emit("update:modelValue", value);
	await flushPromises();
}

// Locates the "Allowed Tenant IDs" field by its label, the same exact-match
// approach the service-account drawer test uses to avoid confusing it with
// other fields.
function tenantIdField(wrapper: VueWrapper) {
	return wrapper.findAll(".field").find((f) => {
		const label = f.find("label");
		return label.exists() && label.text() === "Allowed Tenant IDs";
	});
}

async function addTenantId(wrapper: VueWrapper, tenantId: string) {
	const field = tenantIdField(wrapper)!;
	await field.find("input").setValue(tenantId);
	await field.find("button").trigger("click");
	await flushPromises();
}

function createButton(wrapper: VueWrapper) {
	return wrapper
		.findAll("button")
		.find((b) => b.text() === "Create Identity Provider")!;
}

async function submit(wrapper: VueWrapper) {
	await createButton(wrapper).trigger("click");
	await flushPromises();
	expect(mocks.create).toHaveBeenCalledTimes(1);
	return mocks.create.mock.calls[0][0];
}

describe("IdentityProviderCreateDrawer — allowed tenant IDs", () => {
	beforeEach(() => {
		pinia = createPinia();
		setActivePinia(pinia);
		mocks.create.mockReset().mockResolvedValue(createdProvider);
		mocks.listClients.mockReset().mockResolvedValue({ clients: CLIENTS, total: 0 });
		mocks.listRoles.mockReset().mockResolvedValue(ROLES);
		mocks.push.mockReset();
		mocks.replace.mockReset();
	});

	it("multi-tenant on with two tenant ids sends exactly those two", async () => {
		const wrapper = await mountDrawer();
		await fillRequiredFields(wrapper);

		await setMultiTenant(wrapper, true);
		expect(tenantIdField(wrapper)).toBeTruthy();

		await addTenantId(wrapper, "tenant-aaa");
		await addTenantId(wrapper, "tenant-bbb");

		const request = await submit(wrapper);
		expect(request.allowedTenantIds).toEqual(["tenant-aaa", "tenant-bbb"]);
	});

	it("multi-tenant off never sends allowedTenantIds, even if the field held values first", async () => {
		const wrapper = await mountDrawer();
		await fillRequiredFields(wrapper);

		await setMultiTenant(wrapper, true);
		await addTenantId(wrapper, "tenant-aaa");

		// Turn multi-tenant back off — the field disappears, but its data
		// stays in form state (this is the case a naive "list non-empty"
		// check would get wrong).
		await setMultiTenant(wrapper, false);
		expect(tenantIdField(wrapper)).toBeUndefined();

		const request = await submit(wrapper);
		expect(request.allowedTenantIds).toBeUndefined();
	});

	it("a blank entry and a duplicate are not added", async () => {
		const wrapper = await mountDrawer();
		await fillRequiredFields(wrapper);
		await setMultiTenant(wrapper, true);

		await addTenantId(wrapper, "   "); // blank after trim
		await addTenantId(wrapper, "tenant-aaa");
		await addTenantId(wrapper, "tenant-aaa"); // duplicate

		const request = await submit(wrapper);
		expect(request.allowedTenantIds).toEqual(["tenant-aaa"]);
	});
});
