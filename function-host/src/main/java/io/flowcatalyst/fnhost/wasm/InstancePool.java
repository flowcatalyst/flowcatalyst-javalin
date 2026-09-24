package io.flowcatalyst.fnhost.wasm;

import io.flowcatalyst.sdk.result.Result;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/// The plugin instances of one Wasm version (`docs/spec/function-wasm-runtime.md`
/// §3). An instance is not thread-safe, so each call borrows one exclusively;
/// instances are created lazily, up to `capacity` (= the version's
/// `limits.maxConcurrency`) alive at once.
///
/// **A borrow never waits.** The listener's per-function permits already
/// bound concurrent calls to `maxConcurrency`, and a permit is held until the
/// call's worker has returned its instance (or discarded it) — so with every
/// instance busy there is no caller left to want one. If a borrow nevertheless
/// finds the pool full, that is a bug upstream of the pool, and it is refused
/// ([Exhausted]) rather than queued.
///
/// An instance whose call failed — an error code, a trap, an interrupt at the
/// deadline, the memory cap, output of the wrong shape — is [#discard]ed, never
/// returned: its memory and globals are in whatever state the failure left
/// them. The next call gets a fresh one.
final class InstancePool implements AutoCloseable {

    /// Why [#borrow] handed out no instance.
    sealed interface Refusal permits Exhausted, Closed, InstantiationFailed {
    }

    /// Every one of `capacity` instances is in use.
    record Exhausted(int capacity) implements Refusal {
    }

    /// The version has been unloaded.
    record Closed() implements Refusal {
    }

    /// A new instance could not be created (a link error, a trap in the
    /// module's start function, …).
    record InstantiationFailed(RuntimeException cause) implements Refusal {
        InstantiationFailed {
            Objects.requireNonNull(cause, "cause");
        }
    }

    private final int capacity;
    private final Supplier<PluginInstance> factory;
    private final ReentrantLock lock = new ReentrantLock();
    private final ArrayDeque<PluginInstance> idle = new ArrayDeque<>();
    /// Instances created and not yet discarded — idle plus borrowed. Guarded by [#lock].
    private int live;
    private boolean closed;

    InstancePool(int capacity, Supplier<PluginInstance> factory) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0, was " + capacity);
        }
        this.capacity = capacity;
        this.factory = Objects.requireNonNull(factory, "factory");
    }

    /// An idle instance, else a new one while fewer than `capacity` are alive.
    /// Instantiation runs outside the lock, with the slot already reserved.
    Result<PluginInstance, Refusal> borrow() {
        lock.lock();
        try {
            if (closed) {
                return Result.err(new Closed());
            }
            PluginInstance reused = idle.pollFirst();
            if (reused != null) {
                return Result.ok(reused);
            }
            if (live >= capacity) {
                return Result.err(new Exhausted(capacity));
            }
            live++;
        } finally {
            lock.unlock();
        }
        try {
            return Result.ok(factory.get());
        } catch (RuntimeException e) {
            releaseSlot();
            return Result.err(new InstantiationFailed(e));
        } catch (Error e) {
            releaseSlot();
            throw e;
        }
    }

    /// Returns a healthy instance after a successful call. After [#close] it
    /// is dropped instead.
    void giveBack(PluginInstance instance) {
        Objects.requireNonNull(instance, "instance");
        lock.lock();
        try {
            if (closed) {
                live--;
            } else {
                idle.addFirst(instance);
            }
        } finally {
            lock.unlock();
        }
    }

    /// Drops an instance whose call failed; its slot is free again.
    void discard(PluginInstance instance) {
        Objects.requireNonNull(instance, "instance");
        releaseSlot();
    }

    /// Releases every idle instance and refuses every later borrow; an
    /// instance still borrowed is dropped when its call hands it back.
    @Override
    public void close() {
        lock.lock();
        try {
            closed = true;
            live -= idle.size();
            idle.clear();
        } finally {
            lock.unlock();
        }
    }

    /// Instances alive (idle + borrowed) — a diagnostic.
    int live() {
        lock.lock();
        try {
            return live;
        } finally {
            lock.unlock();
        }
    }

    /// Instances idle in the pool — a diagnostic.
    int idle() {
        lock.lock();
        try {
            return idle.size();
        } finally {
            lock.unlock();
        }
    }

    private void releaseSlot() {
        lock.lock();
        try {
            live--;
        } finally {
            lock.unlock();
        }
    }
}
