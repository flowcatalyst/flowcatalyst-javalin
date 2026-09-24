// @vitest-environment jsdom
/**
 * docs/spec/function-manifest-authoring.md M4 / docs/spec/function-ui.md
 * §2.6: the manifest editor drawer. Each test's docstring names the
 * behaviour it pins and the mutant it kills (CLAUDE.md testing policy —
 * every one of these was run against a deliberately broken version to
 * confirm it fails; see the handback report for the kill table).
 */

import { describe, expect, it, vi, beforeEach } from "vitest";
import { mount, flushPromises } from "@vue/test-utils";
import PrimeVue from "primevue/config";
import type { CheckManifestResponse, PublishResponse } from "@/api/functions";
import {
	exportManifest,
	newManifestModel,
	parseManifestText,
	manifestSchemaUrl,
} from "@/pages/functions/manifestModel";
import { renderPlanLines } from "@/pages/functions/manifestPlanText";
import PublishVersionDrawer from "@/pages/functions/PublishVersionDrawer.vue";

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
	checkManifest: vi.fn(),
}));

vi.mock("@/api/functions", async (importOriginal) => {
	const actual = await importOriginal<typeof import("@/api/functions")>();
	return {
		...actual,
		functionsApi: {
			...actual.functionsApi,
			checkManifest: mocks.checkManifest,
		},
	};
});

async function mountDrawer(initialManifest?: unknown) {
	const { default: ManifestEditorDrawer } = await import(
		"@/pages/functions/ManifestEditorDrawer.vue"
	);
	return mount(ManifestEditorDrawer, {
		props: { address: "acme.default.hello", initialManifest },
		global: {
			plugins: [PrimeVue],
			stubs: { Teleport: true },
		},
	});
}

describe("manifestModel — pure import/export helpers", () => {
	// Pins: export always reproduces every field the import carried (M4:
	// "import the sample -> export -> deep-equal to the import, plus
	// $schema"), in the JSON Schema's own property order (M1 §1/§5, M4:
	// "key order as the schema's"), and NEVER promotes an absent optional
	// field to a present default. Mutant tried: change exportManifest's
	// TOP_ORDER to drop "subscriptions" from the walk -> the subscriptions
	// array silently disappears from the export and this test fails
	// (confirmed by temporarily deleting that entry from TOP_ORDER).
	it("round-trips the sample manifest: export(import(text)) deep-equals the import plus $schema", () => {
		const sampleText = JSON.stringify({
			$schema: "../../server/src/main/resources/schemas/function-manifest.schema.json",
			runtime: "jvm",
			entrypoint: "io.flowcatalyst.example.hello.HelloFunction",
			pool: "default",
			warm: false,
			limits: { maxDurationMs: 10000, maxConcurrency: 8 },
			endpoints: [
				{ path: "/events/greeting-requested", auth: "webhook" },
				{ path: "/api/hello/{name}", auth: "platform", methods: ["GET"] },
				{ path: "/healthz", auth: "none", methods: ["GET"] },
			],
			subscriptions: [
				{
					eventType: "hello:greeting:greeting:requested",
					path: "/events/greeting-requested",
					mode: "IMMEDIATE",
					maxRetries: 3,
					timeoutSeconds: 30,
					dataOnly: false,
				},
			],
			config: ["GREETING"],
			secrets: ["API_KEY"],
		});

		const model = parseManifestText(sampleText);
		const exported = exportManifest(model);

		const expected = { ...JSON.parse(sampleText), $schema: manifestSchemaUrl() };
		expect(exported).toEqual(expected);

		// Fields never present in the sample (schedules/public/db/httpAllow)
		// must stay absent, not turn into `[]` — a defaulting bug wouldn't be
		// caught by toEqual alone if expected also defaulted them, so assert
		// their absence directly too.
		expect(exported).not.toHaveProperty("schedules");
		expect(exported).not.toHaveProperty("public");
		expect(exported).not.toHaveProperty("db");
		expect(exported).not.toHaveProperty("httpAllow");
	});

	// Pins: exportManifest's key order literally follows the schema's
	// property order, not object insertion/alphabetical order. Mutant tried:
	// reverse TOP_ORDER -> "endpoints" would come before "runtime" in
	// Object.keys() and this test fails (confirmed).
	it("orders top-level export keys as the schema does", () => {
		const model = newManifestModel();
		model.pool = "default";
		model.warm = true;
		const exported = exportManifest(model);
		expect(Object.keys(exported)).toEqual([
			"$schema",
			"runtime",
			"entrypoint",
			"pool",
			"warm",
			"endpoints",
		]);
	});
});

