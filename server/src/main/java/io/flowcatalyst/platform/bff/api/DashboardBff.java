package io.flowcatalyst.platform.bff.api;

import io.flowcatalyst.platform.bff.DashboardRepository;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.Checks;
import io.flowcatalyst.http.Exchange;
import io.flowcatalyst.http.Routes;

import java.util.Objects;

/// `GET /bff/dashboard/stats` (bff spec §2): a mix of exact control-plane
/// counts and `pg_class.reltuples`-approximated message-plane counts, for
/// the SPA's dashboard tile row.
///
/// | Method | Path | Status |
/// |---|---|---|
/// | GET | `/bff/dashboard/stats` | 200 [StatsResponse] |
///
/// **Gate (bff spec §2):** admin — anchor scope or the super-admin wildcard,
/// Go `auth.IsAdmin`. The whole-platform counts are not for client-scoped
/// users.
public final class DashboardBff {

    /// The message-plane tables approximated via `pg_class.reltuples` (bff spec §2).
    private static final String[] APPROX_TABLES = {"msg_events", "msg_dispatch_jobs", "aud_logs", "iam_login_attempts"};

    private DashboardBff() {
    }

    public record State(DashboardRepository repo) {
        public State {
            Objects.requireNonNull(repo, "repo");
        }
    }

    public static void register(Routes routes, State s) {
        routes.get("/bff/dashboard/stats", Auth.scoped(ctx -> stats(ctx, s)));
    }

    private static void stats(Exchange ctx, State s) {
        Checks.requireAdmin(Auth.current()); // 401 unauthenticated, 403 ADMIN_REQUIRED otherwise
        var exact = s.repo().exactCounts();
        var approx = s.repo().approximateCounts(APPROX_TABLES);
        ctx.json(new StatsResponse(
                exact.totalClients(), exact.activeUsers(), exact.rolesDefined(),
                approx.getOrDefault("msg_events", 0L),
                approx.getOrDefault("msg_dispatch_jobs", 0L),
                approx.getOrDefault("aud_logs", 0L),
                approx.getOrDefault("iam_login_attempts", 0L)));
    }

    /// The wire shape (bff spec §2 / SPA `DashboardStats`); every field a bare integer, never omitted.
    public record StatsResponse(long totalClients, long activeUsers, long rolesDefined, long eventsApprox,
                                long dispatchJobsApprox, long auditLogsApprox, long loginAttemptsApprox) {
    }
}
