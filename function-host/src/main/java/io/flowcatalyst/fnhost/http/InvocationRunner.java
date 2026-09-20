package io.flowcatalyst.fnhost.http;

import io.flowcatalyst.fnhost.context.InvocationDeadline;
import io.flowcatalyst.fnhost.context.InvocationEmitDefaults;
import io.flowcatalyst.fnhost.load.LoadedFunction;
import io.flowcatalyst.function.Caller;
import io.flowcatalyst.function.FunctionContext;
import io.flowcatalyst.function.Request;
import io.flowcatalyst.function.Result;
import io.flowcatalyst.function.Webhook;
import io.flowcatalyst.function.WebhookFormatException;
import io.flowcatalyst.server.Logging;
import org.slf4j.MDC;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/// Runs one invocation on its own virtual thread with a deadline (spec
/// `function-host-listener.md` §2 step 9): `retain()`/`release()` wrap the
/// call ON THE WORKER THREAD ITSELF, not the HTTP handler's wait — a
/// function that ignores the interrupt past the deadline is still "in
/// flight" for [LoadedFunction#close]'s drain, exactly as long as it
/// actually keeps running (H7: the permit gauge shows the truth until the
/// worker returns, deliberately not at the moment the deadline fires).
///
/// D4b (`docs/spec/function-context.md` §2, X6): the MDC keys (`function`,
/// `version`, `execution_id`, `correlation_id`) are set HERE, on the worker
/// thread that actually calls [LoadedFunction#invoke] — the thread whose
/// SLF4J logger calls (including every one [FunctionContext#logger()]'s
/// `fn.<address>` logger makes) need them. Cleared in this thread's own
/// `finally`, independent of whatever the request thread does with its own
/// copy (`FnHttpServer` sets the same keys on ITS thread too, for the host's
/// own log lines during the call — the two are deliberately redundant, not
/// one relying on the other's thread-local propagating).
///
/// [InvocationDeadline#CURRENT] is bound around the call into the function
/// (never a mutable field on the shared [FunctionContext] — two concurrent
/// invocations of the same loaded version must never race on it).
public final class InvocationRunner {

    private InvocationRunner() {
    }

    /// `future` completes when the function returns, throws or is
    /// interrupted and observes it; `worker` is what a caller interrupts on
    /// a deadline. Attach a `whenComplete` to `future` to learn when it is
    /// finally safe to release whatever the caller acquired around this call.
    public record Invocation(CompletableFuture<Result> future, Thread worker) {
    }

    /// @param deadline when this invocation's [InvocationDeadline] expires —
    ///                 bound as a [ScopedValue] around the call, so
    ///                 [io.flowcatalyst.fnhost.context.AllowlistHttpCaller#send]
    ///                 can cap an outbound call's timeout to the time left
    ///                 without the context carrying per-invocation state
    public static Invocation start(LoadedFunction fn, Request request, FunctionContext ctx, Instant deadline) {
        Objects.requireNonNull(fn, "fn");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(ctx, "ctx");
        Objects.requireNonNull(deadline, "deadline");
        CompletableFuture<Result> future = new CompletableFuture<>();
        Thread worker = Thread.ofVirtual().unstarted(() -> {
            fn.retain();
            Map<String, String> mdc = mdcFor(request);
            mdc.forEach(MDC::put);
            try {
                Result result = ScopedValue.where(InvocationDeadline.CURRENT, deadline)
                        .where(InvocationEmitDefaults.CURRENT, emitDefaults(request))
                        .call(() -> fn.invoke(request, ctx));
                future.complete(result);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            } finally {
                mdc.keySet().forEach(MDC::remove);
                fn.release();
            }
        });
        worker.start();
        return new Invocation(future, worker);
    }

    private static Map<String, String> mdcFor(Request request) {
        Map<String, String> mdc = new LinkedHashMap<>();
        mdc.put(Logging.MdcKeys.FUNCTION, request.address().render());
        mdc.put(Logging.MdcKeys.VERSION, String.valueOf(request.version()));
        mdc.put(Logging.MdcKeys.EXECUTION_ID, request.invocationId());
        request.header("X-Correlation-Id").ifPresent(v -> mdc.put(Logging.MdcKeys.CORRELATION_ID, v));
        return mdc;
    }

    /// Spec `function-context.md` §3. `correlationId`: the inbound event's own
    /// `correlationId` when the delivery carries one — correlation exists to
    /// tie a whole flow together, and an event emitted while handling a
    /// delivery belongs to that delivery's flow — else the `X-Correlation-Id`
    /// header, else this invocation's id. `causationId`: the inbound event's
    /// id. Both inbound values exist only when the request arrived as a
    /// verified webhook delivery ([Caller.Platform]; `auth: none`/`platform`
    /// endpoints never get them) AND its body parses as an event envelope (a
    /// scheduled-job firing parses as [io.flowcatalyst.function.Schedule]
    /// instead, so it offers neither).
    static InvocationEmitDefaults.Defaults emitDefaults(Request request) {
        String correlationId = request.header("X-Correlation-Id").orElse(request.invocationId());
        String causationId = null;
        if (request.caller() instanceof Caller.Platform) {
            try {
                var inbound = Webhook.event(request);
                causationId = inbound.id();
                if (inbound.correlationId() != null && !inbound.correlationId().isBlank()) {
                    correlationId = inbound.correlationId();
                }
            } catch (WebhookFormatException e) {
                // Not a parseable event envelope (e.g. a scheduled-job firing) — no default.
                causationId = null;
            }
        }
        return new InvocationEmitDefaults.Defaults(correlationId, causationId);
    }
}
