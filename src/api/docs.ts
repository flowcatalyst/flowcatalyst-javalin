import { apiFetch } from "./client";
import type {
	DocListResponse as GenDocListResponse,
	DocResponse as GenDocResponse,
	DocSummary as GenDocSummary,
} from "./generated";

// Platform documentation (the repo's docs/*.md, embedded in the server
// binary — always matches the running platform version). Admin-gated.
export type DocSummary = GenDocSummary;
export type DocListResponse = GenDocListResponse;
export type DocResponse = GenDocResponse;

export const docsApi = {
	list(): Promise<DocListResponse> {
		return apiFetch("/docs");
	},

	get(slug: string): Promise<DocResponse> {
		return apiFetch(`/docs/${encodeURIComponent(slug)}`);
	},
};
