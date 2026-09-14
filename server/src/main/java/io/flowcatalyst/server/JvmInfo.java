package io.flowcatalyst.server;

import com.sun.management.HotSpotDiagnosticMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;

/// A one-line snapshot of the JVM's memory ergonomics, logged once at boot by
/// `Server.start` before the listeners bind (docs/spec/jvm-memory.md §2) so
/// the chosen collector and the heap/direct-memory ceilings that
/// `docker/jvm-opts.sh` fenced are visible in the task's log rather than
/// inferred from the container size.
///
/// `com.sun.management` lives in the `jdk.management` module, which the
/// Dockerfile's jlink step already adds (`--add-modules … jdk.management …`)
/// and which unconditionally exports that package, so it resolves in the
/// runtime image without any extra module wiring.
public final class JvmInfo {

    private JvmInfo() {}

    public record Summary(List<String> collectors, long maxHeapMiB, long maxDirectMiB, int processors) {}

    public static Summary summary() {
        List<String> collectors = ManagementFactory.getGarbageCollectorMXBeans().stream()
                .map(GarbageCollectorMXBean::getName)
                .toList();
        long maxHeapMiB = Runtime.getRuntime().maxMemory() / (1024 * 1024);
        long maxDirectMiB = maxDirectMiBFromVmOption();
        int processors = Runtime.getRuntime().availableProcessors();
        return new Summary(collectors, maxHeapMiB, maxDirectMiB, processors);
    }

    /// `-1` when the HotSpot diagnostic bean isn't available at all (e.g. a
    /// non-HotSpot JVM) rather than throwing; `0` is a real answer meaning the
    /// JDK default applies — MaxDirectMemorySize defaults to the max heap size.
    private static long maxDirectMiBFromVmOption() {
        try {
            HotSpotDiagnosticMXBean bean = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
            if (bean == null) return -1;
            long bytes = Long.parseLong(bean.getVMOption("MaxDirectMemorySize").getValue());
            return bytes / (1024 * 1024);
        } catch (RuntimeException e) {
            return -1;
        }
    }
}
