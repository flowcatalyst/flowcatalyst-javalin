import { apiFetch } from "./client";
import type {
	CreatePortalAppResponse as GenCreatePortalAppResponse,
	PortalAppListResponse as GenPortalAppListResponse,
	PortalAppResponse,
	StatusChangeResponse,
} from "./generated";

// Portal apps (/api/portal-apps): the named portals a client runs. OAuth
// clients link to one (portalAppId) and portal users are granted per app.
export type PortalApp = PortalAppResponse;
export type PortalAppListResponse = GenPortalAppListResponse;
// The new app plus its auto-provisioned portal OAuth client; clientSecret
// (CONFIDENTIAL only) is shown exactly once.
export type CreatePortalAppResponse = GenCreatePortalAppResponse;
export type PortalClientType = "CONFIDENTIAL" | "PUBLIC";

export interface CreatePortalAppRequest {
	clientId: string;
	code: string;
	name: string;
	description?: string;
	// Callback URL(s) registered on the provisioned OAuth client.
	redirectUris?: string[];
	clientType?: PortalClientType;
}

export interface UpdatePortalAppRequest {
	clientId: string;
	name?: string;
	description?: string;
	active?: boolean;
}

export const portalAppsApi = {
	// Omit clientId (anchors only) to list every client's apps.
	list(clientId?: string): Promise<PortalAppListResponse> {
		return apiFetch(
			clientId
				? `/portal-apps?clientId=${encodeURIComponent(clientId)}`
				: "/portal-apps",
		);
	},

	create(body: CreatePortalAppRequest): Promise<CreatePortalAppResponse> {
		return apiFetch("/portal-apps", {
			method: "POST",
			body: JSON.stringify(body),
		});
	},

	update(id: string, body: UpdatePortalAppRequest): Promise<PortalApp> {
		return apiFetch(`/portal-apps/${id}`, {
			method: "PUT",
			body: JSON.stringify(body),
		});
	},

	remove(id: string, clientId: string): Promise<StatusChangeResponse> {
		return apiFetch(
			`/portal-apps/${id}?clientId=${encodeURIComponent(clientId)}`,
			{ method: "DELETE" },
		);
	},
};
