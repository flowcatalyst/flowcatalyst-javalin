package io.flowcatalyst.fnhost.http;

import com.sun.management.HotSpotDiagnosticMXBean;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.nio.file.Files;
import java.nio.file.Path;

/// The live memory split `docker/jvm-opts.sh` fenced this process into
/// (docs/spec/jvm-memory.md §4), read from the running JVM itself rather
/// than re-derived from env vars — a mismatch between what the script
/// computed and what the JVM actually enforces would show up here. Feeds
/// `/ready`'s `memory` object (`FnObservability`) so an operator can see the
/// split without shelling into the container.
public final class FnMemorySnapshot {

    private FnMemorySnapshot() {
    }

    /// `limitBytes`/`metaspaceMaxBytes`/`directMaxBytes` are `null` — omitted
    /// from JSON, never `-1` — when that ceiling is unbounded or unknown;
    /// `heapMaxBytes` ([Runtime#maxMemory]) is always a real number.
    public record Info(Long limitBytes, long heapMaxBytes, Long metaspaceMaxBytes, Long directMaxBytes) {
    }

    public static Info capture() {
        return capture(resolveLimitFile());
    }

    /// @param limitFile the cgroup-limit-equivalent file to read, or `null`
    ///                   to omit `limitBytes` entirely — a test seam so a
    ///                   test can pin an exact value without touching real
    ///                   `/sys/fs/cgroup` files or process env vars.
    public static Info capture(Path limitFile) {
        long heapMax = Runtime.getRuntime().maxMemory();
        Long metaspaceMax = poolMaxBytes("Metaspace");
        Long directMax = directMaxBytes();
        Long limitBytes = readLimitBytes(limitFile);
        return new Info(limitBytes, heapMax, metaspaceMax, directMax);
    }

    /// [java.lang.management.MemoryUsage#getMax] is `-1` when the pool's
    /// maximum is undefined (unbounded) per its own javadoc — that becomes
    /// `null` here so the caller omits the field rather than ever printing a
    /// literal `-1`.
    private static Long poolMaxBytes(String poolName) {
        for (MemoryPoolMXBean bean : ManagementFactory.getMemoryPoolMXBeans()) {
            if (poolName.equals(bean.getName())) {
                long max = bean.getUsage().getMax();
                return max < 0 ? null : max;
            }
        }
        return null;
    }

    /// `com.sun.management` lives in the `jdk.management` module, which
    /// `function-host/Dockerfile`'s jlink step already adds (it depends on
    /// `server`'s own jlink module list, `jdk.management` included — see
    /// `JvmInfo`'s own comment on this in the `server` module). `0` is the
    /// JDK's "not explicitly set, computed lazily from the heap at first
    /// use" value, not a real cap, so it is treated the same as unbounded.
    private static Long directMaxBytes() {
        try {
            HotSpotDiagnosticMXBean bean = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
            if (bean == null) return null;
            long bytes = Long.parseLong(bean.getVMOption("MaxDirectMemorySize").getValue());
            return bytes > 0 ? bytes : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /// Same file-selection order as `docker/jvm-opts.sh`'s Rule 1:
    /// `FC_JVM_MEMORY_LIMIT_FILE` test seam, else cgroup v2, else cgroup v1.
    private static Path resolveLimitFile() {
        String override = System.getenv("FC_JVM_MEMORY_LIMIT_FILE");
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        Path v2 = Path.of("/sys/fs/cgroup/memory.max");
        if (Files.isRegularFile(v2)) {
            return v2;
        }
        Path v1 = Path.of("/sys/fs/cgroup/memory/memory.limit_in_bytes");
        if (Files.isRegularFile(v1)) {
            return v1;
        }
        return null;
    }

    /// Same acceptance rules as `docker/jvm-opts.sh`: a missing file, `max`,
    /// a non-numeric value, or the cgroup v1 "unlimited" sentinel (>= 2^60)
    /// all mean "no real limit", so `limitBytes` is omitted rather than
    /// reporting a value the JVM was never actually fenced to.
    private static Long readLimitBytes(Path file) {
        if (file == null) {
            return null;
        }
        String raw;
        try {
            raw = Files.readString(file).strip();
        } catch (IOException e) {
            return null;
        }
        if (raw.isEmpty() || "max".equals(raw)) {
            return null;
        }
        if (!raw.chars().allMatch(Character::isDigit)) {
            return null;
        }
        long value;
        try {
            value = Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return null;
        }
        if (value >= (1L << 60)) {
            return null;
        }
        return value;
    }
}
