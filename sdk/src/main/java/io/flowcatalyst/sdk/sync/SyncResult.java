package io.flowcatalyst.sdk.sync;

import java.util.List;

/**
 * Aggregate result of syncing a full {@link Definitions.DefinitionSet}. Each
 * category is either {@link Category.Synced} (mirroring the platform's
 * {@code SyncResultResponse}), the {@link Category.Skipped} sentinel when the
 * category wasn't part of the submitted set, or {@link Category.Failed} when
 * a LOCAL check (a duplicate code within one sync scope) or a caught HTTP
 * failure stopped it — currently only {@link #connections} and {@link
 * #subscriptions} report {@code Failed}; every other category still lets its
 * HTTP exception propagate, unchanged from before. A {@code Failed}
 * anywhere in the result means {@link DefinitionSynchronizer#sync},
 * {@link DefinitionSynchronizer#syncAll} or {@link
 * DefinitionSynchronizer#syncGrouped} threw {@link DefinitionSyncException}
 * rather than returning this record directly — see that exception for how to
 * read a partial outcome.
 */
public record SyncResult(
        String applicationCode,
        Category roles,
        Category eventTypes,
        /*
         * Connections sync BEFORE subscriptions (a subscription's
         * connectionCode must resolve in the same run) — positioned here to
         * match.
         */
        Category connections,
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

        /**
         * A category that could not be synced: either a local validation
         * failure (two definitions in the same scope sharing a code) that
         * meant nothing was sent, or an HTTP failure caught so sibling
         * scopes could still be attempted (a connection sync failing skips
         * just that scope's subscriptions). {@code created}/{@code
         * updated}/{@code deleted}/{@code syncedCodes} reflect any OTHER
         * scope that synced successfully before or alongside the failure —
         * they are not necessarily all zero.
         */
        record Failed(
                int created,
                int updated,
                int deleted,
                List<String> syncedCodes,
                String error)
                implements Category {}

        Category SKIPPED = new Skipped();

        default boolean isSynced() {
            return this instanceof Synced;
        }

        default boolean isFailed() {
            return this instanceof Failed;
        }
    }
}
