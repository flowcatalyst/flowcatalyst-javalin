package io.flowcatalyst.example.hello;

import java.text.MessageFormat;
import java.util.ResourceBundle;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;

/// A [System.Logger] test double that captures every FULLY FORMATTED line —
/// `java.lang.System.Logger` only declares `getName`, `isLoggable` and the two
/// `log(Level, ResourceBundle, ...)` overloads as abstract; every other
/// overload (including the `Object...` varargs one [HelloFunction] actually
/// calls) is a default method that delegates to these, so implementing just
/// these four is enough to observe everything logged through the interface.
/// Formats with {@link MessageFormat}, exactly as `HostLogger`'s own class doc
/// (`docs/spec/function-context.md` §2) says a real host does — so a test
/// asserting a secret is absent from a log line is asserting it against the
/// same rendering a real function's logs would carry, not against the raw
/// (unformatted) template string.
final class CapturingLogger implements System.Logger {

    private final String name;
    private final List<String> lines = new CopyOnWriteArrayList<>();

    CapturingLogger(String name) {
        this.name = name;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public boolean isLoggable(Level level) {
        return true;
    }

    @Override
    public void log(Level level, ResourceBundle bundle, String msg, Throwable thrown) {
        lines.add(thrown == null ? msg : msg + " " + thrown);
    }

    @Override
    public void log(Level level, ResourceBundle bundle, String format, Object... params) {
        lines.add(params == null || params.length == 0 ? format : MessageFormat.format(format, params));
    }

    List<String> lines() {
        return lines;
    }
}
