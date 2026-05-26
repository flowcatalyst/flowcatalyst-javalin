import { bffFetch } from "./client";
import type {
	Permission,
	PermissionListResponse,
} from "@/types/bff";

// Re-export types for consumers
export type { Permission, PermissionListResponse };

export const permissionsApi = {
	list(): Promise<PermissionListResponse> {
		return bffFetch("/roles/permissions");
	},

	get(permission: string): Promise<Permission> {
		return bffFetch(`/roles/permissions/${encodeURIComponent(permission)}`);
	},
};
