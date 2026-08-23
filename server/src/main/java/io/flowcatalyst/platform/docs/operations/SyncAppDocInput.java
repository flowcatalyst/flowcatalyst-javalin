package io.flowcatalyst.platform.docs.operations;

import java.util.Objects;

/// One page of a docs sync payload, as received on the wire (spec §5):
/// `title` is optional (`null` = derive from the content, then the slug).
public record SyncAppDocInput(String slug, String title, String content) {
    public SyncAppDocInput {
        Objects.requireNonNull(slug, "slug");
        Objects.requireNonNull(content, "content");
    }
}
