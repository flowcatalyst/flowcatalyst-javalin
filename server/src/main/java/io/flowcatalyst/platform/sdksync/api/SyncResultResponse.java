package io.flowcatalyst.platform.sdksync.api;

import io.flowcatalyst.platform.docs.AppDocRepository.ReplaceResult;
import io.flowcatalyst.platform.dispatchpool.operations.DispatchPoolEvents.DispatchPoolsSynced;
import io.flowcatalyst.platform.eventtype.operations.EventTypeEvents.EventTypesSynced;
import io.flowcatalyst.platform.principal.operations.PrincipalEvents.PrincipalsSynced;
import io.flowcatalyst.platform.process.operations.ProcessEvents.ProcessesSynced;
import io.flowcatalyst.platform.role.operations.RoleEvents.RolesSynced;
import io.flowcatalyst.platform.subscription.operations.SubscriptionEvents.SubscriptionsSynced;

import java.util.List;

/// The shared result of the list-based sync routes (spec §2):
/// `{applicationCode, created, updated, deleted, syncedCodes[]}`. One
/// `from` per rollup so the column mapping (roles' `removed`, principals'
/// `deactivated`/`syncedEmails`) is written once.
public record SyncResultResponse(String applicationCode, int created, int updated, int deleted, List<String> syncedCodes) {

    public SyncResultResponse {
        syncedCodes = syncedCodes == null ? List.of() : List.copyOf(syncedCodes);
    }

    static SyncResultResponse from(EventTypesSynced e) {
        return new SyncResultResponse(e.applicationCode(), e.created(), e.updated(), e.deleted(), e.syncedCodes());
    }

    static SyncResultResponse from(RolesSynced e) {
        return new SyncResultResponse(e.applicationCode(), e.created(), e.updated(), e.removed(), e.syncedCodes());
    }

    static SyncResultResponse from(SubscriptionsSynced e) {
        return new SyncResultResponse(e.applicationCode(), e.created(), e.updated(), e.deleted(), e.syncedCodes());
    }

    static SyncResultResponse from(DispatchPoolsSynced e) {
        return new SyncResultResponse(e.applicationCode(), e.created(), e.updated(), e.deleted(), e.syncedCodes());
    }

    static SyncResultResponse from(ProcessesSynced e) {
        return new SyncResultResponse(e.applicationCode(), e.created(), e.updated(), e.deleted(), e.syncedCodes());
    }

    static SyncResultResponse from(PrincipalsSynced e) {
        return new SyncResultResponse(e.applicationCode(), e.created(), e.updated(), e.deactivated(), e.syncedEmails());
    }

    /// Docs emit no event: the store's replace result, named by the resolved application.
    static SyncResultResponse from(String applicationCode, ReplaceResult r) {
        return new SyncResultResponse(applicationCode, r.created(), r.updated(), r.deleted(), r.slugs());
    }
}
