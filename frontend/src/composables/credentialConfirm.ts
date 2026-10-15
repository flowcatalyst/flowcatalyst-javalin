/**
 * Confirmation options for credential-regenerating / minting actions.
 *
 * Every wording here is tied to verified server behaviour (Go handlers):
 *  - service-account regenerate-token / regenerate-secret REPLACE the stored
 *    webhook bearer token / HMAC signing secret; the platform signs outbound
 *    deliveries with the new value (resolver cache TTL ~1 min) so receivers
 *    still holding the old one reject them. No overlap window.
 *  - POST /service-accounts/{id}/token mints a 1h bearer; nothing is persisted
 *    and earlier tokens are NOT revoked.
 *  - POST /oauth-clients/{id}/rotate-secret with no body keeps the OLD secret
 *    valid for a 24h overlap (DefaultSecretGrace); the new one works at once.
 *  - developer-credential set REPLACES the secret ref; the old secret stops
 *    working immediately.
 *
 * `defaultFocus: "reject"` puts initial focus on Cancel; Escape and the close
 * button route through PrimeVue's reject path, so nothing runs on cancel.
 */
import type { ConfirmationOptions } from "primevue/confirmationoptions";

export type CredentialAction =
	| "regenerate-sa-token"
	| "regenerate-sa-secret"
	| "mint-sa-token"
	| "rotate-oauth-secret"
	| "rotate-dev-secret";

interface Spec {
	header: string;
	message: string;
	acceptLabel: string;
	severity: "danger" | "warn";
}

const SHOWN_ONCE = "The new value is shown once, so copy it before closing.";

export const CREDENTIAL_CONFIRM: Record<CredentialAction, Spec> = {
	"regenerate-sa-token": {
		header: "Regenerate auth token?",
		message:
			"The current auth token is replaced and stops being used immediately. Webhook " +
			"deliveries from this service account will carry the new token, so any receiving " +
			"endpoint still configured with the old one will reject them until you update it. " +
			SHOWN_ONCE,
		acceptLabel: "Regenerate token",
		severity: "danger",
	},
	"regenerate-sa-secret": {
		header: "Regenerate signing secret?",
		message:
			"The current signing secret is replaced and stops being used immediately. Webhook " +
			"deliveries from this service account will be signed with the new secret, so any " +
			"receiving endpoint still verifying with the old one will reject them until you " +
			"update it. " +
			SHOWN_ONCE,
		acceptLabel: "Regenerate secret",
		severity: "danger",
	},
	"mint-sa-token": {
		header: "Generate a bearer token?",
		message:
			"This mints a live bearer token carrying all of this service account's permissions. " +
			"Anyone holding it can call the API as this account until it expires in 1 hour. " +
			"Existing tokens are not revoked, and the mint is recorded in the audit log. " +
			SHOWN_ONCE,
		acceptLabel: "Generate token",
		severity: "warn",
	},
	"rotate-oauth-secret": {
		header: "Rotate client secret?",
		message:
			"A new client secret is issued and shown once. The current secret keeps working for " +
			"a 24-hour overlap so applications can be updated, then stops working. Applications " +
			"using the old secret will fail to authenticate after that until they are updated. " +
			SHOWN_ONCE,
		acceptLabel: "Rotate secret",
		severity: "warn",
	},
	"rotate-dev-secret": {
		header: "Rotate developer secret?",
		message:
			"The current developer client secret stops working immediately. Any scripts or tools " +
			"using it will fail to authenticate until you give them the new one. " +
			SHOWN_ONCE,
		acceptLabel: "Rotate secret",
		severity: "danger",
	},
};

/** Build `confirm.require()` options for a credential action. */
export function credentialConfirmOptions(
	action: CredentialAction,
	accept: () => void,
	overrides: { message?: string } = {},
): ConfirmationOptions {
	const spec = CREDENTIAL_CONFIRM[action];
	return {
		header: spec.header,
		message: overrides.message ?? spec.message,
		icon: "pi pi-exclamation-triangle",
		acceptLabel: spec.acceptLabel,
		rejectLabel: "Cancel",
		acceptClass: spec.severity === "danger" ? "p-button-danger" : "p-button-warn",
		rejectClass: "p-button-text p-button-secondary",
		defaultFocus: "reject",
		accept,
	};
}
