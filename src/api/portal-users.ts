import { apiFetch } from "./client";
import type {
	PortalUserListItem,
	PortalUserListResponse as GenPortalUserListResponse,
	PortalUserResponse,
	StatusChangeResponse,
} from "./generated";

// Portal identity plane admin surface (/api/portal-users). Response types
// alias the generated contract so vue-tsc fails on backend drift.
export type PortalUser = PortalUserListItem;
export type PortalUserListResponse = GenPortalUserListResponse;
export type EnsurePortalUserResponse = PortalUserResponse;

export interface EnsurePortalUserRequest {
	clientId: string;
	email: string;
	name?: string;
	returnInviteLink?: boolean;
	redirectUri?: string;
}

export const portalUsersApi = {
	list(clientId: string): Promise<PortalUserListResponse> {
		return apiFetch(
			`/portal-users?clientId=${encodeURIComponent(clientId)}`,
		);
	},

	ensure(body: EnsurePortalUserRequest): Promise<EnsurePortalUserResponse> {
		return apiFetch("/portal-users", {
			method: "POST",
			body: JSON.stringify(body),
		});
	},

	activate(id: string, clientId: string): Promise<StatusChangeResponse> {
		return apiFetch(`/portal-users/${id}/activate`, {
			method: "POST",
			body: JSON.stringify({ clientId }),
		});
	},

	deactivate(id: string, clientId: string): Promise<StatusChangeResponse> {
		return apiFetch(`/portal-users/${id}/deactivate`, {
			method: "POST",
			body: JSON.stringify({ clientId }),
		});
	},

	remove(id: string, clientId: string): Promise<StatusChangeResponse> {
		return apiFetch(
			`/portal-users/${id}?clientId=${encodeURIComponent(clientId)}`,
			{ method: "DELETE" },
		);
	},
};