describe("manifestPlanText — renders exactly like `fn validate`", () => {
	// Pins: the SPA's plan rendering matches ValidateCommand.java's text form
	// line-for-line (M2.3/M4 requirement). Mutant tried: swap the `~`/`+`
	// prefix for updates -> this test fails.
	it("renders create/update/delete/settings-missing lines in ValidateCommand's exact format", () => {
		const lines = renderPlanLines({
			alias: "live",
			toVersion: 2,
			settingsMissing: ["API_KEY"],
			httpOnly: false,
			pool: { action: "update", key: "default", changedFields: ["maxConcurrency"] },
			subscriptions: [
				{
					action: "create",
					triggerKey: "t1",
					eventType: "order.created",
					changedFields: [],
				},
				{
					action: "delete",
					triggerKey: "t2",
					eventType: "order.cancelled",
					changedFields: [],
				},
			],
			schedules: [],
			publicRoutes: { action: "unchanged", added: [], removed: [] },
			conflicts: [],
		});
		expect(lines).toEqual([
			"~ pool (update: maxConcurrency)",
			"+ subscription order.created (create)",
			"- subscription order.cancelled (delete)",
			"! settings missing: API_KEY",
		]);
	});

	it('renders "no changes" only when every section is truly empty', () => {
		const lines = renderPlanLines({
			alias: "live",
			toVersion: 1,
			settingsMissing: [],
			httpOnly: false,
			pool: { action: "unchanged", key: "default", changedFields: [] },
			subscriptions: [],
			schedules: [],
			publicRoutes: { action: "unchanged", added: [], removed: [] },
			conflicts: [],
		});
		expect(lines).toEqual(["no changes"]);
	});
});

