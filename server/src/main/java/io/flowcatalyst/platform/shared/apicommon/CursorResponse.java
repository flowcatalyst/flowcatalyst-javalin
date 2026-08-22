package io.flowcatalyst.platform.shared.apicommon;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/// The cursor-paginated envelope used by the high-volume firehose tables
/// (events, dispatch jobs): `{items, nextCursor?, hasMore}`. `nextCursor` is
/// omitted when empty (Go `omitempty`); `items` is never `null`.
///
/// TODO(pagination-standard): see [OffsetPage].
public record CursorResponse<T>(
        List<T> items,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) String nextCursor,
        boolean hasMore) {

    public CursorResponse {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
