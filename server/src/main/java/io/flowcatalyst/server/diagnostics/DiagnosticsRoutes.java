package io.flowcatalyst.server.diagnostics;

import io.flowcatalyst.http.Exchange;
import io.flowcatalyst.http.Routes;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;

/// The operator diagnostics routes (`docs/diagnostics.md`), under the
/// router API's prefix (default `/router`):
///
/// | Route | Answers |
/// |---|---|
/// | `GET {prefix}/diagnostics` | what this runtime can produce, and the recording's bounds |
/// | `GET {prefix}/diagnostics/thread-dump` | every thread, virtual threads included, as JSON |
/// | `GET {prefix}/diagnostics/jfr?minutes=N` | the last N minutes (default 10) of the continuous recording, as a `.jfr` download |
/// | `GET {prefix}/diagnostics/jfr/events?messageId=…&group=…&minutes=N` | the FlowCatalyst events for one message or group, as JSON |
///
/// ### Never anonymous
///
/// The caller installs the router API's guard over `{prefix}/*` first
/// (`RouterAuth`): outside dev mode a platform bearer token, and
/// `PlatformTokenFilter` asks `platform:messaging:router:operate` for these
/// paths even though they are `GET`s — a recording holds message ids, target
/// hosts and the shape of the code, so reading one is an operator's act, not
/// a viewer's. In dev mode the guard is Basic auth, which is **open** when no
/// user is configured; `guarded = false` says so, and these routes then
/// refuse with 403 rather than hand a dump to anyone.
///
/// ### One at a time
///
/// A snapshot copies up to the recording's size bound to temp space and a
/// thread dump walks every thread; two at once only doubles the cost of
/// the question. A second request while one is being produced (or still
/// streaming) answers 429.
public final class DiagnosticsRoutes {

    /// The snapshot window when `minutes` is absent.
    static final int DEFAULT_MINUTES = 10;

    private final boolean guarded;
    private final Semaphore busy = new Semaphore(1);

    private DiagnosticsRoutes(boolean guarded) {
        this.guarded = guarded;
    }

    /// Mounts the routes under `prefix` (`null`/blank → `/router`).
    ///
    /// @param guarded whether the guard in front of `prefix` actually
    ///                authenticates — `false` only for dev mode's open Basic
    public static void register(Routes routes, String prefix, boolean guarded) {
        var p = prefix == null || prefix.isBlank() ? "/router" : prefix;
        var d = new DiagnosticsRoutes(guarded);
        routes.get(p + "/diagnostics", d::capabilities);
        routes.get(p + "/diagnostics/thread-dump", d::threadDump);
        routes.get(p + "/diagnostics/jfr", d::jfr);
        routes.get(p + "/diagnostics/jfr/events", d::events);
    }

    private void capabilities(Exchange ctx) {
        if (refused(ctx)) {
            return;
        }
        boolean nativeImage = nativeImage();
        Map<String, Object> jfr = new LinkedHashMap<>();
        jfr.put("available", ContinuousRecording.available());
        var recording = ContinuousRecording.running();
        jfr.put("recording", recording.isPresent());
        recording.ifPresent(r -> {
            jfr.put("name", r.getName());
            jfr.put("maxAgeSeconds", r.getMaxAge() == null ? null : r.getMaxAge().toSeconds());
            jfr.put("maxSizeBytes", r.getMaxSize());
            jfr.put("sizeBytes", r.getSize());
        });
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runtime", nativeImage ? "native-image" : "hotspot");
        body.put("jfr", jfr);
        body.put("threadDump", Map.of("virtualThreads", !nativeImage));
        ctx.json(body);
    }

    private void threadDump(Exchange ctx) throws Exception {
        if (refused(ctx) || !acquire(ctx)) {
            return;
        }
        try {
            stream(ctx, Diagnostics.threadDump(), false);
        } catch (Exception | Error e) {
            busy.release();
            throw e;
        }
    }

