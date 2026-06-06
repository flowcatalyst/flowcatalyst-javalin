import { apiFetch } from "./client";

export interface ResetApprovalRequest {
	id: string;
	principalId: string;
	email: string;
	name: string;
	clientId?: string;
	expiresAt: string;
	createdAt: string;
}

// Lost-device password-reset approval queue (Phase 8). Client-administrators
// review and approve/deny resets for users in their client(s).
export const resetApprovalsApi = {
	list(): Promise<{ requests: ResetApprovalRequest[] }> {
		return apiFetch(`/reset-approvals`);
	},
	approve(id: string): Promise<{ message: string }> {
		return apiFetch(`/reset-approvals/${id}/approve`, { method: "POST" });
	},
	deny(id: string): Promise<{ message: string }> {
		return apiFetch(`/reset-approvals/${id}/deny`, { method: "POST" });
	},
};
