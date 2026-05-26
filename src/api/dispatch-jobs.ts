/**
 * Dispatch jobs API client. No pagination — `msg_dispatch_jobs_read`
 * ingests at high rates, page navigation is meaningless. The endpoint
 * returns the most recent N rows matching the filters; configure with
 * `?size=` (default 50, max 1000).
 */

import { apiFetch } from "./client";

export interface DispatchJobRead {
	id: string;
	eventId: string;
	subscriptionId: string;
	clientId?: string | null;
	clientIdentifier?: string | null;
	application?: string | null;
	subdomain?: string | null;
	aggregate?: string | null;
	code?: string | null;
	source?: string | null;
	subject?: string | null;
	status: string;
	dispatchMode?: string | null;
	priority?: number | null;
	correlationId?: string | null;
	scheduledFor?: string | null;
	createdAt: string;
	updatedAt: string;
	completedAt?: string | null;
	lastAttemptAt?: string | null;
	attemptCount?: number | null;
	[key: string]: unknown;
}

export interface DispatchJobsListParams {
	size?: number;
	clientIds?: string[] | undefined;
	statuses?: string[] | undefined;
	applications?: string[] | undefined;
	subdomains?: string[] | undefined;
	aggregates?: string[] | undefined;
	codes?: string[] | undefined;
	source?: string | undefined;
}

export interface DispatchJobFilterOptions {
	applications: { value: string; label: string }[];
	subdomains: { value: string; label: string }[];
	aggregates: { value: string; label: string }[];
	codes: { value: string; label: string }[];
	statuses: { value: string; label: string }[];
}

function buildQuery(params: DispatchJobsListParams): string {
	const qp = new URLSearchParams();
	if (params.size != null) qp.set("size", String(params.size));
	if (params.clientIds?.length) qp.set("clientIds", params.clientIds.join(","));
	if (params.statuses?.length) qp.set("statuses", params.statuses.join(","));
	if (params.applications?.length) qp.set("applications", params.applications.join(","));
	if (params.subdomains?.length) qp.set("subdomains", params.subdomains.join(","));
	if (params.aggregates?.length) qp.set("aggregates", params.aggregates.join(","));
	if (params.codes?.length) qp.set("codes", params.codes.join(","));
	if (params.source) qp.set("source", params.source);
	const s = qp.toString();
	return s ? `?${s}` : "";
}

export const dispatchJobsApi = {
	list(params: DispatchJobsListParams): Promise<DispatchJobRead[]> {
		return apiFetch(`/dispatch-jobs${buildQuery(params)}`);
	},
	filterOptions(): Promise<DispatchJobFilterOptions> {
		return apiFetch(`/dispatch-jobs/filter-options`);
	},
};
