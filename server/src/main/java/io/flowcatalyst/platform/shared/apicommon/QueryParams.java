package io.flowcatalyst.platform.shared.apicommon;

import io.flowcatalyst.sdk.usecase.UseCaseError;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.http.Exchange;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

/// Typed query-parameter reads and their one error shape: a value that does
/// not parse is a 400 `VALIDATION` `validation failed` envelope with
/// `details.errors = [{message, location: "query.<name>", value}]` — the
/// shape huma produces for a bad query parameter, so every Java list reports
/// a bad `page`, `size`, `pageSize`… identically. An absent or empty
/// parameter is *absent* (empty `Optional`); the default it resolves to is
/// the caller's.
///
/// Two forms: [#intParam(Context, String)] throws on the first bad value;
/// [#intParam(Context, String, List)] records the error and reads as absent,
/// for a handler that reports every bad parameter at once and then throws
/// [#validation] (`PageQuery.from`).
public final class QueryParams {

    private QueryParams() {
    }

    /// `name` as an integer (trimmed): absent or empty → empty.
    ///
    /// @throws UseCaseException 400 `VALIDATION` with one `invalid integer`
    ///                          error at `query.<name>` for a non-integer value
    public static OptionalInt intParam(Exchange ctx, String name) {
        var errors = new ArrayList<Map<String, Object>>(1);
        OptionalInt value = intParam(ctx, name, errors);
        if (!errors.isEmpty()) throw validation(errors);
        return value;
    }

    /// The accumulating form: a non-integer value appends its
    /// `{message: "invalid integer", location: "query.<name>", value}` entry
    /// to `errors` and reads as empty; the caller throws [#validation] once
    /// every parameter has been read.
    public static OptionalInt intParam(Exchange ctx, String name, List<Map<String, Object>> errors) {
        String raw = ctx.queryParam(name);
        if (raw == null || raw.isEmpty()) return OptionalInt.empty();
        try {
            return OptionalInt.of(Integer.parseInt(raw.trim()));
        } catch (NumberFormatException _) {
            errors.add(error("invalid integer", name, raw));
            return OptionalInt.empty();
        }
    }

    /// One `{message, location: "query.<name>", value}` entry, keys in that order.
    public static Map<String, Object> error(String message, String name, String value) {
        var detail = new LinkedHashMap<String, Object>();
        detail.put("message", message);
        detail.put("location", "query." + name);
        detail.put("value", value);
        return detail;
    }

    /// The 400 `VALIDATION` `validation failed` envelope carrying `errors` as
    /// `details.errors`, in the order they were recorded.
    public static UseCaseException validation(List<Map<String, Object>> errors) {
        return new UseCaseException(UseCaseError.validation("VALIDATION", "validation failed")
                .withDetails(Map.of("errors", List.copyOf(errors))));
    }
}
