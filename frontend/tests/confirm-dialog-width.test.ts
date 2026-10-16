/**
 * The global ConfirmDialog must be a compact modal, not a viewport-wide bar.
 * (No DOM environment in this suite; the rendered box is asserted by the
 * Playwright e2e specs. Here we pin the props and that App.vue applies them.)
 */
import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { CONFIRM_DIALOG_PROPS } from "@/composables/confirmDialog";

describe("global ConfirmDialog width", () => {
	it("has a bounded width and never exceeds the viewport", () => {
		const { width, maxWidth } = CONFIRM_DIALOG_PROPS.style;
		expect(parseFloat(width)).toBeLessThanOrEqual(40);
		expect(width.endsWith("rem")).toBe(true);
		expect(maxWidth).toBe("calc(100vw - 2rem)");
	});

	it("fills viewport minus margin on phones", () => {
		expect(CONFIRM_DIALOG_PROPS.breakpoints["600px"]).toBe("calc(100vw - 2rem)");
	});

	it("App.vue mounts ConfirmDialog with those props", () => {
		const app = readFileSync(new URL("../src/App.vue", import.meta.url), "utf8");
		expect(app).toMatch(/<ConfirmDialog\s+v-bind="CONFIRM_DIALOG_PROPS"\s*\/>/);
	});
});
