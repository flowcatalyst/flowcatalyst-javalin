// @vitest-environment jsdom
/**
 * Invoke tab (docs/spec/function-ui.md §2.1): no network call. Rendering
 * the tab and clicking a copy button must never touch the API module —
 * this tab only ever writes ready-made commands to the clipboard.
 */

import { describe, expect, it, vi, beforeEach } from "vitest";
import { mount, flushPromises } from "@vue/test-utils";
import PrimeVue from "primevue/config";
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

// Every operation on functionsApi becomes a spy — if the Invoke tab called
// ANY of them, this test would see it.
const apiCallLog: string[] = [];
vi.mock("@/api/functions", async (importOriginal) => {
	const actual = await importOriginal<typeof import("@/api/functions")>();
	const spiedApi: Record<string, unknown> = {};
	for (const key of Object.keys(actual.functionsApi)) {
		spiedApi[key] = vi.fn((...args: unknown[]) => {
			apiCallLog.push(key);
			return (actual.functionsApi as unknown as Record<string, (...a: unknown[]) => unknown>)[
				key
			]?.(...args);
		});
	}
	return { ...actual, functionsApi: spiedApi };
});

if (!("clipboard" in navigator)) {
	Object.defineProperty(navigator, "clipboard", {
		value: { writeText: vi.fn(() => Promise.resolve()) },
		configurable: true,
	});
}

const versions: VersionResponse[] = [
	{
		id: "ver_1",
		version: 1,
		state: "READY",
		digest: "sha256:aaaa",
		artifactRef: "platform://store/1",
		pool: "default",
		warm: false,
		publishedBy: "user_1",
		publishedAt: "2026-09-01T00:00:00Z",
		live: true,
	},
];

async function mountTab() {
	const { default: FunctionInvokeTab } = await import(
		"@/pages/functions/FunctionInvokeTab.vue"
	);
	const wrapper = mount(FunctionInvokeTab, {
		props: { address: "acme.default.hello", versions, liveVersion: 1 },
		global: {
			plugins: [PrimeVue],
			stubs: { Teleport: true },
		},
	});
	await flushPromises();
	return wrapper;
}

describe("FunctionInvokeTab — no network call", () => {
	beforeEach(() => {
		apiCallLog.length = 0;
	});

	it("renders ready-made fcdev/curl commands without calling the API", async () => {
		const wrapper = await mountTab();

		expect(wrapper.get('[data-testid="invoke-live-fcdev"]').text()).toContain(
			"fcdev fn invoke acme.default.hello",
		);
		expect(wrapper.get('[data-testid="invoke-live-curl"]').text()).toContain(
			"acme.default.hello",
		);
		expect(wrapper.get('[data-testid="invoke-version-fcdev"]').text()).toContain(
			"acme.default.hello:1",
		);

		expect(apiCallLog).toEqual([]);
	});

	it("clicking a copy button copies to the clipboard and still calls no API function", async () => {
		const wrapper = await mountTab();

		const copyButtons = wrapper.findAll("button");
		expect(copyButtons.length).toBeGreaterThan(0);
		for (const button of copyButtons) {
			await button.trigger("click");
		}
		await flushPromises();

		expect(navigator.clipboard.writeText).toHaveBeenCalled();
		expect(apiCallLog).toEqual([]);
	});
});
