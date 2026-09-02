package io.flowcatalyst.router.settled;

import io.flowcatalyst.platform.shared.json.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/// Production [SettledReporter]: POSTs `<base>/api/dispatch/settled`
/// (`docs/spec/dispatch-seam.md` §6) so the platform can mark the ACKed
/// siblings recoverable instead of leaving them stranded at
/// `QUEUED`/`PROCESSING` forever.
///
/// ### Fire-and-forget, with its own bounded timeout
///
/// [#report] returns at once: the actual POSTing runs on a dedicated virtual
/// thread, entirely decoupled from the caller. This is load-bearing, not a
/// convenience — [#report] is called from inside a pool's drainer right
/// after it ACKed a `BLOCK_ON_ERROR` group's messages, and that thread must
/// never sit blocked on this class's HTTP call (router-specification.md
/// §5.4). A router that dies before the report lands is recovered by the
/// platform's own reaper sweep, which is why this call is allowed to be
/// best-effort at all.
///
/// ### Chunked, sequential, always attempted in full
///
/// A batch is POSTed in chunks of at most [#CHUNK_SIZE] jobs — comfortably
/// under the endpoint's own 1 MiB body / 10,000-job ceiling — one request
/// after another. A chunk that fails (non-200, or a transport error) is
/// logged at WARN and the remaining chunks are still sent: the endpoint's
/// idempotency guard (`status IN ('QUEUED','PROCESSING')`) makes a retried
/// or overlapping chunk harmless, so there is nothing to gain by giving up
/// on the rest of the batch over one bad chunk.
///
/// ### Its own client, on purpose
///
/// This is one known, trusted, low-volume destination — unlike an arbitrary
/// mediation target — so it gets a small dedicated [HttpClient] rather than
/// borrowing [io.flowcatalyst.router.pool.HttpMediator]'s breaker/rate-limit
/// machinery, which exists for a different problem.
public final class HttpSettledReporter implements SettledReporter {

    private static final Logger log = LoggerFactory.getLogger(HttpSettledReporter.class);

    private static final String SETTLED_PATH = "/api/dispatch/settled";

    /// A group larger than this is reported over several sequential requests
    /// instead of one (`docs/spec/dispatch-seam.md` §6's 1,000-job chunk).
    static final int CHUNK_SIZE = 1000;

    /// Bounds a single chunk's HTTP call when no timeout is given —
    /// independent of the delivery pipeline's own timeouts
    /// (router-specification.md §5.4).
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    private final String url;
    private final HttpClient client;
    private final Duration timeout;

    /// @param baseUrl the platform's base URL (trailing slashes are
    ///                 tolerated and stripped); [#SETTLED_PATH] is appended
    public HttpSettledReporter(String baseUrl) {
        this(baseUrl, DEFAULT_TIMEOUT);
    }

    /// @param timeout bounds a single chunk's HTTP call — a test shortens
    ///                 this to assert the fire-and-forget property without
    ///                 waiting out the production default
    public HttpSettledReporter(String baseUrl, Duration timeout) {
        this.url = stripTrailingSlashes(baseUrl) + SETTLED_PATH;
        this.timeout = timeout;
        this.client = HttpClient.newBuilder()
                // A 3xx here would silently drop the body exactly as it does
                // for a real mediation target; there is nothing this class
                // could sensibly do with a redirected settled hook.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    private static String stripTrailingSlashes(String baseUrl) {
        var end = baseUrl.length();
        while (end > 0 && baseUrl.charAt(end - 1) == '/') {
            end--;
        }
        return baseUrl.substring(0, end);
    }

    @Override
    public void report(SettledReport report) {
        if (report.jobs().isEmpty()) {
            // Nothing to tell the platform; an empty POST would be a legal
            // no-op there too, but there is no reason to make the call.
            return;
        }
        Thread.ofVirtual().name("settled-reporter").start(() -> deliver(report));
    }

    private void deliver(SettledReport report) {
        var jobs = report.jobs();
        for (int start = 0; start < jobs.size(); start += CHUNK_SIZE) {
            var end = Math.min(start + CHUNK_SIZE, jobs.size());
            postChunk(report, jobs.subList(start, end));
        }
    }

    private void postChunk(SettledReport report, List<SettledJob> chunk) {
        byte[] body;
        try {
            body = Json.MAPPER.writeValueAsBytes(new Body(report.reason(), chunk));
        } catch (RuntimeException e) {
            warn(report, "could not encode request: " + e, e);
            return;
        }

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(url))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .build();
        } catch (RuntimeException e) {
            warn(report, "malformed settled url " + url, e);
            return;
        }

        try {
            var response = client.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() != 200) {
                warn(report, "settled hook returned " + response.statusCode(), null);
            }
        } catch (IOException e) {
            warn(report, "request failed: " + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            warn(report, "interrupted", null);
        }
    }

    private static final String WARN_TEMPLATE =
            "settled-message hook failed; the platform reaper is the backstop (pool={} group={}): {}";

    // The one line this call leaves behind: the platform reaper is the
    // correctness backstop, so this is a latency/visibility loss, never a
    // lost message — but an operator watching for exactly that ought to be
    // able to find it. Two overloads rather than a nullable trailing
    // Throwable: SLF4J only recognises the last varargs element as the
    // exception when it actually IS one, and `null` fails that check
    // silently, so a shared method taking `Throwable cause` and always
    // appending it would just drop a stray null argument instead of a
    // stack trace.
    private static void warn(SettledReport report, String detail, Throwable cause) {
        if (cause != null) {
            log.warn(WARN_TEMPLATE, report.poolCode(), report.group(), detail, cause);
        } else {
            log.warn(WARN_TEMPLATE, report.poolCode(), report.group(), detail);
        }
    }

    /// The exact wire shape (`docs/spec/dispatch-seam.md` §6):
    /// `{"reason": string, "jobs": [{"id": string, "token": string}]}`.
    /// Record component order is field order under the shared mapper's
    /// record support, so this and [SettledJob]'s `(id, token)` order are
    /// what pins the byte-for-byte shape.
    private record Body(String reason, List<SettledJob> jobs) {
    }
}
