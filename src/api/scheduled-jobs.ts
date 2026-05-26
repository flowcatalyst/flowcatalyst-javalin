/**
 * Scheduled Jobs API client.
 *
 * Reads use the BFF (cookie auth, response shapes tuned for the UI).
 * Writes go through the platform API (`/api/scheduled-jobs/*`) since the
 * BFF is read-only for this resource.
 */

import { apiFetch, bffFetch } from "./client";

export type ScheduledJobStatus = "ACTIVE" | "PAUSED" | "ARCHIVED";
export type TriggerKind = "CRON" | "MANUAL";
export type InstanceStatus =
	| "QUEUED"
	| "IN_FLIGHT"
	| "DELIVERED"
	| "COMPLETED"
	| "FAILED"
	| "DELIVERY_FAILED";
export type CompletionStatus = "SUCCESS" | "FAILURE";
export type LogLevel = "DEBUG" | "INFO" | "WARN" | "ERROR";

export interface ScheduledJob {
	id: string;
	clientId?: string | null;
	clientName?: string | null;
	code: string;
	name: string;
	description?: string;
	status: ScheduledJobStatus;
	crons: string[];
	timezone: string;
	payload?: unknown;
	concurrent: boolean;
	tracksCompletion: boolean;
	timeoutSeconds?: number;
	deliveryMaxAttempts: number;
	targetUrl?: string;
	lastFiredAt?: string;
	createdAt: string;
	updatedAt: string;
	version: number;
	hasActiveInstance: boolean;
}

export interface ScheduledJobInstance {
	id: string;
	scheduledJobId: string;
	jobCode: string;
	clientId?: string | null;
	triggerKind: TriggerKind;
	scheduledFor?: string;
	firedAt: string;
	deliveredAt?: string;
	completedAt?: string;
	status: InstanceStatus;
	deliveryAttempts: number;
	deliveryError?: string;
	completionStatus?: CompletionStatus;
	completionResult?: unknown;
	correlationId?: string;
	createdAt: string;
}

export interface ScheduledJobInstanceLog {
	id: string;
	instanceId: string;
	level: LogLevel;
	message: string;
	metadata?: unknown;
	createdAt: string;
}

export interface PaginatedJobs {
	data: ScheduledJob[];
	page: number;
	size: number;
	total: number;
	totalPages: number;
}

export interface PaginatedInstances {
	data: ScheduledJobInstance[];
	page: number;
	size: number;
	total: number;
	totalPages: number;
}

export interface FilterOption {
	value: string;
	label: string;
}

export interface ScheduledJobsFilterOptions {
	clients: FilterOption[];
	statuses: FilterOption[];
}

export interface ListJobsParams {
	clientId?: string;
	status?: ScheduledJobStatus | string;
	search?: string;
	page?: number;
	size?: number;
}

export interface ListInstancesParams {
	status?: InstanceStatus | string;
	triggerKind?: TriggerKind | string;
	from?: string;
	to?: string;
	page?: number;
	size?: number;
}

export interface CreateScheduledJobBody {
	code: string;
	name: string;
	description?: string;
	clientId?: string | null;
	crons: string[];
	timezone?: string;
	payload?: unknown;
	concurrent?: boolean;
	tracksCompletion?: boolean;
	timeoutSeconds?: number;
	deliveryMaxAttempts?: number;
	targetUrl?: string;
}

export interface UpdateScheduledJobBody {
	name?: string;
	description?: string;
	crons?: string[];
	timezone?: string;
	payload?: unknown;
	concurrent?: boolean;
	tracksCompletion?: boolean;
	timeoutSeconds?: number;
	deliveryMaxAttempts?: number;
	targetUrl?: string;
}

function qs(params: Record<string, unknown>): string {
	const sp = new URLSearchParams();
	for (const [k, v] of Object.entries(params)) {
		if (v === undefined || v === null || v === "") continue;
		sp.append(k, String(v));
	}
	const s = sp.toString();
	return s ? `?${s}` : "";
}

export const scheduledJobsApi = {
	// ── Reads (BFF) ─────────────────────────────────────────────────────────

	list(params: ListJobsParams = {}): Promise<PaginatedJobs> {
		return bffFetch(`/scheduled-jobs${qs(params as Record<string, unknown>)}`);
	},
	get(id: string): Promise<ScheduledJob> {
		return bffFetch(`/scheduled-jobs/${encodeURIComponent(id)}`);
	},
	listInstances(
		jobId: string,
		params: ListInstancesParams = {},
	): Promise<PaginatedInstances> {
		return bffFetch(
			`/scheduled-jobs/${encodeURIComponent(jobId)}/instances${qs(params as Record<string, unknown>)}`,
		);
	},
	getInstance(instanceId: string): Promise<ScheduledJobInstance> {
		return bffFetch(`/scheduled-jobs/instances/${encodeURIComponent(instanceId)}`);
	},
	listInstanceLogs(instanceId: string): Promise<ScheduledJobInstanceLog[]> {
		return bffFetch(
			`/scheduled-jobs/instances/${encodeURIComponent(instanceId)}/logs`,
		);
	},
	filterOptions(): Promise<ScheduledJobsFilterOptions> {
		return bffFetch(`/scheduled-jobs/filter-options`);
	},

	// ── Writes (Platform API) ───────────────────────────────────────────────

	create(body: CreateScheduledJobBody): Promise<{ id: string }> {
		return apiFetch(`/scheduled-jobs`, {
			method: "POST",
			body: JSON.stringify(body),
		});
	},
	update(id: string, body: UpdateScheduledJobBody): Promise<void> {
		return apiFetch(`/scheduled-jobs/${encodeURIComponent(id)}`, {
			method: "PUT",
			body: JSON.stringify(body),
		});
	},
	pause(id: string): Promise<void> {
		return apiFetch(`/scheduled-jobs/${encodeURIComponent(id)}/pause`, {
			method: "POST",
		});
	},
	resume(id: string): Promise<void> {
		return apiFetch(`/scheduled-jobs/${encodeURIComponent(id)}/resume`, {
			method: "POST",
		});
	},
	archive(id: string): Promise<void> {
		return apiFetch(`/scheduled-jobs/${encodeURIComponent(id)}/archive`, {
			method: "POST",
		});
	},
	delete(id: string): Promise<void> {
		return apiFetch(`/scheduled-jobs/${encodeURIComponent(id)}`, {
			method: "DELETE",
		});
	},
	fire(id: string, correlationId?: string): Promise<{ id: string }> {
		return apiFetch(`/scheduled-jobs/${encodeURIComponent(id)}/fire`, {
			method: "POST",
			body: JSON.stringify({ correlationId }),
		});
	},
};
