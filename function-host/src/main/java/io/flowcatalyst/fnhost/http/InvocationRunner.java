package io.flowcatalyst.fnhost.http;

import io.flowcatalyst.fnhost.load.LoadedFunction;
import io.flowcatalyst.function.FunctionContext;
import io.flowcatalyst.function.Request;
import io.flowcatalyst.function.Result;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/// Runs one invocation on its own virtual thread with a deadline (spec
/// `function-host-listener.md` §2 step 9): `retain()`/`release()` wrap the
/// call ON THE WORKER THREAD ITSELF, not the HTTP handler's wait — a
/// function that ignores the interrupt past the deadline is still "in
/// flight" for [LoadedFunction#close]'s drain, exactly as long as it
/// actually keeps running (H7: the permit gauge shows the truth until the
/// worker returns, deliberately not at the moment the deadline fires).
public final class InvocationRunner {

    private InvocationRunner() {
    }

    /// `future` completes when the function returns, throws or is
    /// interrupted and observes it; `worker` is what a caller interrupts on
    /// a deadline. Attach a `whenComplete` to `future` to learn when it is
    /// finally safe to release whatever the caller acquired around this call.
    public record Invocation(CompletableFuture<Result> future, Thread worker) {
    }

    public static Invocation start(LoadedFunction fn, Request request, FunctionContext ctx) {
        Objects.requireNonNull(fn, "fn");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(ctx, "ctx");
        CompletableFuture<Result> future = new CompletableFuture<>();
        Thread worker = Thread.ofVirtual().unstarted(() -> {
            fn.retain();
            try {
                Result result = fn.invoke(request, ctx);
                future.complete(result);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            } finally {
                fn.release();
            }
        });
        worker.start();
        return new Invocation(future, worker);
    }
}
