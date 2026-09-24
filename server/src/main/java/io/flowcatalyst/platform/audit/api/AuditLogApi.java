package io.flowcatalyst.platform.audit.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.flowcatalyst.platform.audit.AuditLog;
import io.flowcatalyst.platform.audit.StoredAuditRedaction;
import io.flowcatalyst.platform.audit.AuditLogRepository;
import io.flowcatalyst.platform.audit.AuditLogRepository.CursorFilter;
import io.flowcatalyst.platform.audit.AuditLogRepository.Facet;
import io.flowcatalyst.platform.audit.AuditLogRepository.ListFilter;
import io.flowcatalyst.platform.shared.apicommon.KeysetCursor;
import io.flowcatalyst.platform.shared.apicommon.QueryParams;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.Checks;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.http.Exchange;
import io.flowcatalyst.http.Handler;
import io.flowcatalyst.http.Routes;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

import static io.flowcatalyst.platform.shared.auth.Permission.AUDIT_LOG_VIEW;

/// The `/api/audit-logs` surface (spec §2) — read-only. Every handler does
/// exactly: coarse permission → repository read → response; there are no
/// use cases on this aggregate. Every handler runs inside [Auth#scoped].
///
/// | Method | Path | Status |
/// |---|---|---|
/// | GET | `/api/audit-logs` | 200 [AuditLogListResponse] (cursor) |
/// | GET | `/api/audit-logs/recent` | 200 [AuditLogListResponse] (alias of the list) |
/// | GET | `/api/audit-logs/entity-types` | 200 [AuditLogEntityTypesResponse] |
/// | GET | `/api/audit-logs/operations` | 200 [AuditLogOperationsResponse] |
/// | GET | `/api/audit-logs/application-ids` | 200 [AuditLogApplicationIDsResponse] |
/// | GET | `/api/audit-logs/client-ids` | 200 [AuditLogClientIDsResponse] |
/// | GET | `/api/audit-logs/entity/{entityType}/{entityId}` | 200 [AuditLogListResponse] (first 500, `hasMore` false) |
/// | GET | `/api/audit-logs/principal/{principalId}` | 200 [AuditLogListResponse] (first 500, `hasMore` false) |
/// | GET | `/api/audit-logs/{id}` | 200 [AuditLogResponse] |
public final class AuditLogApi {

    /// The page size when `pageSize` is absent or out of `1..MAX_PAGE_SIZE` (spec §3, open question 1).
    static final int DEFAULT_PAGE_SIZE = 50;
    static final int MAX_PAGE_SIZE = 200;
    /// The fixed window of the unpaginated entity / principal lists and of the facets (spec §5, §6).
    static final int FILTERED_LIST_LIMIT = 500;
    static final int FACET_LIMIT = 500;

    private AuditLogApi() {
    }

    /// The handlers' dependencies — a repository only; nothing here writes.
    public record State(AuditLogRepository repo) {
        public State {
            Objects.requireNonNull(repo, "repo");
        }
    }

    /// Mounts the endpoints; paths, methods and status codes are the lockfile's.
    /// The literal segments are registered before `{id}` so they win.
    public static void register(Routes routes, State s) {
        Handler list = Auth.scoped(ctx -> list(ctx, s));
        routes.get("/api/audit-logs", list);
        routes.get("/api/audit-logs/recent", list); // historical alias, the SPA's "recent activity" panel still uses it
        routes.get("/api/audit-logs/entity-types", Auth.scoped(ctx -> facet(ctx, s, Facet.ENTITY_TYPE, AuditLogEntityTypesResponse::new)));
        routes.get("/api/audit-logs/operations", Auth.scoped(ctx -> facet(ctx, s, Facet.OPERATION, AuditLogOperationsResponse::new)));
        routes.get("/api/audit-logs/application-ids", Auth.scoped(ctx -> facet(ctx, s, Facet.APPLICATION_ID, AuditLogApplicationIDsResponse::new)));
        routes.get("/api/audit-logs/client-ids", Auth.scoped(ctx -> facet(ctx, s, Facet.CLIENT_ID, AuditLogClientIDsResponse::new)));
        routes.get("/api/audit-logs/entity/{entityType}/{entityId}", Auth.scoped(ctx -> byEntity(ctx, s)));
        routes.get("/api/audit-logs/principal/{principalId}", Auth.scoped(ctx -> byPrincipal(ctx, s)));
        routes.get("/api/audit-logs/{id}", Auth.scoped(ctx -> getById(ctx, s)));
    }

