package io.flowcatalyst.fnhost.wasm;

import io.flowcatalyst.fnhost.wasm.InstancePool.Closed;
import io.flowcatalyst.fnhost.wasm.InstancePool.Exhausted;
import io.flowcatalyst.fnhost.wasm.InstancePool.InstantiationFailed;
import io.flowcatalyst.fnhost.wasm.InstancePool.Refusal;
import io.flowcatalyst.fnhost.wasm.WasmAbi.GuestReply;
import io.flowcatalyst.fnhost.wasm.WasmAbi.Malformed;
import io.flowcatalyst.function.Function;
import io.flowcatalyst.function.FunctionContext;
import io.flowcatalyst.function.Request;
import io.flowcatalyst.function.Result;
import io.flowcatalyst.platform.function.FunctionAddress;
import io.flowcatalyst.sdk.result.Result.Err;
import io.flowcatalyst.sdk.result.Result.Ok;
import org.extism.sdk.chicory.ExtismFunctionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import run.endive.runtime.WasmInterruptedException;

import java.util.Objects;
import java.util.Set;

/// A Wasm version as a [Function] (`docs/spec/function-wasm-runtime.md` §3),
/// so the registry, the listener and the invocation path treat it exactly as
/// they treat a JVM function.
///
/// - [#init] binds the version's [FunctionContext] for the host functions and
///   instantiates nothing — instances are created lazily by the first calls.
/// - [#handle] borrows an instance, calls the manifest's export with the
///   request JSON ([WasmAbi]) and turns the reply into a [Result]. An error
///   code, a trap, the memory cap, or a reply of the wrong shape is a 500
///   ([Result#fail], a fixed reason — the guest's own message goes only to
///   the host's WARN) and the instance is discarded; never a host exception.
/// - The deadline: the listener interrupts the invocation's thread and Endive
///   stops the guest at once (`WasmInterruptedException`); the instance is
///   discarded and [#handle] ends with [InterruptedException] — the
///   interrupt is never swallowed, so the worker returns and the permit goes
///   back.
/// - [#stop] closes the pool.
public final class WasmFunction implements Function {

    private static final Logger LOG = LoggerFactory.getLogger(WasmFunction.class);

    /// The reason every failed Wasm call answers with — never the guest's own
    /// message, which may say more than the caller should see.
    static final String FAILURE_REASON = "the function failed";

    private final CompiledWasm compiled;
    private final String export;
    private final int maxConcurrency;
    private final Set<String> declaredConfig;
    private final Set<String> declaredSecrets;
    private final FunctionAddress address;
    private final int version;
    private volatile InstancePool pool;

    /// @param export          the manifest's `entrypoint` — already checked to be a
    ///                        function export of the module
    /// @param maxConcurrency  `limits.maxConcurrency` — the pool's capacity
    /// @param declaredConfig  `manifest.config`: the only keys `config_get` answers
    /// @param declaredSecrets `manifest.secrets`: the only keys `fc_secret_get` answers
    public WasmFunction(CompiledWasm compiled, String export, int maxConcurrency, Set<String> declaredConfig,
                        Set<String> declaredSecrets, FunctionAddress address, int version) {
        this.compiled = Objects.requireNonNull(compiled, "compiled");
        this.export = Objects.requireNonNull(export, "export");
        this.maxConcurrency = maxConcurrency;
        this.declaredConfig = Set.copyOf(declaredConfig);
        this.declaredSecrets = Set.copyOf(declaredSecrets);
        this.address = Objects.requireNonNull(address, "address");
        this.version = version;
    }

    @Override
    public void init(FunctionContext ctx) {
        Objects.requireNonNull(ctx, "ctx");
        CompiledWasm.Bindings bindings = new CompiledWasm.Bindings(ctx.logger(), ctx.config(), declaredConfig,
                ctx.secrets(), declaredSecrets, ctx.http(), ctx.events(), ctx.clock());
        pool = new InstancePool(maxConcurrency, () -> compiled.instantiate(bindings));
    }

