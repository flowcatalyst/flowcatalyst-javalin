// @vitest-environment jsdom
/**
 * U3/U4 (docs/spec/function-ui.md §2.2, §6): the publish drawer sha256's
 * the chosen jar in the browser, uploads it BEFORE it publishes, and
 * publishes with the artifactRef the UPLOAD returned — never a locally
 * built string. A failed upload surfaces its code/message/details in the
 * drawer and never calls publish.
 */

import { describe, expect, it, vi, beforeEach } from "vitest";
import { mount, flushPromises } from "@vue/test-utils";
import PrimeVue from "primevue/config";
import { ApiError } from "@/api/client";
import type { PublishResponse, UploadArtifactResponse } from "@/api/functions";

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
	uploadArtifact: vi.fn(),
	publishVersion: vi.fn(),
}));

vi.mock("@/api/functions", async (importOriginal) => {
	const actual = await importOriginal<typeof import("@/api/functions")>();
	return {
		...actual,
		functionsApi: {
			...actual.functionsApi,
			uploadArtifact: mocks.uploadArtifact,
			publishVersion: mocks.publishVersion,
		},
	};
});

const JAR_BYTES = new Uint8Array([10, 20, 30, 40, 50, 60, 70, 80, 90, 100]);
const MANIFEST_TEXT = JSON.stringify({
	runtime: "jvm",
	entrypoint: "com.example.Hello",
});

async function expectedDigest(): Promise<string> {
	const digestBuf = await crypto.subtle.digest("SHA-256", JAR_BYTES);
	const hex = Array.from(new Uint8Array(digestBuf))
		.map((b) => b.toString(16).padStart(2, "0"))
		.join("");
	return `sha256:${hex}`;
}

function setFile(input: HTMLInputElement, file: File) {
	Object.defineProperty(input, "files", { value: [file], configurable: true });
	input.dispatchEvent(new Event("change"));
}

/**
 * jsdom's File/Blob.arrayBuffer() resolves via a real macrotask (not just a
 * microtask), so a single flushPromises() after a click isn't always enough
 * to observe the upload call that follows the sha256 hashing step.
 */
async function settle() {
	await flushPromises();
	await new Promise((resolve) => setTimeout(resolve, 0));
	await flushPromises();
}

async function mountDrawer() {
	const { default: PublishVersionDrawer } = await import(
		"@/pages/functions/PublishVersionDrawer.vue"
	);
	return mount(PublishVersionDrawer, {
		props: { address: "acme.default.hello" },
		global: {
			plugins: [PrimeVue],
			stubs: { Teleport: true },
		},
	});
}

async function selectFiles(wrapper: Awaited<ReturnType<typeof mountDrawer>>) {
	const jarFile = new File([JAR_BYTES], "hello.jar");
	const manifestFile = new File([MANIFEST_TEXT], "manifest.json", {
		type: "application/json",
	});

	const jarInput = wrapper.get('[data-testid="publish-jar-input"]')
		.element as HTMLInputElement;
	setFile(jarInput, jarFile);
	await flushPromises();

	const manifestInput = wrapper.get('[data-testid="publish-manifest-input"]')
		.element as HTMLInputElement;
	setFile(manifestInput, manifestFile);
	await flushPromises();
}

