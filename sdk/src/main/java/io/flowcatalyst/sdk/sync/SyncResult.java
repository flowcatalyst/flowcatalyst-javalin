package io.flowcatalyst.sdk.sync;

import java.util.List;

/**
 * Aggregate result of syncing a full {@link Definitions.DefinitionSet}. Each
 * category is either {@link Category.Synced} (mirroring the platform's
 * {@code SyncResultResponse}) or the {@link Category.Skipped} sentinel when
 * the category wasn't part of the submitted set.
 */
public record SyncResult(
        String applicationCode,
        Category roles,
        Category eventTypes,
        Category subscriptions,
        Category dispatchPools,
        Category principals,
        Category processes,
        Category scheduledJobs,
        /*
         * OpenAPI sync is a single-document upload: on success syncedCodes
         * carries [version]; created/updated reflect newly-published vs
         * replaced (both zero on a byte-identical re-sync).
         */
        Category openapi) {

    /** Per-category outcome. */
    public sealed interface Category {

        record Synced(
                String applicationCode,
                int created,
                int updated,
                int deleted,
                List<String> syncedCodes)
                implements Category {}

        record Skipped() implements Category {}

        Category SKIPPED = new Skipped();

        default boolean isSynced() {
            return this instanceof Synced;
        }
    }
}
