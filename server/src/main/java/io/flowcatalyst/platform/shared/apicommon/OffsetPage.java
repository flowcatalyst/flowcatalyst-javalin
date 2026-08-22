package io.flowcatalyst.platform.shared.apicommon;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/// The offset-paginated envelope: `{data, page, size, total, total_pages}`
/// with a 0-based page index. `data` is never `null` (serialises as `[]`).
///
/// TODO(pagination-standard): the three list envelopes in this package
/// ([OffsetPage], [CursorResponse], [SizeOnlyResponse]) will collapse into ONE
/// standard shape; they are kept faithful to Go for now and live together
/// here so that change is local.
public record OffsetPage<T>(
        List<T> data,
        int page,
        int size,
        long total,
        @JsonProperty("total_pages") int totalPages) {

    public OffsetPage {
        data = data == null ? List.of() : List.copyOf(data);
    }

    /// Builds a page, computing `total_pages` (0 when `size <= 0`) — Go
    /// `apicommon.NewOffsetPage`.
    public static <T> OffsetPage<T> of(List<T> data, int page, int size, long total) {
        var totalPages = size > 0 ? (int) ((total + size - 1) / size) : 0;
        return new OffsetPage<>(data, page, size, total, totalPages);
    }

    /// Builds a page straight from the resolved query.
    public static <T> OffsetPage<T> of(List<T> data, PageQuery query, long total) {
        return of(data, query.pageIndex(), query.pageSize(), total);
    }
}
