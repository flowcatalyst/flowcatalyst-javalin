package io.flowcatalyst.http.vertx;

import io.vertx.core.Context;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerRequest;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.ArrayDeque;

/// The request-side bridge for `Routes.putStreaming`
/// (`docs/spec/function-artifact-upload.md` §3): a back-pressured
/// [InputStream] over a Vert.x [HttpServerRequest], readable from the
/// handler's own virtual thread while every touch of `request` itself stays
/// on the event-loop [Context] — the same discipline [VertxExchange]'s
/// buffered response write uses.
///
/// [#attach] sets Vert.x's handlers and pauses the request in the SAME
/// event-loop tick, before admission ([VertxListener#dispatch]) — handlers
/// must be attached before pausing (Vert.x's own `ReadStream` contract);
/// attaching them lazily, on the first [#read] instead, raced Vert.x's
/// "already ended" bookkeeping for a small body that arrives in the same
/// read as the headers (`IllegalStateException: Request has already been
/// read`, found while pinning U1–U10). Pausing immediately still means no
/// byte is DELIVERED to [#onChunk] until [#read] calls [#ensureResumed] —
/// a handler that rejects the request on authorization/reach before ever
/// touching the body (spec §3's own ordering, U5) still never drains the
/// socket. Chunks queue up to [#HIGH_WATERMARK] buffers before the request
/// is paused again; the consumer resumes it once the queue drains to
/// [#LOW_WATERMARK] — bounded memory regardless of the artifact's size,
/// matching Java 25's virtual-thread-friendly `synchronized`/`wait` (no
/// carrier pinning, JEP 491).
final class VertxBodyInputStream extends InputStream {

    private static final int HIGH_WATERMARK = 8;
    private static final int LOW_WATERMARK = 2;

    private final Context vertxContext;
    private final HttpServerRequest request;

    /// Guards every field below; also the wait/notify monitor between the
    /// event-loop producer ([#onChunk]/[#onEnd]/[#onError]) and the virtual
    /// thread consumer ([#read]).
    private final Object lock = new Object();
    private final ArrayDeque<Buffer> queue = new ArrayDeque<>();
    /// Mirrors the request's real paused/resumed state — `true` from
    /// [#attach] (which pauses) until [#ensureResumed]'s first resume.
    private boolean paused = true;
    private boolean eof;
    private boolean closed;
    /// Set once, by [#close] — [VertxListener] reads it AFTER the chain has
    /// finished, to decide whether the connection needs closing (FIX 3;
    /// never from inside `close()` itself — see its own doc).
    private volatile boolean abandonedBeforeEof;
    private Throwable failure;
    private Buffer current;
    private int currentPos;

    /// Last time a chunk (or EOF) arrived — [VertxListener]'s stall deadline
    /// reads this from the loop's timer thread while [#onChunk]/[#onEnd]
    /// write it from the loop's request-handler thread; both are the SAME
    /// event-loop thread in Vert.x's single-event-loop model this listener
    /// uses, but `volatile` costs nothing and removes any doubt.
    private volatile long lastProgressNanos = System.nanoTime();

    private VertxBodyInputStream(Context vertxContext, HttpServerRequest request) {
        this.vertxContext = vertxContext;
        this.request = request;
    }

    /// The stall deadline's own clock (spec: a streaming exchange is timed
    /// out for going quiet, never for taking a long time in total).
    long lastProgressNanos() {
        return lastProgressNanos;
    }

    /// Event-loop only — called from [VertxListener#dispatch] before the
    /// request is ever queued for a worker.
    static VertxBodyInputStream attach(Context vertxContext, HttpServerRequest request) {
        var stream = new VertxBodyInputStream(vertxContext, request);
        request.handler(stream::onChunk);
        request.endHandler(v -> stream.onEnd());
        request.exceptionHandler(stream::onError);
        request.pause();
        return stream;
    }

