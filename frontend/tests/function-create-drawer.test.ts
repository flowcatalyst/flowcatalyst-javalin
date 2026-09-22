// @vitest-environment jsdom
/**
 * Gap 1 (docs/spec/function-ui.md §2.1, docs/functions.md §12): the create
 * drawer is the only UI path to `POST /api/functions`.
 *
 * - the address preview updates live from the three labels
 * - submit calls functionsApi.create with EXACTLY the request built from the
 *   form (mutant: drop a field from the request — e.g. omit `runtime` —
 *   and this test fails because `toHaveBeenCalledWith` no longer matches)
 * - a FUNCTION_EXISTS rejection renders in the form and does NOT navigate
 *   (mutant: swallow the error in a bare catch that does nothing — this
 *   test fails because the error message never appears in the DOM; a
 *   mutant that navigates anyway on error is caught by the replace-not-
 *   called assertion)
 */

import { describe, expect, it, vi, beforeEach } from "vitest";
import { mount, flushPromises } from "@vue/test-utils";
import { createPinia, setActivePinia, type Pinia } from "pinia";
import PrimeVue from "primevue/config";
import ConfirmationService from "primevue/confirmationservice";
import { useAuthStore } from "@/stores/auth";
import { ApiError } from "@/api/client";
import type { FunctionResponse } from "@/api/functions";

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
	push: vi.fn(),
	replace: vi.fn(),
}));

vi.mock("@/api/functions", async (importOriginal) => {
	const actual = await importOriginal<typeof import("@/api/functions")>();
	return {
		...actual,
		functionsApi: { ...actual.functionsApi, create: mocks.create },
	};
});

// useDrawerRoute (composables/useDrawerRoute.ts) pulls in onBeforeRouteLeave/
// onBeforeRouteUpdate, which register guards against the ACTIVE router — none
// exists in this headless mount, so they're stubbed to no-ops; useRoute/
// useRouter are stubbed the same way functions-list.test.ts stubs them.
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

const createdFunction: FunctionResponse = {
	id: "fn_1",
	address: "hello.default.hello",
	applicationCode: "hello",
	serviceName: "default",
	name: "hello",
	applicationId: "app_1",
	runtime: "jvm",
	status: "ACTIVE",
	createdAt: "2026-09-22T00:00:00Z",
	updatedAt: "2026-09-22T00:00:00Z",
};

let pinia: Pinia;

async function mountDrawer() {
	const { default: FunctionCreateDrawer } = await import(
		"@/pages/functions/FunctionCreateDrawer.vue"
	);
	const wrapper = mount(FunctionCreateDrawer, {
		global: {
			plugins: [pinia, PrimeVue, ConfirmationService],
			stubs: { Teleport: true },
		},
	});
	await flushPromises();
	return wrapper;
}

function fillAddress(wrapper: Awaited<ReturnType<typeof mountDrawer>>) {
	return Promise.all([
		wrapper.find('input[placeholder="acme"]').setValue("hello"),
		wrapper.find('input[placeholder="default"]').setValue("default"),
		wrapper.find('input[placeholder="hello"]').setValue("hello"),
	]);
}

describe("FunctionCreateDrawer", () => {
	beforeEach(() => {
		pinia = createPinia();
		setActivePinia(pinia);
		const authStore = useAuthStore();
		// Anchor user (no home client): platform-owned is the default toggle,
		// so the submitted request's clientId is undefined without any extra
		// interaction — isolates the assertion to the three address fields +
		// runtime + description.
		authStore.setUser({
			id: "u1",
			email: "a@example.com",
			name: "A",
			clientId: null,
			roles: ["platform:anchor"],
			permissions: ["platform:function:function:manage"],
			ssoManaged: false,
		});

		mocks.create.mockReset();
		mocks.push.mockReset();
		mocks.replace.mockReset();
	});

	it("previews the address live from the three labels", async () => {
		const wrapper = await mountDrawer();

		expect(wrapper.text()).toContain("—");

		await fillAddress(wrapper);

		expect(wrapper.text()).toContain("hello.default.hello");
	});

	it("submits functionsApi.create with exactly the request built from the form", async () => {
		mocks.create.mockResolvedValue(createdFunction);
		const wrapper = await mountDrawer();

		await fillAddress(wrapper);
		await wrapper.find('textarea').setValue("A test function");

		const submit = wrapper.findAll("button").find((b) => b.text() === "Create Function");
		expect(submit).toBeTruthy();
		await submit!.trigger("click");
		await flushPromises();

		expect(mocks.create).toHaveBeenCalledTimes(1);
		expect(mocks.create).toHaveBeenCalledWith({
			applicationCode: "hello",
			serviceName: "default",
			name: "hello",
			runtime: "jvm",
			description: "A test function",
			clientId: undefined,
		});
		expect(mocks.replace).toHaveBeenCalledWith(
			expect.objectContaining({ path: "/functions/hello.default.hello" }),
		);
	});

	it("renders a FUNCTION_EXISTS rejection in the form and does not navigate", async () => {
		mocks.create.mockRejectedValue(
			new ApiError(
				"function 'hello.default.hello' already exists",
				409,
				"FUNCTION_EXISTS",
			),
		);
		const wrapper = await mountDrawer();

		await fillAddress(wrapper);
		const submit = wrapper.findAll("button").find((b) => b.text() === "Create Function");
		await submit!.trigger("click");
		await flushPromises();

		expect(wrapper.text()).toContain("FUNCTION_EXISTS");
		expect(wrapper.text()).toContain("already exists");
		expect(mocks.replace).not.toHaveBeenCalled();
		expect(mocks.push).not.toHaveBeenCalled();
	});
});
