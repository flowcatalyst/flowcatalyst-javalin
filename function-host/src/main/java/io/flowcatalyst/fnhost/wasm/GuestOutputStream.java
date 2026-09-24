package io.flowcatalyst.fnhost.wasm;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/// A guest's WASI stdout or stderr, as log lines on the version's own logger
/// (`docs/spec/function-wasm-runtime.md` §3: stdout at INFO, stderr at WARN)
/// — never the host process's own streams. One per plugin instance (an
/// instance runs one call at a time, so this needs no locking); a line ends
/// at `\n`, at [#MAX_LINE_BYTES], or at [#flush] (the host flushes after
/// every call so a trailing partial line is not held until the next one).
final class GuestOutputStream extends OutputStream {

    /// A line longer than this is emitted in pieces — a guest writing a
    /// megabyte without a newline must not grow the host's buffer without
    /// bound. A constant, never a knob.
    static final int MAX_LINE_BYTES = 8 * 1024;

    private final System.Logger target;
    private final System.Logger.Level level;
    private final ByteArrayOutputStream line = new ByteArrayOutputStream();

    GuestOutputStream(System.Logger target, System.Logger.Level level) {
        this.target = Objects.requireNonNull(target, "target");
        this.level = Objects.requireNonNull(level, "level");
    }

    @Override
    public void write(int b) {
        if (b == '\n') {
            emit();
            return;
        }
        line.write(b);
        if (line.size() >= MAX_LINE_BYTES) {
            emit();
        }
    }

    @Override
    public void write(byte[] b, int off, int len) {
        Objects.checkFromIndexSize(off, len, b.length);
        for (int i = off; i < off + len; i++) {
            write(b[i]);
        }
    }

    @Override
    public void flush() {
        if (line.size() > 0) {
            emit();
        }
    }

    private void emit() {
        String text = line.toString(StandardCharsets.UTF_8);
        line.reset();
        if (text.endsWith("\r")) {
            text = text.substring(0, text.length() - 1);
        }
        if (target.isLoggable(level)) {
            target.log(level, text);
        }
    }
}