    // ── Handlers ───────────────────────────────────────────────────────────

    private static void list(Exchange ctx, State s) {
        Checks.require(Auth.current(), AUDIT_LOG_VIEW);
        int size = pageSize(ctx);
        List<AuditLog> rows = s.repo().findWithCursor(cursorFilter(ctx), after(ctx), size + 1);
        ctx.json(AuditLogListResponse.page(rows, size));
    }

    private static void getById(Exchange ctx, State s) {
        Checks.require(Auth.current(), AUDIT_LOG_VIEW);
        String id = ctx.pathParam("id");
        ctx.json(AuditLogResponse.from(s.repo().findById(id).orElseThrow(() -> HttpError.notFound("AuditLog", id))));
    }

    private static void byEntity(Exchange ctx, State s) {
        Checks.require(Auth.current(), AUDIT_LOG_VIEW);
        var filter = new ListFilter(ctx.pathParam("entityType"), ctx.pathParam("entityId"), null, null, null, null);
        ctx.json(AuditLogListResponse.unpaged(s.repo().findWithFilters(filter, FILTERED_LIST_LIMIT, 0)));
    }

    private static void byPrincipal(Exchange ctx, State s) {
        Checks.require(Auth.current(), AUDIT_LOG_VIEW);
        var filter = new ListFilter(null, null, ctx.pathParam("principalId"), null, null, null);
        ctx.json(AuditLogListResponse.unpaged(s.repo().findWithFilters(filter, FILTERED_LIST_LIMIT, 0)));
    }

    /// One handler for the four facet routes; `wrap` builds the route's own envelope.
    private static void facet(Exchange ctx, State s, Facet facet, Function<List<String>, Object> wrap) {
        Checks.require(Auth.current(), AUDIT_LOG_VIEW);
        ctx.json(wrap.apply(s.repo().distinctValues(facet, FACET_LIMIT)));
    }

    // ── Read-side helpers ──────────────────────────────────────────────────

    /// Query params → keyset filter (spec §3). Absent/empty → no filter; the
    /// id lists are CSV, trimmed, blanks dropped.
    private static CursorFilter cursorFilter(Exchange ctx) {
        return new CursorFilter(
                queryParam(ctx, "entityType"),
                queryParam(ctx, "entityId"),
                queryParam(ctx, "principalId"),
                queryParam(ctx, "operation"),
                csv(queryParam(ctx, "applicationIds")),
                csv(queryParam(ctx, "clientIds")));
    }

    /// `after` → cursor, or `null` for the first page. This route's policy
    /// for a malformed token (spec §3, §4): 400 `CURSOR` `invalid cursor`.
    private static KeysetCursor after(Exchange ctx) {
        String token = queryParam(ctx, "after");
        return token == null ? null
                : KeysetCursor.parse(token).orElseThrow(() -> UseCaseException.validation("CURSOR", "invalid cursor"));
    }

    /// `pageSize`: absent → default; out of range → default (not clamped —
    /// spec §3, open question 1); non-integer → 400 `VALIDATION` ([QueryParams]).
    private static int pageSize(Exchange ctx) {
        int size = QueryParams.intParam(ctx, "pageSize").orElse(DEFAULT_PAGE_SIZE);
        return size < 1 || size > MAX_PAGE_SIZE ? DEFAULT_PAGE_SIZE : size;
    }

