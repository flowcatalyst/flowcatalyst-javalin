import { bffFetch } from "./client";
import type {
	Permission,
	PermissionListResponse,
} from "@/types/bff";

// Re-export types for consumers
export type { Permission, PermissionListResponse };

export const permissionsApi = {
	// list returns the permission catalogue. Pass an application code to scope
	// it to that application's permissions (e.g. when editing one of its
	// roles); omit it for the full catalogue across every application.
	list(application?: string): Promise<PermissionListResponse> {
		const query = application
			? `?application=${encodeURIComponent(application)}`
			: "";
		return bffFetch(`/roles/permissions${query}`);
	},

	get(permission: string): Promise<Permission> {
		return bffFetch(`/roles/permissions/${encodeURIComponent(permission)}`);
	},
};
