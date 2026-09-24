package io.flowcatalyst.fnhost.wasm;

import run.endive.log.Logger;

import java.util.Objects;

/// Endive's [Logger] over one version's `System.Logger` — the function's own
/// `fn.<address>` SLF4J logger ([io.flowcatalyst.fnhost.context.HostLogger])
/// in production (`docs/spec/function-wasm-runtime.md` §1, §4). Every line
/// the runtime or the guest produces for this version — Endive's own
/// diagnostics, WASI's, the guest's Extism `log_*` calls — lands there, never
/// on stdout/stderr (Endive's default `SystemLogger` writes to both).
final class EndiveLogger implements Logger {

    private final System.Logger target;

    EndiveLogger(System.Logger target) {
        this.target = Objects.requireNonNull(target, "target");
    }

    @Override
    public void log(Level level, String msg, Throwable throwable) {
        System.Logger.Level mapped = map(level);
        if (mapped == System.Logger.Level.OFF || !target.isLoggable(mapped)) {
            return;
        }
        if (throwable != null) {
            target.log(mapped, msg, throwable);
        } else {
            target.log(mapped, msg);
        }
    }

    @Override
    public boolean isLoggable(Level level) {
        System.Logger.Level mapped = map(level);
        return mapped != System.Logger.Level.OFF && target.isLoggable(mapped);
    }

    private static System.Logger.Level map(Level level) {
        return switch (level) {
            case ALL, TRACE -> System.Logger.Level.TRACE;
            case DEBUG -> System.Logger.Level.DEBUG;
            case INFO -> System.Logger.Level.INFO;
            case WARNING -> System.Logger.Level.WARNING;
            case ERROR -> System.Logger.Level.ERROR;
            case OFF -> System.Logger.Level.OFF;
        };
    }
}