    /// Resumes the request — once, on the first read. Always hops to the
    /// event-loop context; never called from it directly (this runs on the
    /// handler's own virtual thread).
    private void ensureResumed() {
        synchronized (lock) {
            if (!paused) {
                return;
            }
            paused = false;
        }
        vertxContext.runOnContext(v -> request.resume());
    }

    /// Event-loop thread only.
    private void onChunk(Buffer buf) {
        lastProgressNanos = System.nanoTime();
        synchronized (lock) {
            if (closed) {
                return;
            }
            queue.addLast(buf);
            lock.notifyAll();
            if (!paused && queue.size() >= HIGH_WATERMARK) {
                paused = true;
                request.pause();
            }
        }
    }

    private void onEnd() {
        // Progress too (not just chunks): EOF gives the handler one full stall
        // window from here to finish hash-compare + `store.put` — intentional,
        // spec's own note (FIX 1) — nothing "stalls" merely because the wire
        // has nothing left to send.
        lastProgressNanos = System.nanoTime();
        synchronized (lock) {
            eof = true;
            lock.notifyAll();
        }
    }

    private void onError(Throwable t) {
        synchronized (lock) {
            failure = t;
            lock.notifyAll();
        }
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        int n = read(one, 0, 1);
        return n == -1 ? -1 : one[0] & 0xFF;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (len == 0) {
            return 0;
        }
        ensureResumed();
        synchronized (lock) {
            while (true) {
                if (current != null && currentPos < current.length()) {
                    int n = Math.min(len, current.length() - currentPos);
                    current.getBytes(currentPos, currentPos + n, b, off);
                    currentPos += n;
                    return n;
                }
                if (!queue.isEmpty()) {
                    current = queue.pollFirst();
                    currentPos = 0;
                    if (paused && queue.size() <= LOW_WATERMARK) {
                        paused = false;
                        vertxContext.runOnContext(v -> request.resume());
                    }
                    continue;
                }
                if (failure != null) {
                    throw new IOException("reading the request body", failure);
                }
                if (eof) {
                    return -1;
                }
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("interrupted while reading the request body");
                }
            }
        }
    }

    /// Abandons the stream (a 413/422 path, or the deadline). Never touches
    /// the CONNECTION itself — FIX 3, revised after an earlier version
    /// (tag `Connection: close` here, or close the connection outright)
    /// proved unsafe both ways, empirically, while pinning this unit's own
    /// test:
    ///
    ///   - Closing the connection outright would, on this listener's shared
    ///     h2/h2c port, tear down every OTHER multiplexed stream on it too —
    ///     an abandoned upload on one request must never fail a sibling
    ///     request in flight on the same connection.
    ///   - Tagging `Connection: close` and trusting Vert.x to close the
    ///     connection once the response is flushed sounds safe but is NOT:
    ///     with request body bytes still unread on the same connection (the
    ///     very common case — abandonment usually means "most of a large
    ///     upload was never read"), the close races the OS socket's own
    ///     unread-data bookkeeping and the response can be lost to a TCP
    ///     reset before the client ever sees it — reproduced directly: the
    ///     handler's 413 was written and `end()` reported success, yet the
    ///     client's raw socket read timed out with zero bytes.
    ///
    /// So this method only resumes the request (draining and discarding
    /// whatever body remains — `onChunk` drops everything once `closed` is
    /// set) and records [#abandonedBeforeEof] for [VertxListener] to act on
    /// LATER, once it has positive confirmation the response was actually
    /// written — see `runChain`'s own doc for where that happens.
    @Override
    public void close() {
        boolean reachedEof;
        synchronized (lock) {
            reachedEof = eof;
            closed = true;
            queue.clear();
        }
        if (reachedEof) {
            return;
        }
        abandonedBeforeEof = true;
        vertxContext.runOnContext(v -> request.resume());
    }

    /// Whether this stream was closed before reaching EOF — FIX 3: the
    /// signal [VertxListener] uses, after the response has been confirmed
    /// written, to decide whether the connection needs closing at all
    /// (HTTP/1.x only; never for HTTP/2 — see [#close]'s own doc).
    boolean abandonedBeforeEof() {
        return abandonedBeforeEof;
    }
}
