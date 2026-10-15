/**
 * Credential-regenerating actions confirm in a modal with accurate wording:
 * Cancel is default-focused, the accept label names the action, and nothing
 * runs unless accept is invoked.
 */
import { describe, expect, it, vi } from "vitest";
import {
	CREDENTIAL_CONFIRM,
	credentialConfirmOptions,
	type CredentialAction,
} from "@/composables/credentialConfirm";

const actions = Object.keys(CREDENTIAL_CONFIRM) as CredentialAction[];

describe("credentialConfirmOptions", () => {
	it.each(actions)("%s: Cancel is default-focused, accept is labelled, tone is warning", (a) => {
		const accept = vi.fn();
		const o = credentialConfirmOptions(a, accept);
		expect(o.defaultFocus).toBe("reject");
		expect(o.rejectLabel).toBe("Cancel");
		expect(o.acceptLabel).toBe(CREDENTIAL_CONFIRM[a].acceptLabel);
		expect(o.acceptLabel).not.toMatch(/^(OK|Yes)$/);
		expect(o.acceptClass).toMatch(/p-button-(danger|warn)/);
		expect(o.message).toContain("shown once");
		expect(accept).not.toHaveBeenCalled(); // building options never acts
	});

	it("runs the action only through accept", () => {
		const accept = vi.fn();
		credentialConfirmOptions("regenerate-sa-secret", accept).accept?.();
		expect(accept).toHaveBeenCalledTimes(1);
	});

	it("does not claim a token mint revokes anything, or that OAuth rotation is instant", () => {
		expect(CREDENTIAL_CONFIRM["mint-sa-token"].message).toContain("not revoked");
		expect(CREDENTIAL_CONFIRM["rotate-oauth-secret"].message).toContain("24-hour overlap");
		expect(CREDENTIAL_CONFIRM["rotate-dev-secret"].message).toContain("immediately");
	});

	it("allows a per-call message override", () => {
		const o = credentialConfirmOptions("rotate-dev-secret", () => {}, { message: "custom" });
		expect(o.message).toBe("custom");
	});
});
