package io.flowcatalyst.platform.sdksync.api;

import io.flowcatalyst.platform.docs.operations.SyncAppDocInput;
import io.flowcatalyst.platform.docs.operations.SyncAppDocsCommand;

import java.util.List;

/// `SyncDocsRequest` (lockfile): `{docs[]: {slug, title, content}}`.
///
/// No `removeUnlisted`: the payload **is** the set, replaced declaratively
/// (spec §3), so there is no prune flag to honour or ignore.
public record SyncDocsRequest(List<Input> docs) {

    public record Input(String slug, String title, String content) {
        SyncAppDocInput toInput() {
            return new SyncAppDocInput(slug, title, content);
        }
    }

    SyncAppDocsCommand toCommand(String applicationId, String applicationCode) {
        return new SyncAppDocsCommand(applicationId, applicationCode,
                docs == null ? List.of() : docs.stream().map(Input::toInput).toList());
    }
}
