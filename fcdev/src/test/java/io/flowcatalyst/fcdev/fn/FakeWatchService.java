package io.flowcatalyst.fcdev.fn;

import java.nio.file.ClosedWatchServiceException;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.Watchable;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/// A fully in-process [WatchService]: lets [WatchCommandTest] drive
/// [WatchCommand#awaitDebouncedChange] with EXACT control over when raw
/// events arrive, independent of any real OS watcher's granularity — on
/// macOS the JDK's default `WatchService` is polling-based with multi-second
/// granularity, which would otherwise coalesce two rapid writes into one
/// `take()` regardless of whether {@link WatchCommand}'s OWN debounce logic
/// does anything at all (a real risk: that would make "no debounce" a
/// mutant this test cannot see). [#signal()] still blocks the calling
/// thread for real time inside [#poll(long, TimeUnit)] up to the caller's
/// own timeout — a genuinely bounded wait, never unbounded.
final class FakeWatchService implements WatchService {

    private final BlockingQueue<WatchKey> queue = new LinkedBlockingQueue<>();
    private volatile boolean closed;

    /// One fake key, reused for every signal — a real `WatchService` also
    /// hands back the SAME [WatchKey] object per registered path.
    static final WatchKey KEY = new WatchKey() {
        @Override
        public boolean isValid() {
            return true;
        }

        @Override
        public List<WatchEvent<?>> pollEvents() {
            return List.of();
        }

        @Override
        public boolean reset() {
            return true;
        }

        @Override
        public void cancel() {
        }

        @Override
        public Watchable watchable() {
            return null;
        }
    };

    /// Enqueues one raw event — the test's stand-in for a file write.
    void signal() {
        queue.offer(KEY);
    }

    @Override
    public void close() {
        closed = true;
    }

    @Override
    public WatchKey poll() {
        return queue.poll();
    }

    @Override
    public WatchKey poll(long timeout, TimeUnit unit) throws InterruptedException {
        if (closed) {
            throw new ClosedWatchServiceException();
        }
        return queue.poll(timeout, unit);
    }

    @Override
    public WatchKey take() throws InterruptedException {
        if (closed) {
            throw new ClosedWatchServiceException();
        }
        return queue.take();
    }
}
