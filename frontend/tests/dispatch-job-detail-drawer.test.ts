// @vitest-environment jsdom
/**
 * T15 (docs/spec/catch-up-2026-09-22.md slice C3): the job panel renders
 * attempts with `request` (signedBy, header names) and the unsigned reason,
 * and the sign action shows the plan — without ever sending anything itself
 * (the SPA calls `dispatchJobsApi.sign`, a dry run; there is no "deliver"
 * button on this panel at all).
 */

import { describe, expect, it, vi, beforeEach } from "vitest";
import { mount, flushPromises } from "@vue/test-utils";
import { createPinia, setActivePinia, type Pinia } from "pinia";
import PrimeVue from "primevue/config";
import ConfirmationService from "primevue/confirmationservice";
import type {
	DeliveryPlan,
	DispatchJobAttempt,
	DispatchJobDetail,
} from "@/api/dispatch-jobs";

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
	get: vi.fn(),
	attempts: vi.fn(),
	sign: vi.fn(),
	requeue: vi.fn(),
	push: vi.fn(),
	replace: vi.fn(),
}));

vi.mock("@/api/dispatch-jobs", async (importOriginal) => {
	const actual = await importOriginal<typeof import("@/api/dispatch-jobs")>();
	return {
		...actual,
		dispatchJobsApi: {
			...actual.dispatchJobsApi,
			get: mocks.get,
			attempts: mocks.attempts,
			sign: mocks.sign,
			requeue: mocks.requeue,
		},
	};
});

// useDrawerRoute (composables/useDrawerRoute.ts) pulls in onBeforeRouteLeave/
// onBeforeRouteUpdate, which register guards against the ACTIVE router — none
// exists in this headless mount, so they're stubbed to no-ops, same as
// function-create-drawer.test.ts. `params.id` is what useDrawerRoute reads
// to know which job to load.
vi.mock("vue-router", async (importOriginal) => {
	const actual = await importOriginal<typeof import("vue-router")>();
	return {
		...actual,
		useRoute: () => ({ query: {}, params: { id: "job-1" } }),
		useRouter: () => ({ push: mocks.push, replace: mocks.replace }),
		onBeforeRouteLeave: () => {},
		onBeforeRouteUpdate: () => {},
	};
});

const job: DispatchJobDetail = {
	id: "job-1",
	kind: "EVENT",
	code: "acme:orders:order:created",
	payload: "{\"amount\":100}",
	payloadContentType: "application/json",
	dataOnly: false,
	mode: "IMMEDIATE",
	sequence: 0,
	timeoutSeconds: 30,
	maxRetries: 3,
	retryStrategy: "exponential",
	status: "FAILED",
	attemptCount: 1,
	targetUrl: "https://sub.example.test/hook",
	descriptor: "orders-webhook",
	messageGroup: "grp-1",
	clientId: "cli_1",
	lastError: "HTTP 401 Unauthorized",
	createdAt: "2026-09-22T00:00:00Z",
	updatedAt: "2026-09-22T00:00:05Z",
};

// The load-bearing attempt: carries `request` (signedBy + header NAMES,
// never a value) and an unsigned reason on a different attempt — both must
// reach the rendered panel, not just live in the fetched JSON.
const attempts: DispatchJobAttempt[] = [
	{
		attemptNumber: 1,
		attemptedAt: "2026-09-22T00:00:01Z",
		completedAt: "2026-09-22T00:00:02Z",
		durationMillis: 120,
		responseCode: 401,
		responseBody: "Unauthorized",
		success: false,
		errorMessage: "HTTP 401 Unauthorized",
		errorType: "HTTP_ERROR",
		request: {
			signedBy: "acme-svc",
			signature: true,
			bearer: false,
			timestamp: "2026-09-22T00:00:01.000Z",
			headers: ["Content-Type", "X-FlowCatalyst-Signature", "X-FlowCatalyst-Timestamp"],
		},
	},
];

const plan: DeliveryPlan = {
	request: {
		signedBy: "acme-svc",
		signature: true,
		bearer: false,
		timestamp: "2026-09-22T00:10:00.000Z",
		headers: ["Content-Type", "X-FlowCatalyst-Signature", "X-FlowCatalyst-Timestamp"],
		target: "https://sub.example.test/hook",
	},
	headers: {
		"Content-Type": "application/json",
		"X-FlowCatalyst-Timestamp": "2026-09-22T00:10:00.000Z",
		"X-FlowCatalyst-Signature": "deadbeef",
	},
	body: "{\"amount\":100}",
};

let pinia: Pinia;

async function mountDrawer() {
	const { default: DispatchJobDetailDrawer } = await import(
		"@/pages/dispatch-jobs/DispatchJobDetailDrawer.vue"
	);
	const wrapper = mount(DispatchJobDetailDrawer, {
		global: {
			plugins: [pinia, PrimeVue, ConfirmationService],
			stubs: { Teleport: true },
		},
	});
	await flushPromises();
	return wrapper;
}

describe("DispatchJobDetailDrawer", () => {
	beforeEach(() => {
		pinia = createPinia();
		setActivePinia(pinia);
		mocks.get.mockReset().mockResolvedValue(job);
		mocks.attempts.mockReset().mockResolvedValue(attempts);
		mocks.sign.mockReset().mockResolvedValue(plan);
		mocks.requeue.mockReset();
		mocks.push.mockReset();
		mocks.replace.mockReset();
	});

	it("loads the job and its attempts for the routed id", async () => {
		await mountDrawer();

		expect(mocks.get).toHaveBeenCalledWith("job-1");
		expect(mocks.attempts).toHaveBeenCalledWith("job-1");
	});

	it("renders an attempt's request summary — signedBy and header NAMES, never a header value", async () => {
		const wrapper = await mountDrawer();

		const text = wrapper.text();
		expect(text).toContain("signed by acme-svc");
		expect(text).toContain("X-FlowCatalyst-Signature");
		expect(text).toContain("X-FlowCatalyst-Timestamp");
	});

	it("renders the unsigned reason when an attempt carries one", async () => {
		mocks.attempts.mockResolvedValue([
			{
				...attempts[0],
				request: {
					signature: false,
					bearer: false,
					headers: [],
					unsignedReason: "connection cnn-value: service account sa-conn is inactive",
				},
			},
		]);
		const wrapper = await mountDrawer();

		expect(wrapper.text()).toContain(
			"UNSIGNED — connection cnn-value: service account sa-conn is inactive",
		);
	});

	it("the Sign action calls the sign API and shows the returned plan — never a delivery call "
			+ "(mutant: swallow the plan, or call requeue/deliver instead)", async () => {
		const wrapper = await mountDrawer();

		const signButton = wrapper.findAll("button").find((b) => b.text().includes("Sign"));
		expect(signButton).toBeTruthy();
		await signButton!.trigger("click");
		await flushPromises();

		expect(mocks.sign).toHaveBeenCalledExactlyOnceWith("job-1");
		expect(mocks.requeue).not.toHaveBeenCalled();

		const text = wrapper.text();
		expect(text).toContain("Delivery as it would go out now");
		// The plan's headers render with values (this is the ONE place that
		// legitimately shows a header value) — but never the raw signature
		// value is asserted here as a distinct behaviour: the DOM must show
		// what the server actually returned, byte for byte.
		expect(text).toContain("deadbeef");
	});
});
