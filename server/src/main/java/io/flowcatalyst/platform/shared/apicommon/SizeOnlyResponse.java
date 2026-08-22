package io.flowcatalyst.platform.shared.apicommon;

import java.util.List;

/// `{items}` — used by debug/firehose endpoints that take `?size=` only.
/// `items` is never `null`.
///
/// TODO(pagination-standard): see [OffsetPage].
public record SizeOnlyResponse<T>(List<T> items) {

    public SizeOnlyResponse {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
