package io.flowcatalyst.http;

import io.flowcatalyst.platform.shared.TestHttp;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/// Pins `docs/spec/http-seam.md` §4 rows 1-9 (row 10, `NoFrameworkLeakTest`,
/// lands in a later unit) through the Javalin adapter under test —
/// `TestHttp.routes(...)`, the JDK `HttpClient`, no shortcuts through the
/// adapter's internals. The same class pins the Vert.x adapter in Phase 2
/// unchanged.
class SeamContractTest {

    private static TestHttp app;

    /// Records the byte payload streamed by `/seam/stream` and whether it
    /// closed, for row 7.
    private static final byte[] STREAM_BYTES = "stream-body".getBytes(StandardCharsets.UTF_8);
    private static final AtomicReference<TrackingStream> STREAM = new AtomicReference<>();

    /// `Thread.currentThread()` recorded by the before filter, the route
    /// handler and the after filter on `/seam/thread`, for row 8.
    private static final List<Thread> THREADS = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void start() {
        app = TestHttp.routes(routes -> {
            // The three exception mappers a real bootstrap site would
            // install through `HttpError` — reproduced here as a fixture so
            // this test does not depend on that (out-of-scope, unedited)
            // class. Row 4's status→code table lives in the HttpException
            // arm; row 3's specificity is exercised by UseCaseException vs.
            // the Exception.class fallback.
            routes.exception(Exception.class, (e, ctx) ->
                    ctx.status(500).json(Map.of("error", "INTERNAL", "message", String.valueOf(e.getMessage()))));
            routes.exception(UseCaseException.class, (e, ctx) ->
                    ctx.status(e.httpStatus()).json(Map.of("error", e.code(), "message", e.error().message())));
            routes.exception(HttpException.class, (e, ctx) ->
                    ctx.status(e.status()).json(Map.of("error", codeFor(e.status()), "message", String.valueOf(e.getMessage()))));

            // Row 1: bodiless rule.
            routes.get("/seam/204", ctx -> ctx.status(204));
            routes.get("/seam/nobody", ctx -> { /* writes nothing */ });
            routes.get("/seam/json", ctx -> ctx.json(Map.of("ok", true)));

            // Row 2: 404 envelope, wrong method still 404.
            routes.get("/seam/only-get", ctx -> ctx.result("only-get"));

            // Row 3: exception specificity through the wire.
            routes.get("/seam/usecase", ctx -> {
                throw UseCaseException.resourceNotFound("Widget", "w1");
            });

            // Row 4: HttpException -> status-named envelope, every arm.
            routes.get("/seam/http-exception/{status}", ctx -> {
                throw new HttpException(Integer.parseInt(ctx.pathParam("status")), "boom");
            });

            // Row 5: skipRemainingHandlers stops the handler, not `after`.
            routes.before("/seam/skip", ctx -> {
                ctx.status(429).result("blocked");
                ctx.skipRemainingHandlers();
            });
            routes.get("/seam/skip", ctx -> ctx.header("X-Handler-Ran", "true"));
            routes.after(ctx -> {
                if ("/seam/skip".equals(ctx.path())) ctx.header("X-After-Ran", "true");
            });

            // Row 6: cookie attributes.
            routes.get("/seam/cookie", ctx -> ctx.cookie(
                    new HttpCookie("sid", "abc123", "/x", 60, true, true, HttpCookie.SameSite.STRICT)));

            // Row 7: result(InputStream) streams and closes.
            routes.get("/seam/stream", ctx -> {
                var stream = new TrackingStream(STREAM_BYTES);
                STREAM.set(stream);
                ctx.result(stream);
            });

            // Row 8: one thread for before + handler + after.
            routes.before("/seam/thread", ctx -> THREADS.add(Thread.currentThread()));
            routes.get("/seam/thread", ctx -> {
                THREADS.add(Thread.currentThread());
                ctx.result("ok");
            });
            routes.after(ctx -> {
                if ("/seam/thread".equals(ctx.path())) THREADS.add(Thread.currentThread());
            });
        });
    }

