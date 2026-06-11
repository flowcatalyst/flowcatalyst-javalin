import { apiFetch } from "./client";
import type { PrincipalScope } from "./users";
import type {
	CreateServiceAccountResponse as GenCreateServiceAccountResponse,
	RegenerateAuthTokenResponse,
	RegenerateSigningSecretResponse,
	RoleAssignmentDto,
	ServiceAccountListResponse as GenServiceAccountListResponse,
	ServiceAccountOAuthSecrets,
	ServiceAccountResponse,
	ServiceAccountRoleListResponse,
	ServiceAccountRolesAssignedResponse,
	ServiceAccountWebhookSecrets,
} from "./generated";

// Request-side string union the forms rely on. The generated response
// types deliberately stay `string` (the spec doesn't carry enums — see
// docs/frontend-api-types-adoption.md on SDK coordination).
export type WebhookAuthType = "BEARER" | "BASIC";

// Response types alias the generated contract (api/openapi.lock.json) so
// `vue-tsc` fails on backend drift. Aliased under the historical names so
// pages keep their imports. Optional fields come back `undefined` (not
// `null`); normalise at use sites.
export type ServiceAccount = ServiceAccountResponse;
export type ServiceAccountListResponse = GenServiceAccountListResponse;
export type OAuthCredentials = ServiceAccountOAuthSecrets;
export type WebhookCredentials = ServiceAccountWebhookSecrets;
export type CreateServiceAccountResponse = GenCreateServiceAccountResponse;
export type RegenerateTokenResponse = RegenerateAuthTokenResponse;
export type RegenerateSecretResponse = RegenerateSigningSecretResponse;
export type RoleAssignment = RoleAssignmentDto;
export type RolesResponse = ServiceAccountRoleListResponse;
export type RolesAssignedResponse = ServiceAccountRolesAssignedResponse;

export interface CreateServiceAccountRequest {
	code: string;
	name: string;
	description?: string;
	clientIds?: string[];
	applicationId?: string;
	scope?: PrincipalScope;
}

export interface UpdateServiceAccountRequest {
	name?: string;
	description?: string;
	clientIds?: string[];
	scope?: PrincipalScope;
}

export interface ServiceAccountFilters {
	clientId?: string;
	applicationId?: string;
	active?: boolean;
}

export const serviceAccountsApi = {
	/**
	 * List all service accounts with optional filters.
	 */
	list(filters?: ServiceAccountFilters): Promise<ServiceAccountListResponse> {
		const params = new URLSearchParams();
		if (filters?.clientId) params.append("clientId", filters.clientId);
		if (filters?.applicationId)
			params.append("applicationId", filters.applicationId);
		if (filters?.active !== undefined)
			params.append("active", String(filters.active));

		const query = params.toString();
		return apiFetch(`/service-accounts${query ? `?${query}` : ""}`);
	},

	/**
	 * Get a service account by ID.
	 */
	get(id: string): Promise<ServiceAccount> {
		return apiFetch(`/service-accounts/${id}`);
	},

	/**
	 * Get a service account by code.
	 */
	getByCode(code: string): Promise<ServiceAccount> {
		return apiFetch(`/service-accounts/code/${code}`);
	},

	/**
	 * Create a new service account.
	 * Returns the created service account along with credentials (shown only once).
	 */
	create(
		data: CreateServiceAccountRequest,
	): Promise<CreateServiceAccountResponse> {
		return apiFetch("/service-accounts", {
			method: "POST",
			body: JSON.stringify(data),
		});
	},

	/**
	 * Update a service account's metadata. Returns 204 (no body) — reload or
	 * patch local state from the request after a successful save.
	 */
	update(id: string, data: UpdateServiceAccountRequest): Promise<void> {
		return apiFetch(`/service-accounts/${id}`, {
			method: "PUT",
			body: JSON.stringify(data),
		});
	},

	/**
	 * Delete a service account.
	 */
	delete(id: string): Promise<void> {
		return apiFetch(`/service-accounts/${id}`, {
			method: "DELETE",
		});
	},

	// ==================== Credential Management ====================

	/**
	 * Regenerate the auth token (returns new token, shown only once).
	 */
	regenerateToken(id: string): Promise<RegenerateTokenResponse> {
		return apiFetch(`/service-accounts/${id}/regenerate-token`, {
			method: "POST",
		});
	},

	/**
	 * Regenerate the signing secret (returns new secret, shown only once).
	 */
	regenerateSecret(id: string): Promise<RegenerateSecretResponse> {
		return apiFetch(`/service-accounts/${id}/regenerate-secret`, {
			method: "POST",
		});
	},

	// ==================== Role Management ====================

	/**
	 * Get assigned roles for a service account.
	 */
	getRoles(id: string): Promise<RolesResponse> {
		return apiFetch(`/service-accounts/${id}/roles`);
	},

	/**
	 * Assign roles to a service account (declarative - replaces all existing roles).
	 */
	assignRoles(id: string, roles: string[]): Promise<RolesAssignedResponse> {
		return apiFetch(`/service-accounts/${id}/roles`, {
			method: "PUT",
			body: JSON.stringify({ roles }),
		});
	},
};
