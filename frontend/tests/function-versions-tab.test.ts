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
import type { AliasResponse, VersionResponse } from "@/api/functions";

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
	// The component calls `functionsApi.promote` (the alias-defaulting
	// wrapper), not `promoteAlias` directly — mocked at that boundary so the
	// assertion pins what the COMPONENT actually calls, not an implementation
	// detail one module away (`promote` delegates to `promoteAlias`
	// internally, but that self-reference is not visible to this mock).
	promote: vi.fn(),
	retireVersion: vi.fn(),
	listAliases: vi.fn(),
	deleteAlias: vi.fn(),
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
			promote: mocks.promote,
			retireVersion: mocks.retireVersion,
			listAliases: mocks.listAliases,
			deleteAlias: mocks.deleteAlias,
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

const liveAlias: AliasResponse = {
	alias: "live",
	version: 3,
	versionId: "ver_3",
	updatedBy: "user_1",
	updatedAt: "2026-09-03T00:00:00Z",
};

const namedAlias: AliasResponse = {
	alias: "qa",
	version: 1,
	versionId: "ver_1",
	updatedBy: "user_1",
	updatedAt: "2026-09-04T00:00:00Z",
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
		mocks.promote.mockReset();
		mocks.retireVersion.mockReset();
		mocks.listAliases.mockReset();
		mocks.deleteAlias.mockReset();
		mocks.listVersions.mockResolvedValue([readyNotLive, publishedNotReady, liveVersion]);
		mocks.listAliases.mockResolvedValue([liveAlias, namedAlias]);
		mocks.promote.mockResolvedValue({ alias: "live", version: 1, versionId: "ver_1" });
	});

	it("enables Promote only for the READY, non-live version and disables it for a PUBLISHED (not-ready) version", async () => {
		const wrapper = await mountTab();

		// Two tables now render (versions, then aliases) — scope to the first.
		const rows = wrapper.findAll("table")[0].findAll("tbody > tr");
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

	// package J2/J4 (spec `function-zones-and-aliases.md` §2: "two different
	// aliases may legitimately name the same version at once"): the ROW-level
	// Promote action stays enabled for the live version too, because a
	// DIFFERENT named alias (e.g. "qa") might still want to point at it — only
	// the DIALOG's own submit is disabled, and only once the typed alias is
	// one that already names this exact version (ALIAS_UNCHANGED). Mutant:
	// gate on `v.live` again — this fails because it would disable Promote
	// for v3 outright, never even opening the dialog to prove the per-alias
	// distinction.
	it("keeps Promote enabled for the live version, but disables the dialog's own submit only for an alias that already points there", async () => {
		const wrapper = await mountTab();
		const liveRow = wrapper.findAll("table")[0].findAll("tbody > tr").find((r) => r.text().includes("v3"));
		const promoteLive = liveRow?.findAll("button").find((b) => b.text() === "Promote");
		expect(promoteLive).toBeTruthy();
		expect(promoteLive?.attributes("disabled")).toBeUndefined();

		await promoteLive?.trigger("click");
		await flushPromises();

		const dialog = wrapper.find(".p-dialog");
		expect(dialog.exists()).toBe(true);

		// Default alias is "live", which already points at v3 (ver_3) —
		// ALIAS_UNCHANGED — the dialog's own submit is disabled.
		const submitDefault = dialog.findAll("button").find((b) => b.text() === "Promote");
		expect(submitDefault?.attributes("disabled")).toBeDefined();

		// "qa" points at v1 (ver_1), not v3 — typing it re-enables submit.
		const input = dialog.find("#promoteAlias");
		await input.setValue("qa");
		const submitQa = dialog.findAll("button").find((b) => b.text() === "Promote");
		expect(submitQa?.attributes("disabled")).toBeUndefined();
	});

	it("disables Retire for the live version even though it is READY", async () => {
		const wrapper = await mountTab();

		const rows = wrapper.findAll("table")[0].findAll("tbody > tr");
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

	// spec `function-zones-and-aliases.md` §6: the Promote drawer opens with
	// an alias field (defaulting to `live`) and sends whatever name is typed
	// — mutant: ignore the field and always promote `live`. Types "staging",
	// not "qa" — the fixture's own `namedAlias` already points "qa" at v1, so
	// typing "qa" here would hit the dialog's ALIAS_UNCHANGED no-op guard
	// (`promoteWouldBeNoOp`, its own separately-pinned behaviour) and never
	// reach `functionsApi.promote` at all, which would make this assertion
	// pass for the wrong reason (or not run) rather than pinning "the typed
	// name is sent".
	it("promote dialog sends the typed alias name, not always live", async () => {
		const wrapper = await mountTab();
		const row1 = wrapper.findAll("table")[0].findAll("tbody > tr").find((r) => r.text().includes("v1"));
		const promote1 = row1?.findAll("button").find((b) => b.text() === "Promote");
		await promote1?.trigger("click");
		await flushPromises();

		const dialog = wrapper.find(".p-dialog");
		expect(dialog.exists()).toBe(true);
		const input = dialog.find("#promoteAlias");
		expect(input.exists()).toBe(true);
		expect((input.element as HTMLInputElement).value).toBe("live");

		await input.setValue("staging");
		const dialogPromote = dialog.findAll("button").find((b) => b.text() === "Promote");
		expect(dialogPromote?.attributes("disabled")).toBeUndefined();
		await dialogPromote?.trigger("click");
		await flushPromises();

		expect(mocks.promote).toHaveBeenCalledWith("acme.default.hello", 1, "staging");
	});

	// U1 (spec §8): the Aliases table disables Delete for `live` and enables
	// it for a named alias — mutant: gate both the same way (always
	// enabled/always disabled).
	it("disables the alias Delete button for live and enables it for a named alias", async () => {
		const wrapper = await mountTab();

		const aliasTables = wrapper.findAll("table");
		const aliasesTable = aliasTables[aliasTables.length - 1];
		const aliasRows = aliasesTable.findAll("tbody > tr");

		const liveRow = aliasRows.find((r) => r.text().includes("live"));
		const liveDelete = liveRow?.findAll("button").find((b) => b.text() === "Delete");
		expect(liveDelete).toBeTruthy();
		expect(liveDelete?.attributes("disabled")).toBeDefined();

		const qaRow = aliasRows.find((r) => r.text().includes("qa"));
		const qaDelete = qaRow?.findAll("button").find((b) => b.text() === "Delete");
		expect(qaDelete).toBeTruthy();
		expect(qaDelete?.attributes("disabled")).toBeUndefined();
	});
});
