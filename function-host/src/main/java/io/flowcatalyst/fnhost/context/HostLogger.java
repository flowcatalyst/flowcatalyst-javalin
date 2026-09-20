package io.flowcatalyst.fnhost.context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.MessageFormat;
import java.util.Arrays;
import java.util.MissingResourceException;
import java.util.Objects;
import java.util.ResourceBundle;

/// A [System.Logger] backed by the SLF4J logger `fn.<address>` (spec
/// `function-context.md` §2). `System.Logger` has exactly four abstract
/// methods — `getName`, `isLoggable`, and the two `ResourceBundle`-taking
/// `log` overloads — every other overload (`log(Level, String)`,
/// `log(Level, String, Object...)`, the `Supplier` forms, …) is a JDK
/// default method that funnels into these, so implementing only these four
/// covers the whole interface.
///
/// Level mapping: `TRACE`/`DEBUG`/`INFO`/`WARNING`/`ERROR` map to the
/// matching SLF4J level; `ALL` logs at the lowest SLF4J level (`trace`) —
/// never filtered out, matching what `isLoggable(ALL)` promises; `OFF` is
/// never loggable, so [#log] never even reaches the emit step for it.
public final class HostLogger implements System.Logger {

    private final Logger slf4j;

    public HostLogger(String address) {
        this(LoggerFactory.getLogger("fn." + Objects.requireNonNull(address, "address")));
    }

    /// Test seam: log against an arbitrary SLF4J logger (a fixed name, so a
    /// test's `ListAppender` can be attached to it directly).
    HostLogger(Logger slf4j) {
        this.slf4j = Objects.requireNonNull(slf4j, "slf4j");
    }

    @Override
    public String getName() {
        return slf4j.getName();
    }

    @Override
    public boolean isLoggable(Level level) {
        return switch (level) {
            case ALL -> true;
            case TRACE -> slf4j.isTraceEnabled();
            case DEBUG -> slf4j.isDebugEnabled();
            case INFO -> slf4j.isInfoEnabled();
            case WARNING -> slf4j.isWarnEnabled();
            case ERROR -> slf4j.isErrorEnabled();
            case OFF -> false;
        };
    }

    @Override
    public void log(Level level, ResourceBundle bundle, String msg, Throwable thrown) {
        if (!isLoggable(level)) {
            return;
        }
        emit(level, resolve(bundle, msg), thrown);
    }

    @Override
    public void log(Level level, ResourceBundle bundle, String format, Object... params) {
        if (!isLoggable(level)) {
            return;
        }
        String resolved = resolve(bundle, format);
        Throwable cause = null;
        Object[] args = params;
        // System.Logger's own contract for this overload: a trailing Throwable
        // is the record's cause, not a format argument (spec: "a throwable goes
        // to setCause").
        if (params != null && params.length > 0 && params[params.length - 1] instanceof Throwable t) {
            cause = t;
            args = Arrays.copyOf(params, params.length - 1);
        }
        String formatted = (resolved != null && args != null && args.length > 0)
                ? MessageFormat.format(resolved, args)
                : resolved;
        emit(level, formatted, cause);
    }

    private static String resolve(ResourceBundle bundle, String key) {
        if (bundle == null || key == null) {
            return key;
        }
        try {
            return bundle.getString(key);
        } catch (MissingResourceException e) {
            return key;
        }
    }

    private void emit(Level level, String message, Throwable cause) {
        switch (level) {
            case TRACE, ALL -> log(slf4j::trace, slf4j::trace, message, cause);
            case DEBUG -> log(slf4j::debug, slf4j::debug, message, cause);
            case INFO -> log(slf4j::info, slf4j::info, message, cause);
            case WARNING -> log(slf4j::warn, slf4j::warn, message, cause);
            case ERROR -> log(slf4j::error, slf4j::error, message, cause);
            case OFF -> {
                // unreachable: isLoggable(OFF) is always false, so callers never get here.
            }
        }
    }

    private interface Plain {
        void log(String message);
    }

    private interface WithCause {
        void log(String message, Throwable cause);
    }

    private static void log(Plain plain, WithCause withCause, String message, Throwable cause) {
        if (cause != null) {
            withCause.log(message, cause);
        } else {
            plain.log(message);
        }
    }
}