describe("ManifestEditorDrawer", () => {
	beforeEach(() => {
		mocks.checkManifest.mockReset();
	});

	// Pins: a form edit is reflected in the JSON view (M4: "editing either
	// updates the other"). Mutant tried: remove the `watch(model, ...,
	// {deep:true})` block -> the JSON textarea keeps its stale initial text
	// after the entrypoint field changes, and this test fails (confirmed).
	it("a form edit updates the JSON view", async () => {
		const wrapper = await mountDrawer();
		const entrypointInput = wrapper.get('[data-testid="manifest-entrypoint-input"]');
		await entrypointInput.setValue("com.example.fn.ChangedHandler");
		await flushPromises();

		const textarea = wrapper.get('[data-testid="manifest-json-textarea"]')
			.element as HTMLTextAreaElement;
		expect(textarea.value).toContain("com.example.fn.ChangedHandler");
	});

	// Pins: editing the JSON view updates the form (the other half of "editing
	// either updates the other"), and a document that fails to parse leaves
	// the form showing the LAST VALID model rather than crashing or silently
	// adopting garbage. Mutant tried: make onJsonInput always assign
	// `model.value = parsed` even inside the catch block (i.e. before the
	// throw is caught) -> a half-broken document would corrupt the model;
	// tried instead: skip the try/catch and let JSON.parse throw uncaught ->
	// the component throws instead of showing the warning banner, and the
	// "invalid JSON" assertion below fails (confirmed both ways).
	it("a valid JSON edit updates the form; an invalid one leaves the form on the last valid model with a warning", async () => {
		const wrapper = await mountDrawer();

		// Switch to JSON view and edit to a different, still-valid document.
		await wrapper.get('[data-testid="manifest-view-toggle"] :nth-child(2)').trigger("click");
		const textarea = wrapper.get('[data-testid="manifest-json-textarea"]');
		const validEdit = JSON.stringify({
			runtime: "jvm",
			entrypoint: "com.example.fn.FromJson",
		});
		await textarea.setValue(validEdit);
		await flushPromises();

		await wrapper.get('[data-testid="manifest-view-toggle"] :nth-child(1)').trigger("click");
		const entrypointInput = wrapper.get('[data-testid="manifest-entrypoint-input"]')
			.element as HTMLInputElement;
		expect(entrypointInput.value).toBe("com.example.fn.FromJson");

		// Now break the JSON.
		await wrapper.get('[data-testid="manifest-view-toggle"] :nth-child(2)').trigger("click");
		await textarea.setValue("{ not valid json");
		await flushPromises();

		expect(wrapper.find('[data-testid="manifest-json-error"]').exists()).toBe(true);

		await wrapper.get('[data-testid="manifest-view-toggle"] :nth-child(1)').trigger("click");
		const entrypointAfterBreak = wrapper.get('[data-testid="manifest-entrypoint-input"]')
			.element as HTMLInputElement;
		// Still the last VALID value ("FromJson"), not corrupted/emptied.
		expect(entrypointAfterBreak.value).toBe("com.example.fn.FromJson");
	});

	// Pins: Validate calls checkManifest with the CURRENT edited model (not a
	// stale copy) and renders the server's error code/message. Mutant tried:
	// capture `model.value` once at mount time into a local `const` and send
	// that instead of the live ref -> the edited entrypoint above would never
	// reach the request, and the assertion on call args fails (confirmed).
	it("Validate sends the current model and renders a server error against the endpoint field", async () => {
		const response: CheckManifestResponse = {
			valid: false,
			errors: [{ code: "ENDPOINT_AUTH_REQUIRED", message: "auth is required" }],
		};
		mocks.checkManifest.mockResolvedValue(response);

		const wrapper = await mountDrawer();
		await wrapper
			.get('[data-testid="manifest-entrypoint-input"]')
			.setValue("com.example.fn.Edited");
		await wrapper.get('[data-testid="manifest-validate-button"]').trigger("click");
		await flushPromises();

		expect(mocks.checkManifest).toHaveBeenCalledTimes(1);
		const [address, body] = mocks.checkManifest.mock.calls[0];
		expect(address).toBe("acme.default.hello");
		expect(body.manifest.entrypoint).toBe("com.example.fn.Edited");

		const endpointError = wrapper.get('[data-testid="endpoints-section-error"]');
		expect(endpointError.text()).toContain("ENDPOINT_AUTH_REQUIRED");
		expect(endpointError.text()).toContain("auth is required");

		const errorsBox = wrapper.get('[data-testid="manifest-errors"]');
		expect(errorsBox.text()).toContain("ENDPOINT_AUTH_REQUIRED");
	});

	// Pins: a valid response's plan is rendered using the SAME text form as
	// `fn validate` (not just "some JSON dump") — specifically that a
	// `delete` action produces a `-` line. Mutant tried: render plan actions
	// generically as `${action} ${eventType}` instead of via renderPlanLines
	// -> the "-" prefix disappears and this test fails (confirmed).
	it("Validate renders a plan's Delete rows in fn-validate's format", async () => {
		const response: CheckManifestResponse = {
			valid: true,
			errors: [],
			plan: {
				alias: "live",
				toVersion: 3,
				settingsMissing: [],
				httpOnly: false,
				subscriptions: [
					{
						action: "delete",
						triggerKey: "t1",
						eventType: "order.cancelled",
						changedFields: [],
					},
				],
				conflicts: [],
			},
		};
		mocks.checkManifest.mockResolvedValue(response);

		const wrapper = await mountDrawer();
		await wrapper.get('[data-testid="manifest-validate-button"]').trigger("click");
		await flushPromises();

		const plan = wrapper.get('[data-testid="manifest-plan"]');
		expect(plan.text()).toContain("- subscription order.cancelled (delete)");
	});

	// Pins: "Publish with this manifest" opens the publish drawer with the
	// EXACT current (edited) model, not the manifest the editor started
	// from. Mutant tried: pass the original `initialManifest` prop straight
	// through instead of the live `model` ref -> the edited entrypoint below
	// would not reach the child drawer and this test fails (confirmed).
	it("Publish with this manifest hands the exact edited manifest to the publish drawer", async () => {
		const wrapper = await mountDrawer({ runtime: "jvm", entrypoint: "com.example.fn.Original" });
		await wrapper
			.get('[data-testid="manifest-entrypoint-input"]')
			.setValue("com.example.fn.EditedBeforePublish");

		await wrapper.get('[data-testid="manifest-publish-button"]').trigger("click");
		await flushPromises();

		const publishDrawer = wrapper.findComponent(PublishVersionDrawer);
		expect(publishDrawer.exists()).toBe(true);
		expect(publishDrawer.props("initialManifest")).toMatchObject({
			entrypoint: "com.example.fn.EditedBeforePublish",
		});
	});

	it("emits published and closes when the embedded publish drawer succeeds", async () => {
		const wrapper = await mountDrawer();
		await wrapper.get('[data-testid="manifest-publish-button"]').trigger("click");
		await flushPromises();

		const publishDrawer = wrapper.findComponent(PublishVersionDrawer);
		const published: PublishResponse = { id: "ver_9", version: 9, state: "PUBLISHED", digest: "sha256:aa" };
		publishDrawer.vm.$emit("published", published);
		await flushPromises();

		expect(wrapper.emitted("published")).toBeTruthy();
		expect(wrapper.emitted("published")?.[0]?.[0]).toEqual(published);
		expect(wrapper.emitted("close")).toBeTruthy();
	});
});