describe("PublishVersionDrawer", () => {
	beforeEach(() => {
		mocks.uploadArtifact.mockReset();
		mocks.publishVersion.mockReset();
	});

	it("U3: uploads the jar with its sha256 digest BEFORE publishing, and publishes with the ref the upload returned", async () => {
		const uploadResponse: UploadArtifactResponse = {
			artifactRef: "platform://store/distinctive-ref-from-upload",
			digest: await expectedDigest(),
			bytes: JAR_BYTES.length,
		};
		const publishResponse: PublishResponse = {
			id: "ver_1",
			version: 1,
			state: "PUBLISHED",
			digest: uploadResponse.digest,
		};
		mocks.uploadArtifact.mockResolvedValue(uploadResponse);
		mocks.publishVersion.mockResolvedValue(publishResponse);

		const wrapper = await mountDrawer();
		await selectFiles(wrapper);

		await wrapper.get('[data-testid="publish-submit"]').trigger("click");
		await settle();

		expect(mocks.uploadArtifact).toHaveBeenCalledTimes(1);
		expect(mocks.publishVersion).toHaveBeenCalledTimes(1);

		// Order: upload before publish.
		const uploadOrder = mocks.uploadArtifact.mock.invocationCallOrder[0];
		const publishOrder = mocks.publishVersion.mock.invocationCallOrder[0];
		expect(uploadOrder).toBeLessThan(publishOrder as number);

		// Upload got the address, the correct sha256 digest, and the jar bytes.
		const uploadArgs = mocks.uploadArtifact.mock.calls[0];
		expect(uploadArgs[0]).toBe("acme.default.hello");
		expect(uploadArgs[1]).toBe(await expectedDigest());

		// Publish used the artifactRef the upload RETURNED — never a locally
		// built string.
		const publishArgs = mocks.publishVersion.mock.calls[0];
		expect(publishArgs[0]).toBe("acme.default.hello");
		expect(publishArgs[1].artifactRef).toBe(
			"platform://store/distinctive-ref-from-upload",
		);
		expect(publishArgs[1].digest).toBe(uploadResponse.digest);

		expect(wrapper.emitted("published")).toBeTruthy();
	});

	it("U4: a DIGEST_MISMATCH upload failure is surfaced in the drawer and publish is never called", async () => {
		mocks.uploadArtifact.mockRejectedValue(
			new ApiError("digest did not match the uploaded bytes", 422, "DIGEST_MISMATCH"),
		);

		const wrapper = await mountDrawer();
		await selectFiles(wrapper);

		await wrapper.get('[data-testid="publish-submit"]').trigger("click");
		await settle();

		expect(mocks.uploadArtifact).toHaveBeenCalledTimes(1);
		expect(mocks.publishVersion).not.toHaveBeenCalled();

		const errorBox = wrapper.get('[data-testid="publish-error"]');
		expect(errorBox.text()).toContain("DIGEST_MISMATCH");
		expect(errorBox.text()).toContain("digest did not match the uploaded bytes");
		expect(wrapper.emitted("published")).toBeFalsy();
	});

	it("U4: MANIFEST_INVALID with field details renders those details and does not publish", async () => {
		mocks.uploadArtifact.mockResolvedValue({
			artifactRef: "platform://store/ok",
			digest: await expectedDigest(),
			bytes: JAR_BYTES.length,
		} satisfies UploadArtifactResponse);
		mocks.publishVersion.mockRejectedValue(
			new ApiError("manifest failed validation", 400, "MANIFEST_INVALID", {
				errors: [{ location: "manifest.warm", message: "must be a boolean" }],
			}),
		);

		const wrapper = await mountDrawer();
		await selectFiles(wrapper);

		await wrapper.get('[data-testid="publish-submit"]').trigger("click");
		await settle();

		expect(mocks.uploadArtifact).toHaveBeenCalledTimes(1);
		expect(mocks.publishVersion).toHaveBeenCalledTimes(1);

		const errorBox = wrapper.get('[data-testid="publish-error"]');
		expect(errorBox.text()).toContain("MANIFEST_INVALID");
		expect(errorBox.text()).toContain("manifest.warm");
		expect(errorBox.text()).toContain("must be a boolean");
		expect(wrapper.emitted("published")).toBeFalsy();
	});

	// docs/spec/function-manifest-authoring.md M4: "Publish with this
	// manifest" opens this drawer with the manifest editor's current model
	// pre-filled — the jar is still chosen here (canSubmit must not require
	// re-choosing manifest.json as a file when a manifest was already
	// supplied via the prop). Mutant tried: initialise `manifestText` to ""
	// regardless of the prop -> canSubmit stays false with only the jar
	// chosen, and this test's first assertion fails (confirmed); a second
	// mutant (send `JSON.stringify(manifestFile text)` instead of the prop)
	// would send the wrong manifest and fail the publishVersion assertion.
	it("pre-fills the manifest from initialManifest and can publish without a manifest file", async () => {
		const uploadResponse: UploadArtifactResponse = {
			artifactRef: "platform://store/from-editor",
			digest: await expectedDigest(),
			bytes: JAR_BYTES.length,
		};
		mocks.uploadArtifact.mockResolvedValue(uploadResponse);
		mocks.publishVersion.mockResolvedValue({
			id: "ver_2",
			version: 2,
			state: "PUBLISHED",
			digest: uploadResponse.digest,
		} satisfies PublishResponse);

		const { default: PublishVersionDrawer } = await import(
			"@/pages/functions/PublishVersionDrawer.vue"
		);
		const wrapper = mount(PublishVersionDrawer, {
			props: {
				address: "acme.default.hello",
				initialManifest: { runtime: "jvm", entrypoint: "com.example.fn.FromEditor" },
			},
			global: {
				plugins: [PrimeVue],
				stubs: { Teleport: true },
			},
		});
		await flushPromises();

		// Only the jar is chosen — no manifest file input touched.
		const jarFile = new File([JAR_BYTES], "hello.jar");
		const jarInput = wrapper.get('[data-testid="publish-jar-input"]')
			.element as HTMLInputElement;
		setFile(jarInput, jarFile);
		await flushPromises();

		const submit = wrapper.get('[data-testid="publish-submit"]');
		expect(submit.attributes("disabled")).toBeUndefined();

		await submit.trigger("click");
		await settle();

		expect(mocks.publishVersion).toHaveBeenCalledTimes(1);
		const publishArgs = mocks.publishVersion.mock.calls[0];
		expect(publishArgs[1].manifest).toEqual({
			runtime: "jvm",
			entrypoint: "com.example.fn.FromEditor",
		});
	});

	// docs/spec/function-wasm-platform-ui.md §1: once a `runtime: "wasm"`
	// manifest is loaded, the artifact input accepts a .wasm module — its
	// `accept` attribute and label switch off the jar-only ones, and a real
	// .wasm file can be chosen and published. Mutant: leave `accept` hardcoded
	// to ".jar,..." — the assertion on the input's `accept` attribute fails;
	// mutant: leave the label as "Jar file" — the label assertion fails.
	it("accepts a .wasm artifact and relabels the input once a wasm manifest is loaded", async () => {
		const wrapper = await mountDrawer();

		const jarInput = wrapper.get('[data-testid="publish-jar-input"]')
			.element as HTMLInputElement;
		expect(jarInput.accept).toContain(".jar");
		expect(wrapper.text()).toContain("Jar file");

		const wasmManifestText = JSON.stringify({ runtime: "wasm", entrypoint: "handle" });
		const manifestFile = new File([wasmManifestText], "manifest.json", {
			type: "application/json",
		});
		const manifestInput = wrapper.get('[data-testid="publish-manifest-input"]')
			.element as HTMLInputElement;
		setFile(manifestInput, manifestFile);
		await flushPromises();

		expect(jarInput.accept).toContain(".wasm");
		expect(jarInput.accept).not.toContain(".jar");
		expect(wrapper.text()).toContain("Wasm module");
		expect(wrapper.text()).not.toContain("Jar file");

		const uploadResponse: UploadArtifactResponse = {
			artifactRef: "platform://store/wasm-ref",
			digest: await expectedDigest(),
			bytes: JAR_BYTES.length,
		};
		mocks.uploadArtifact.mockResolvedValue(uploadResponse);
		mocks.publishVersion.mockResolvedValue({
			id: "ver_3",
			version: 3,
			state: "PUBLISHED",
			digest: uploadResponse.digest,
		} satisfies PublishResponse);

		const wasmFile = new File([JAR_BYTES], "function.wasm");
		setFile(jarInput, wasmFile);
		await flushPromises();

		await wrapper.get('[data-testid="publish-submit"]').trigger("click");
		await settle();

		expect(mocks.publishVersion).toHaveBeenCalledTimes(1);
		expect(mocks.publishVersion.mock.calls[0][1].manifest).toEqual({
			runtime: "wasm",
			entrypoint: "handle",
		});
	});
});
