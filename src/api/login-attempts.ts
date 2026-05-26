/**
 * API client for Login Attempt operations.
 */

import { apiFetch } from "./client";

export interface LoginAttempt {
	id: string;
	attemptType: string;
	outcome: string;
	failureReason: string | null;
	identifier: string;
	principalId: string | null;
	ipAddress: string | null;
	userAgent: string | null;
	attemptedAt: string;
}

/**
 * Cursor-paginated login attempts. Backend keysets on
 * `(attemptedAt, id) DESC`; `iam_login_attempts` is unbounded so we never
 * count.
 */
export interface LoginAttemptListResponse {
	items: LoginAttempt[];
	hasMore: boolean;
	nextCursor?: string;
}

export interface LoginAttemptFilters {
	attemptType?: string;
	outcome?: string;
	identifier?: string;
	principalId?: string;
	dateFrom?: string;
	dateTo?: string;
	/** Opaque cursor returned by a previous response. */
	after?: string | undefined;
	pageSize?: number;
}

/**
 * Fetch a page of login attempts (cursor-paginated).
 */
export async function fetchLoginAttempts(
	filters: LoginAttemptFilters = {},
): Promise<LoginAttemptListResponse> {
	const params = new URLSearchParams();
	if (filters.attemptType) params.set("attemptType", filters.attemptType);
	if (filters.outcome) params.set("outcome", filters.outcome);
	if (filters.identifier) params.set("identifier", filters.identifier);
	if (filters.principalId) params.set("principalId", filters.principalId);
	if (filters.dateFrom) params.set("dateFrom", filters.dateFrom);
	if (filters.dateTo) params.set("dateTo", filters.dateTo);
	if (filters.after) params.set("after", filters.after);
	if (filters.pageSize !== undefined)
		params.set("pageSize", String(filters.pageSize));

	const query = params.toString();
	return apiFetch<LoginAttemptListResponse>(
		`/login-attempts${query ? `?${query}` : ""}`,
	);
}
