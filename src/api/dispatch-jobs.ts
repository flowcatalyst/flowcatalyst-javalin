/**
 * Dispatch jobs API client. No pagination — `msg_dispatch_jobs_read`
 * ingests at high rates, page navigation is meaningless. The endpoint
 * returns the most recent N rows matching the filters; configure with
 * `?size=` (default 50, max 1000).
 */

import { apiFetch } from "./client";
import type {
	DispatchJobFilterOptionsResponse,
	DispatchJobRead as GenDispatchJobRead,
	RequeueResponse,
} from "./generated";

// Response types alias the generated contract (api/openapi.lock.json) so
// `vue-tsc` fails on backend drift. Aliased under the historical names so
// pages keep their imports. The old hand-rolled row carried phantom fields
// (no `maxRetries` on the read row) and an index signature; the old
// filter-options shape ({value,label} arrays under applications/subdomains/
// aggregates) never matched the wire — the facets are plain string arrays
// under statuses/codes/clientIds/dispatchPoolIds/subscriptionIds/kinds.
export type DispatchJobRead = GenDispatchJobRead;
export type DispatchJobFilterOptions = DispatchJobFilterOptionsResponse;

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
	// Reset jobs to PENDING so the scheduler re-dispatches them. Returns the
	// number actually reset (tenant-scoped server-side). Used by the list
	// page's per-row retry + bulk "requeue selected".
	requeue(ids: string[]): Promise<RequeueResponse> {
		return apiFetch(`/dispatch-jobs/requeue`, {
			method: "POST",
			body: JSON.stringify({ ids }),
		});
	},
};
