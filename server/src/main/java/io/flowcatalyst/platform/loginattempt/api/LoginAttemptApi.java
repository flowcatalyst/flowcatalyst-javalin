package io.flowcatalyst.platform.loginattempt.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.flowcatalyst.platform.loginattempt.LoginAttempt;
import io.flowcatalyst.platform.loginattempt.LoginAttemptRepository;
import io.flowcatalyst.platform.loginattempt.LoginAttemptRepository.ListFilter;
import io.flowcatalyst.platform.shared.apicommon.CursorResponse;
import io.flowcatalyst.platform.shared.apicommon.KeysetCursor;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.Checks;
import io.flowcatalyst.sdk.usecase.UseCaseError;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.javalin.http.Context;
import io.javalin.router.JavalinDefaultRoutingApi;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/// The `/api/login-attempts` surface (spec §2) — one read-only route. The
/// handler does exactly: anchor gate → repository read → response; there
/// are no use cases on this aggregate. It runs inside [Auth#scoped].
///
/// | Method | Path | Status |
/// |---|---|---|
/// | GET | `/api/login-attempts` | 200 `LoginAttemptListResponse` = [CursorResponse] of [LoginAttemptResponse] |
public final class LoginAttemptApi {

    /// The page size when `pageSize` is absent or out of `1..MAX_PAGE_SIZE` (spec §3, open question 3).
    static final int DEFAULT_PAGE_SIZE = 50;
    static final int MAX_PAGE_SIZE = 200;

    private LoginAttemptApi() {
    }

    /// The handler's dependencies — a repository only; nothing here writes.
    public record State(LoginAttemptRepository repo) {
        public State {
            Objects.requireNonNull(repo, "repo");
        }
    }

    /// Mounts the endpoint; path, method and status code are the lockfile's.
    public static void register(JavalinDefaultRoutingApi routes, State s) {
        routes.get("/api/login-attempts", Auth.scoped(ctx -> list(ctx, s)));
    }

    // ── Handler ────────────────────────────────────────────────────────────

    private static void list(Context ctx, State s) {
        Checks.requireAnchor(Auth.current());
        int size = pageSize(ctx);
        List<LoginAttempt> rows = s.repo().findPage(listFilter(ctx), after(ctx), size + 1);
        ctx.json(page(rows, size));
    }

    // ── Read-side helpers ──────────────────────────────────────────────────

    /// Query params → filter (spec §3). Absent/empty → no filter; an
    /// unparseable date bound is no bound (open question 4).
    private static ListFilter listFilter(Context ctx) {
        return new ListFilter(
                queryParam(ctx, "attemptType"),
                queryParam(ctx, "outcome"),
                queryParam(ctx, "identifier"),
                queryParam(ctx, "principalId"),
                timestamp(queryParam(ctx, "dateFrom")),
                timestamp(queryParam(ctx, "dateTo")));
    }

    /// `after` → cursor, or `null` for the first page — also for a malformed
    /// token: this route's policy is "ignore", the audit list's is 400
    /// `CURSOR` (spec §3, open question 4).
    private static KeysetCursor after(Context ctx) {
        String token = queryParam(ctx, "after");
        return token == null ? null : KeysetCursor.parse(token).orElse(null);
    }

    /// From an over-fetched window of `size + 1` rows: the extra row only
    /// proves a next page exists; the cursor is the last *returned* row's.
    /// `items` is never `null`; `nextCursor` is omitted unless `hasMore` (spec §3).
    private static CursorResponse<LoginAttemptResponse> page(List<LoginAttempt> rows, int size) {
        boolean hasMore = rows.size() > size;
        List<LoginAttempt> shown = hasMore ? rows.subList(0, size) : rows;
        String next = hasMore && !shown.isEmpty() ? shown.getLast().cursor().encode() : null;
        return new CursorResponse<>(shown.stream().map(LoginAttemptResponse::from).toList(), next, hasMore);
    }

    /// `pageSize`: absent → default; out of range → default (not clamped —
    /// spec §3, open question 3); non-integer → 400 `VALIDATION`.
    private static int pageSize(Context ctx) {
        String raw = queryParam(ctx, "pageSize");
        if (raw == null) return DEFAULT_PAGE_SIZE;
        int size;
        try {
            size = Integer.parseInt(raw.trim());
        } catch (NumberFormatException _) {
            var detail = new LinkedHashMap<String, Object>();
            detail.put("message", "invalid integer");
            detail.put("location", "query.pageSize");
            detail.put("value", raw);
            throw new UseCaseException(UseCaseError.validation("VALIDATION", "validation failed")
                    .withDetails(Map.of("errors", List.of(detail))));
        }
        return size < 1 || size > MAX_PAGE_SIZE ? DEFAULT_PAGE_SIZE : size;
    }

    /// RFC 3339 with any offset → instant; `null` or unparseable → `null`.
    private static Instant timestamp(String raw) {
        if (raw == null) return null;
        try {
            return OffsetDateTime.parse(raw).toInstant();
        } catch (DateTimeParseException _) {
            return null;
        }
    }

    /// Absent or empty query parameter → `null`.
    private static String queryParam(Context ctx, String name) {
        String v = ctx.queryParam(name);
        return v == null || v.isEmpty() ? null : v;
    }

    // ── Wire DTOs (lockfile components) ────────────────────────────────────
    // The list envelope `LoginAttemptListResponse` is the standard
    // `{items, hasMore, nextCursor?}` — [CursorResponse], not a record here.

    /// The wire shape of one attempt: every key is always present — the
    /// optionals as JSON `null` (the mapper's default would omit them),
    /// `identifier` as `""` when the row has none (the lockfile types it
    /// non-null; spec §2, open question 2). Inside the JVM it is `null`:
    /// this mapping is the DTO's alone.
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record LoginAttemptResponse(
            String id,
            String attemptType,
            String outcome,
            String failureReason,
            String identifier,
            String principalId,
            String ipAddress,
            String userAgent,
            Instant attemptedAt) {

        public static LoginAttemptResponse from(LoginAttempt a) {
            return new LoginAttemptResponse(a.id(), a.attemptType().name(), a.outcome().name(), a.failureReason(),
                    a.identifier() == null ? "" : a.identifier(), a.principalId(), a.ipAddress(), a.userAgent(),
                    a.attemptedAt());
        }
    }
}
