import { apiFetch } from "./client";
import type {
	IdentityProviderListResponse as GenIdentityProviderListResponse,
	IdentityProviderResponse,
} from "./generated";

// Request-side string union the forms rely on. The generated response type
// deliberately stays `string` (the spec doesn't carry enums — see
// docs/frontend-api-types-adoption.md on SDK coordination).
export type IdentityProviderType = "INTERNAL" | "OIDC";

// Response types alias the generated contract (api/openapi.lock.json) so
// `vue-tsc` fails on backend drift. Aliased under the historical names so
// pages keep their imports.
export type IdentityProvider = IdentityProviderResponse;
export type IdentityProviderListResponse = GenIdentityProviderListResponse;

export interface CreateIdentityProviderRequest {
	code: string;
	name: string;
	type: IdentityProviderType;
	oidcIssuerUrl?: string;
	oidcClientId?: string;
	oidcClientSecretRef?: string;
	oidcMultiTenant?: boolean;
	oidcIssuerPattern?: string;
	allowedEmailDomains?: string[];
}

export interface UpdateIdentityProviderRequest {
	name?: string;
	oidcIssuerUrl?: string;
	oidcClientId?: string;
	oidcClientSecretRef?: string;
	oidcMultiTenant?: boolean;
	oidcIssuerPattern?: string;
	allowedEmailDomains?: string[];
}

export const identityProvidersApi = {
	list(): Promise<IdentityProviderListResponse> {
		return apiFetch("/identity-providers");
	},

	get(id: string): Promise<IdentityProvider> {
		return apiFetch(`/identity-providers/${id}`);
	},

	// NOTE: there is no GET /identity-providers/by-code/{code} on the wire —
	// the previous getByCode() here called a route the backend never exposed
	// (404). Removed when adopting the generated types; use list() + filter
	// or get(id) instead.

	// Unlike most create endpoints (which return `{ id }`), the backend
	// deliberately returns the full provider on 201 so the SPA can render it
	// without a re-fetch (see CreateIdentityProviderResponses in the spec).
	create(data: CreateIdentityProviderRequest): Promise<IdentityProvider> {
		return apiFetch("/identity-providers", {
			method: "POST",
			body: JSON.stringify(data),
		});
	},

	// PUT returns the full updated provider (200), same SPA-friendly choice
	// as create.
	update(
		id: string,
		data: UpdateIdentityProviderRequest,
	): Promise<IdentityProvider> {
		return apiFetch(`/identity-providers/${id}`, {
			method: "PUT",
			body: JSON.stringify(data),
		});
	},

	delete(id: string): Promise<void> {
		return apiFetch(`/identity-providers/${id}`, {
			method: "DELETE",
		});
	},
};