    /// Absent or empty query parameter → `null`.
    private static String queryParam(Exchange ctx, String name) {
        String v = ctx.queryParam(name);
        return v == null || v.isEmpty() ? null : v;
    }

    /// Comma-separated → trimmed, non-blank parts; `null` → empty list (no filter).
    private static List<String> csv(String value) {
        if (value == null) return List.of();
        return Arrays.stream(value.split(",")).map(String::trim).filter(p -> !p.isEmpty()).toList();
    }

    // ── Wire DTOs (lockfile components) ────────────────────────────────────

    /// The wire shape of one entry; optional fields are omitted when absent.
    /// `operationJson` is the command document **as a compact JSON string**
    /// (the SPA `JSON.parse`s it), never a nested object (spec §2).
    public record AuditLogResponse(
            String id,
            String entityType,
            String entityId,
            String operation,
            String operationJson,
            String principalId,
            String principalName,
            String applicationId,
            String clientId,
            Instant performedAt) {

        /// Redacted on read ([StoredAuditRedaction]): a row stored before
        /// source-side redaction, or sent by an SDK that predates it, is never
        /// served with its secret — whether or not the sweep has run.
        public static AuditLogResponse from(AuditLog a) {
            return new AuditLogResponse(a.id(), a.entityType(), a.entityId(), a.operation(),
                    a.operationJson() == null ? null
                            : Json.write(StoredAuditRedaction.redact(a.operation(), a.operationJson())),
                    a.principalId(), a.principalName(), a.applicationId(), a.clientId(), a.performedAt());
        }
    }

    /// `{auditLogs, hasMore, nextCursor?}` — the cursor envelope of the list
    /// routes and the unpaginated envelope of the entity / principal lists.
    /// `auditLogs` is never `null`; `nextCursor` is omitted unless `hasMore`.
    public record AuditLogListResponse(
            List<AuditLogResponse> auditLogs,
            boolean hasMore,
            @JsonInclude(JsonInclude.Include.NON_EMPTY) String nextCursor) {

        public AuditLogListResponse {
            auditLogs = auditLogs == null ? List.of() : List.copyOf(auditLogs);
        }

        /// From an over-fetched window of `size + 1` rows: the extra row only
        /// proves a next page exists; the cursor is the last *returned* row's.
        static AuditLogListResponse page(List<AuditLog> rows, int size) {
            boolean hasMore = rows.size() > size;
            List<AuditLog> shown = hasMore ? rows.subList(0, size) : rows;
            String next = hasMore && !shown.isEmpty() ? shown.getLast().cursor().encode() : null;
            return new AuditLogListResponse(shown.stream().map(AuditLogResponse::from).toList(), hasMore, next);
        }

        /// A fixed window with no pagination: `hasMore` false, no cursor.
        static AuditLogListResponse unpaged(List<AuditLog> rows) {
            return new AuditLogListResponse(rows.stream().map(AuditLogResponse::from).toList(), false, null);
        }
    }

    /// `{entityTypes: [string]}`.
    public record AuditLogEntityTypesResponse(List<String> entityTypes) {
        public AuditLogEntityTypesResponse {
            entityTypes = entityTypes == null ? List.of() : List.copyOf(entityTypes);
        }
    }

    /// `{operations: [string]}`.
    public record AuditLogOperationsResponse(List<String> operations) {
        public AuditLogOperationsResponse {
            operations = operations == null ? List.of() : List.copyOf(operations);
        }
    }

    /// `{applicationIds: [string]}`.
    public record AuditLogApplicationIDsResponse(List<String> applicationIds) {
        public AuditLogApplicationIDsResponse {
            applicationIds = applicationIds == null ? List.of() : List.copyOf(applicationIds);
        }
    }

    /// `{clientIds: [string]}`.
    public record AuditLogClientIDsResponse(List<String> clientIds) {
        public AuditLogClientIDsResponse {
            clientIds = clientIds == null ? List.of() : List.copyOf(clientIds);
        }
    }
}
