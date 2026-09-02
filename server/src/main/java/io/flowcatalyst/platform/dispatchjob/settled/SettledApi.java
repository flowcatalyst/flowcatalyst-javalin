package io.flowcatalyst.platform.dispatchjob.settled;

import io.flowcatalyst.platform.dispatchjob.DispatchJobRepository;
import io.flowcatalyst.platform.shared.json.Json;
import io.javalin.http.Context;
import io.javalin.router.JavalinDefaultRoutingApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/// `POST /api/dispatch/settled` (dispatch-seam spec §6) — the router→platform
/// "settled message" hook that closes the fast-move half of the A-01
/// `BLOCK_ON_ERROR` group recovery: when a group's head fails terminally, the
/// router ACKs the untried buffered siblings off the broker immediately, then
/// fire-and-forgets a report here so the platform can mark those rows
/// recoverable instead of leaving them stranded at `QUEUED`/`PROCESSING`
/// forever (the reaper, [io.flowcatalyst.platform.dispatchjob.DispatchJobReaper],
/// is the backstop for a dropped call or a router crash between the ACK and
/// this call).
///
/// Public route — registered from `Platform` OUTSIDE the platform JWT
/// middleware (`Platform.isPublicPath`), since the router carries no
/// platform JWT and self-verifies each job's scheduler-signed HMAC bearer
/// instead ([HmacTokenVerifier]). Not part of the SDK lockfile (spec §10):
/// this is a router-to-platform internal callback, not an SDK operation.
///
/// | Method | Path | Auth | Status |
/// |---|---|---|---|
/// | POST | `/api/dispatch/settled` | per-item HMAC token, self-verified | 200 / 400 / 401 [SettledResponse] |
public final class SettledApi {

    private static final Logger LOG = LoggerFactory.getLogger(SettledApi.class);

    /// A generous ceiling against a pathological request, not a real-traffic
    /// limit (spec §3's timing table).
    static final int MAX_JOBS_PER_REQUEST = 10_000;
    /// Request body size cap.
    static final long MAX_BODY_BYTES = 1L << 20; // 1 MiB
    /// Recorded on every row this call settles when the caller sends a blank reason.
    static final String DEFAULT_REASON =
            "settled: router ACKed as an untried buffered sibling behind a failed BLOCK_ON_ERROR head";

    private SettledApi() {
    }

    /// The handler's dependencies.
    public record State(DispatchJobRepository repo, HmacTokenVerifier verifier) {
        public State {
            Objects.requireNonNull(repo, "repo");
            Objects.requireNonNull(verifier, "verifier");
        }
    }

    public static void register(JavalinDefaultRoutingApi routes, State s) {
        routes.post("/api/dispatch/settled", ctx -> serve(ctx, s));
    }

    private static void serve(Context ctx, State s) {
        // Reject on the declared Content-Length BEFORE buffering the body (audit finding: a
        // caller that declares a multi-GB body must not make this handler read it all into
        // memory first only to discard it). `contentLength()` is -1 for a chunked body with no
        // declared length, so the post-read check below is still needed for that case.
        if (ctx.contentLength() > MAX_BODY_BYTES) {
            ctx.status(400).json(SettledResponse.EMPTY);
            return;
        }
        byte[] body = ctx.bodyAsBytes();
        if (body.length > MAX_BODY_BYTES) {
            ctx.status(400).json(SettledResponse.EMPTY);
            return;
        }
        SettledRequest req;
        try {
            req = Json.MAPPER.readValue(body, SettledRequest.class);
        } catch (JacksonException e) {
            ctx.status(400).json(SettledResponse.EMPTY);
            return;
        }
        List<SettledRequest.Job> jobs = req.jobs() == null ? List.of() : req.jobs();
        if (jobs.isEmpty()) {
            ctx.status(200).json(SettledResponse.EMPTY);
            return;
        }
        if (jobs.size() > MAX_JOBS_PER_REQUEST) {
            ctx.status(400).json(SettledResponse.EMPTY);
            return;
        }
        String reason = req.reason() == null || req.reason().isBlank() ? DEFAULT_REASON : req.reason().trim();

        // Verify each id/token pair independently — a bad token for one job
        // must not sink the rest of the batch (spec §6).
        var ids = new ArrayList<String>(jobs.size());
        for (SettledRequest.Job job : jobs) {
            String id = job.id() == null ? "" : job.id().trim();
            String token = job.token() == null ? "" : job.token().trim();
            if (id.isEmpty() || token.isEmpty()) continue;
            if (!s.verifier().verify(id, token)) {
                LOG.warn("dispatch settled: bad auth token, job_id={}", id);
                continue;
            }
            ids.add(id);
        }
        if (ids.isEmpty()) {
            // Nothing verified — an empty/garbage batch, or every token
            // failed. The reaper remains the backstop for anything
            // genuinely stranded (spec §6).
            ctx.status(401).json(SettledResponse.EMPTY);
            return;
        }

        List<String> settled = s.repo().settleAcked(ids, reason);
        if (!settled.isEmpty()) {
            LOG.info("dispatch settled: {} siblings marked PENDING, reason={}", settled.size(), reason);
        }
        ctx.status(200).json(new SettledResponse(settled.size(), settled.isEmpty() ? null : settled));
    }

    // ── Wire DTOs (spec §6) ────────────────────────────────────────────────

    /// `{reason, jobs: [{id, token}]}`.
    public record SettledRequest(String reason, List<Job> jobs) {
        public record Job(String id, String token) {
        }
    }

    /// `{settled, ids?}` — `ids` omitted when empty (default `NON_ABSENT` inclusion drops `null`).
    public record SettledResponse(int settled, List<String> ids) {
        static final SettledResponse EMPTY = new SettledResponse(0, null);
    }
}
