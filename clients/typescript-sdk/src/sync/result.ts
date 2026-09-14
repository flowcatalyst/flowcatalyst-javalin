/**
 * Sync result types returned by `DefinitionSynchronizer`.
 *
 * Each per-category result mirrors the backend's `SyncResultResponse` shape
 * (what the platform wrote), plus a convenience `skipped` flag set when the
 * category wasn't present in the input `DefinitionSet`.
 */

/** Per-category counts. */
export interface CategorySyncResult {
	applicationCode: string;
	created: number;
	updated: number;
	deleted: number;
	syncedCodes: string[];
}

/** Sentinel returned when a category wasn't part of the submitted set. */
export interface SkippedSyncResult {
	skipped: true;
}

export type MaybeCategoryResult = CategorySyncResult | SkippedSyncResult;

/** Aggregate result of syncing a full `DefinitionSet`. */
export interface SyncResult {
	applicationCode: string;
	roles: MaybeCategoryResult;
	eventTypes: MaybeCategoryResult;
	subscriptions: MaybeCategoryResult;
	dispatchPools: MaybeCategoryResult;
	principals: MaybeCategoryResult;
	processes: MaybeCategoryResult;
	scheduledJobs: MaybeCategoryResult;
	/**
	 * OpenAPI sync is a single-document upload. On success
	 * `syncedCodes` carries `[version]`; `created`/`updated` reflect
	 * whether the document was newly published or replaced an existing
	 * one (both zero if byte-identical re-sync).
	 */
	openapi: MaybeCategoryResult;
}

/** Narrow a category result to the non-skipped case. */
export function isSynced(r: MaybeCategoryResult): r is CategorySyncResult {
	return !("skipped" in r && r.skipped);
}

/** A "skipped" sentinel for categories not in the set. */
export const SKIPPED: SkippedSyncResult = { skipped: true };