    @AfterAll
    static void stop() {
        app.close();
    }

    /// Row 4's status→code table, reproduced in the test's own mapper
    /// fixture (spec §4 row 4) so the mutant step can drop an arm without
    /// touching production code.
    private static String codeFor(int status) {
        return switch (status) {
            case 400 -> "BAD_REQUEST";
            case 401 -> "UNAUTHORIZED";
            case 403 -> "FORBIDDEN";
            case 404 -> "NOT_FOUND";
            case 409 -> "CONFLICT";
            default -> "INTERNAL";
        };
    }

    // ── Row 1: bodiless rule ─────────────────────────────────────────────

    @Test
    void a204CarriesNoContentType() {
        var r = app.get("/seam/204");
        assertThat(r.statusCode()).isEqualTo(204);
        assertThat(r.headers().firstValue("Content-Type")).isEmpty();
    }

    @Test
    void a200WhoseHandlerWroteNoBodyCarriesNoContentType() {
        var r = app.get("/seam/nobody");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.headers().firstValue("Content-Type")).isEmpty();
    }

    @Test
    void aJson200CarriesTheJsonContentType() {
        var r = app.get("/seam/json");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.headers().firstValue("Content-Type")).hasValueSatisfying(ct -> assertThat(ct).contains("application/json"));
    }

    // ── Row 2: 404 envelope, never 405 ───────────────────────────────────

    @Test
    void anUnknownPathAnswersTheNotFoundEnvelope() {
        var r = app.get("/seam/does-not-exist");
        assertThat(r.statusCode()).isEqualTo(404);
        assertThat(r.body()).contains("\"NOT_FOUND\"");
    }

    @Test
    void aKnownPathWithTheWrongMethodAlsoAnswers404NeverA405() {
        var r = app.send("POST", "/seam/only-get", null);
        assertThat(r.statusCode()).isEqualTo(404);
        assertThat(r.body()).contains("\"NOT_FOUND\"");
    }

    // ── Row 3: exception specificity ─────────────────────────────────────

    @Test
    void aUseCaseExceptionIsMappedByItsOwnMapperNotTheCatchAll() {
        var r = app.get("/seam/usecase");
        assertThat(r.statusCode()).isEqualTo(404);
        assertThat(r.body()).contains("\"Widget_NOT_FOUND\"");
        assertThat(r.body()).doesNotContain("\"INTERNAL\"");
    }

    @Test
    void resolveReturnsTheExactRegisteredClassMapperRegardlessOfRegistrationOrder() {
        var mappers = new ExceptionMappers();
        var seen = new CopyOnWriteArrayList<String>();
        // Registered broad-before-narrow ("reversed" relative to
        // specificity) — resolution must still prefer the exact class.
        mappers.register(RuntimeException.class, (e, ctx) -> seen.add("RuntimeException"));
        mappers.register(NarrowException.class, (e, ctx) -> seen.add("NarrowException"));

        var resolved = mappers.resolve(new NarrowException());

        assertThat(resolved).isPresent();
        resolved.get().handle(new NarrowException(), null);
        assertThat(seen).containsExactly("NarrowException");
    }

    private static final class NarrowException extends RuntimeException {
    }

    // ── Row 4: HttpException status→code table ───────────────────────────

    @Test
    void httpException400IsBadRequest() {
        assertHttpExceptionEnvelope(400, "BAD_REQUEST");
    }

    @Test
    void httpException401IsUnauthorized() {
        assertHttpExceptionEnvelope(401, "UNAUTHORIZED");
    }

    @Test
    void httpException403IsForbidden() {
        assertHttpExceptionEnvelope(403, "FORBIDDEN");
    }

    @Test
    void httpException404IsNotFound() {
        assertHttpExceptionEnvelope(404, "NOT_FOUND");
    }

    @Test
    void httpException409IsConflict() {
        assertHttpExceptionEnvelope(409, "CONFLICT");
    }

    @Test
    void httpExceptionWithAnUnlistedStatusIsInternal() {
        assertHttpExceptionEnvelope(418, "INTERNAL");
    }

    private void assertHttpExceptionEnvelope(int status, String code) {
        var r = app.get("/seam/http-exception/" + status);
        assertThat(r.statusCode()).isEqualTo(status);
        assertThat(r.body()).contains("\"" + code + "\"");
    }

    // ── Row 5: skipRemainingHandlers ─────────────────────────────────────

    @Test
    void skipRemainingHandlersStopsTheRouteHandlerButAfterStillRuns() {
        var r = app.get("/seam/skip");
        assertThat(r.statusCode()).isEqualTo(429);
        assertThat(r.headers().firstValue("X-Handler-Ran")).as("route handler must not have run").isEmpty();
        assertThat(r.headers().firstValue("X-After-Ran")).as("after filter must still run").contains("true");
    }

    // ── Row 6: cookie attributes ──────────────────────────────────────────

    @Test
    void cookieAttributesSurviveOnTheWire() {
        var r = app.get("/seam/cookie");
        var setCookie = r.headers().firstValue("Set-Cookie")
                .orElseThrow(() -> new AssertionError("no Set-Cookie header"));
        assertThat(setCookie).contains("sid=abc123");
        assertThat(setCookie).containsIgnoringCase("SameSite=Strict");
        assertThat(setCookie).containsIgnoringCase("HttpOnly");
        assertThat(setCookie).containsIgnoringCase("Secure");
        assertThat(setCookie).containsIgnoringCase("Path=/x");
        assertThat(setCookie).containsIgnoringCase("Max-Age=60");
    }

    // ── Row 7: result(InputStream) streams and closes ────────────────────

    @Test
    void resultInputStreamStreamsTheBytesAndClosesTheStream() {
        var r = app.get("/seam/stream");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).isEqualTo(new String(STREAM_BYTES, StandardCharsets.UTF_8));

        var stream = STREAM.get();
        assertThat(stream).as("route handler ran and recorded its stream").isNotNull();
        waitUntil(() -> stream.closed, Duration.ofSeconds(5));
        assertThat(stream.closed).isTrue();
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition, Duration budget) {
        long deadline = System.nanoTime() + budget.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
    }

    /// A `ByteArrayInputStream` that records whether `close()` was called.
    private static final class TrackingStream extends ByteArrayInputStream {
        volatile boolean closed;

        TrackingStream(byte[] buf) {
            super(buf);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }

    // ── Row 8: one thread per request ─────────────────────────────────────

    @Test
    void beforeHandlerAndAfterRunOnOneThread() {
        THREADS.clear();
        var r = app.get("/seam/thread");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(THREADS).hasSize(3);
        assertThat(THREADS).containsOnly(THREADS.get(0));
    }

    // ── Row 9: registry lists routes, not filters ─────────────────────────

    @Test
    void registryListsEveryRouteWithItsGroupAndNoFilters() {
        try (var fixture = TestHttp.routes(routes -> {
            routes.get("/fixture/a", ctx -> ctx.result("a"));
            routes.in(Group.LOGIN).post("/fixture/b", ctx -> ctx.result("b"));
            routes.in(Group.INGEST).put("/fixture/c", ctx -> { });
            routes.delete("/fixture/d", ctx -> { });
            routes.before(ctx -> { });
            routes.after(ctx -> { });
            routes.exception(RuntimeException.class, (e, ctx) -> { });
        })) {
            var regs = fixture.registry().registrations();
            assertThat(regs).containsExactlyInAnyOrder(
                    new RouteRegistry.Registration("GET", "/fixture/a", null),
                    new RouteRegistry.Registration("POST", "/fixture/b", Group.LOGIN),
                    new RouteRegistry.Registration("PUT", "/fixture/c", Group.INGEST),
                    new RouteRegistry.Registration("DELETE", "/fixture/d", null));
        }
    }
}
