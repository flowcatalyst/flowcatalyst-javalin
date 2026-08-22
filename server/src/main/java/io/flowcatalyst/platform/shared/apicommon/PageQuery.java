package io.flowcatalyst.platform.shared.apicommon;

import io.flowcatalyst.sdk.usecase.UseCaseError;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.javalin.http.Context;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/// The offset-pagination query (Go `apicommon.PageQuery`): `page` (0-based,
/// default 0) and `size` (default 20, capped at [#MAX_PAGE_SIZE]), accepting
/// the historical size aliases `limit` / `pageSize` / `page_size` in that
/// priority order after `size`.
///
/// The record holds the raw values as sent (0 = absent) — `page`, `size`
/// and the three size aliases; the resolution lives in [#pageIndex] /
/// [#pageSize] / [#offset] / [#limit].
///
/// TODO(pagination-standard): see [OffsetPage].
public record PageQuery(int page, int size, int limitAlias, int pageSizeAlias, int pageSizeSnakeAlias) {

    /// Caps the resolved page size (the 1000-row firehose clamp).
    public static final int MAX_PAGE_SIZE = 1000;

    /// The default when no size alias is supplied.
    public static final int DEFAULT_PAGE_SIZE = 20;

    public static final PageQuery DEFAULT = new PageQuery(0, 0, 0, 0, 0);

    /// `page` + `size` only — the common programmatic form.
    public PageQuery(int page, int size) {
        this(page, size, 0, 0, 0);
    }

    /// Parses the query string. A non-integer value is a 400 `VALIDATION`
    /// envelope with `details.errors = [{message, location: "query.<name>",
    /// value}]`, the same shape huma produces for a bad query parameter.
    public static PageQuery from(Context ctx) {
        var errors = new ArrayList<Map<String, Object>>();
        var page = intParam(ctx, "page", errors);
        var size = intParam(ctx, "size", errors);
        var limit = intParam(ctx, "limit", errors);
        var camel = intParam(ctx, "pageSize", errors);
        var snake = intParam(ctx, "page_size", errors);
        if (!errors.isEmpty()) {
            throw new UseCaseException(UseCaseError.validation("VALIDATION", "validation failed")
                    .withDetails(Map.of("errors", List.copyOf(errors))));
        }
        return new PageQuery(page, size, limit, camel, snake);
    }

    private static int intParam(Context ctx, String name, List<Map<String, Object>> errors) {
        var raw = ctx.queryParam(name);
        if (raw == null || raw.isEmpty()) return 0;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException _) {
            var detail = new LinkedHashMap<String, Object>();
            detail.put("message", "invalid integer");
            detail.put("location", "query." + name);
            detail.put("value", raw);
            errors.add(detail);
            return 0;
        }
    }

    /// Resolved 0-based page index (negative → 0).
    public int pageIndex() {
        return Math.max(page, 0);
    }

    /// Resolved page size: first positive of `size, limit, pageSize,
    /// page_size`, capped at [#MAX_PAGE_SIZE]; default 20.
    public int pageSize() {
        return IntStream.of(size, limitAlias, pageSizeAlias, pageSizeSnakeAlias)
                .filter(v -> v > 0)
                .map(v -> Math.min(v, MAX_PAGE_SIZE))
                .findFirst()
                .orElse(DEFAULT_PAGE_SIZE);
    }

    /// SQL offset for the resolved page.
    public long offset() {
        return (long) pageIndex() * pageSize();
    }

    /// SQL limit for the resolved page.
    public long limit() {
        return pageSize();
    }
}
