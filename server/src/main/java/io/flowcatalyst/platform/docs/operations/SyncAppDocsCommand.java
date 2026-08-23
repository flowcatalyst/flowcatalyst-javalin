package io.flowcatalyst.platform.docs.operations;

import java.util.List;
import java.util.Objects;

/// Replace an application's documentation set with `docs` (spec §5). The
/// application is carried as id + code: the id is what the store is keyed
/// by, the code is what the authorization message and the result name.
public record SyncAppDocsCommand(String applicationId, String applicationCode, List<SyncAppDocInput> docs) {
    public SyncAppDocsCommand {
        Objects.requireNonNull(applicationId, "applicationId");
        docs = docs == null ? List.of() : List.copyOf(docs);
    }
}