    private void jfr(Exchange ctx) throws Exception {
        if (refused(ctx)) {
            return;
        }
        var minutes = minutes(ctx);
        if (minutes == null || !acquire(ctx)) {
            return;
        }
        try {
            var snapshot = Diagnostics.jfrSnapshot(minutes);
            if (snapshot.isEmpty()) {
                busy.release();
                noRecording(ctx);
                return;
            }
            stream(ctx, snapshot.get(), true);
        } catch (Exception | Error e) {
            busy.release();
            throw e;
        }
    }

    private void events(Exchange ctx) throws Exception {
        if (refused(ctx)) {
            return;
        }
        var minutes = minutes(ctx);
        if (minutes == null) {
            return;
        }
        Diagnostics.EventQuery query;
        try {
            query = new Diagnostics.EventQuery(ctx.queryParam("messageId"), ctx.queryParam("group"), minutes);
        } catch (IllegalArgumentException e) {
            error(ctx, 400, "VALIDATION", e.getMessage());
            return;
        }
        if (!acquire(ctx)) {
            return;
        }
        try {
            var events = Diagnostics.events(query);
            if (events.isEmpty()) {
                noRecording(ctx);
                return;
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("messageId", query.messageId());
            body.put("group", query.group());
            body.put("minutes", minutes.toMinutes());
            body.put("limit", Diagnostics.MAX_EVENTS);
            body.put("events", events.get());
            ctx.json(body);
        } finally {
            busy.release();
        }
    }

    /// Streams `dump` and deletes it afterwards; the busy permit goes back
    /// when the adapter closes the stream, i.e. once the download is over.
    private void stream(Exchange ctx, Diagnostics.DumpFile dump, boolean attachment) throws Exception {
        long size = dump.size();
        var in = dump.open(busy::release);
        ctx.contentType(dump.contentType());
        if (attachment) {
            ctx.header("Content-Disposition", "attachment; filename=\"" + dump.filename() + "\"");
        }
        ctx.header("Cache-Control", "no-store");
        ctx.status(200).resultStream(in, size);
    }

    private boolean refused(Exchange ctx) {
        if (guarded) {
            return false;
        }
        error(ctx, 403, "DIAGNOSTICS_REQUIRE_AUTH", "diagnostics are never served anonymously: in dev mode set "
                + "FC_ROUTER_AUTH_USER and FC_ROUTER_AUTH_PASS, or use jcmd against the process");
        return true;
    }

    private boolean acquire(Exchange ctx) {
        if (busy.tryAcquire()) {
            return true;
        }
        ctx.header("Retry-After", "5");
        error(ctx, 429, "DIAGNOSTIC_IN_PROGRESS", "another diagnostic is being produced or downloaded; retry shortly");
        return false;
    }

    /// `minutes`, defaulting to [#DEFAULT_MINUTES]; `null` after answering
    /// 400 for anything but a positive integer.
    private static Duration minutes(Exchange ctx) {
        var raw = ctx.queryParam("minutes");
        if (raw == null || raw.isBlank()) {
            return Duration.ofMinutes(DEFAULT_MINUTES);
        }
        try {
            int minutes = Integer.parseInt(raw.strip());
            if (minutes > 0) {
                return Duration.ofMinutes(minutes);
            }
        } catch (NumberFormatException ignored) {
            // answered below
        }
        error(ctx, 400, "VALIDATION", "minutes must be a positive integer");
        return null;
    }

    private static void noRecording(Exchange ctx) {
        String why = ContinuousRecording.available()
                ? "no continuous flight recording is running (FC_JFR_ENABLED=false?) or it holds no data yet"
                : "this runtime has no flight recorder (a native image needs --enable-monitoring=jfr)";
        error(ctx, 404, "NO_RECORDING", why);
    }

    private static void error(Exchange ctx, int status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", code);
        body.put("message", message);
        ctx.status(status).json(body);
    }

    /// `org.graalvm.nativeimage.imagecode` is set only inside a native image.
    static boolean nativeImage() {
        return System.getProperty("org.graalvm.nativeimage.imagecode") != null;
    }
}