    @Override
    public Result handle(Request in, FunctionContext ctx) throws InterruptedException {
        InstancePool current = pool;
        if (current == null) {
            throw new IllegalStateException("init() has not run for " + address.render() + "@" + version);
        }
        byte[] input = WasmAbi.encode(in);
        PluginInstance instance;
        switch (current.borrow()) {
            case Ok<PluginInstance, Refusal>(PluginInstance borrowed) -> instance = borrowed;
            case Err<PluginInstance, Refusal>(Refusal refusal) -> {
                if (refusal instanceof InstantiationFailed(RuntimeException cause) && interrupted(cause)) {
                    throw stopped(cause); // the deadline arrived while the instance was being built
                }
                return refused(refusal);
            }
        }

        boolean healthy = false;
        try {
            byte[] output = instance.call(export, input);
            return switch (WasmAbi.decode(output)) {
                case Ok<GuestReply, Malformed>(GuestReply reply) -> {
                    healthy = true;
                    yield Result.http(reply.status(), reply.headers(), reply.body());
                }
                case Err<GuestReply, Malformed>(Malformed malformed) -> {
                    warn("malformed_output", malformed.detail(), null);
                    yield Result.fail(FAILURE_REASON);
                }
            };
        } catch (RuntimeException e) {
            if (interrupted(e)) {
                throw stopped(e);
            }
            if (e instanceof ExtismFunctionException guestError) {
                warn("guest_error", String.valueOf(guestError.getError()), null);
            } else {
                warn("trap", String.valueOf(e.getMessage()), e);
            }
            return Result.fail(FAILURE_REASON);
        } finally {
            instance.flushOutput();
            if (healthy) {
                current.giveBack(instance);
            } else {
                current.discard(instance);
            }
        }
    }

    private Result refused(Refusal refusal) {
        switch (refusal) {
            case Closed ignored -> throw new IllegalStateException(
                    "Wasm version " + address.render() + "@" + version + " is unloaded");
            case Exhausted(int capacity) -> warn("pool_exhausted",
                    "every one of " + capacity + " instances is busy — the permits should have prevented this",
                    null);
            case InstantiationFailed(RuntimeException cause) ->
                    warn("instantiation_failed", String.valueOf(cause.getMessage()), cause);
        }
        return Result.fail(FAILURE_REASON);
    }

    private static InterruptedException stopped(RuntimeException cause) {
        InterruptedException stop = new InterruptedException("the guest was stopped at its deadline");
        stop.initCause(cause);
        return stop;
    }

    @Override
    public void stop() {
        InstancePool current = pool;
        if (current != null) {
            current.close();
        }
    }

    /// Instances alive (idle + borrowed) — a diagnostic; `0` before [#init].
    public int liveInstances() {
        InstancePool current = pool;
        return current == null ? 0 : current.live();
    }

    /// Instances idle in the pool — a diagnostic; `0` before [#init].
    public int idleInstances() {
        InstancePool current = pool;
        return current == null ? 0 : current.idle();
    }

    /// Endive signals a deadline stop as [WasmInterruptedException], possibly
    /// wrapped by a host function it propagated through.
    private static boolean interrupted(Throwable t) {
        Throwable current = t;
        for (int hop = 0; current != null && hop < 8; hop++) {
            if (current instanceof WasmInterruptedException || current instanceof InterruptedException) {
                return true;
            }
            current = current.getCause();
        }
        return Thread.currentThread().isInterrupted();
    }

    private void warn(String reason, String detail, Throwable cause) {
        var event = LOG.atWarn().setMessage("wasm function call failed")
                .addKeyValue("address", address.render())
                .addKeyValue("version", version)
                .addKeyValue("reason", reason)
                .addKeyValue("detail", truncate(detail));
        if (cause != null) {
            event = event.setCause(cause);
        }
        event.log();
    }

    private static String truncate(String detail) {
        return detail.length() <= 1024 ? detail : detail.substring(0, 1024) + "…";
    }
}
