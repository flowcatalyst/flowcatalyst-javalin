package io.flowcatalyst.fnhost.http;

/// Trivial driver for [FnMemorySnapshotTest]'s forked-JVM assertions: prints
/// `FnMemorySnapshot.capture(null)`'s three JVM-derived fields as `key=value`
/// lines so the parent test can fork with explicit `-XX:MaxMetaspaceSize=`/
/// `-XX:MaxDirectMemorySize=` flags (which cannot be applied to the already-running
/// test JVM) and observe what [FnMemorySnapshot] reports under them. `null`
/// for the limit file: this driver is about the JVM-pool/VM-option fields
/// only, not the cgroup-file field (covered separately, in-process, by
/// [FnMemorySnapshotTest]'s `capture(Path)` tests).
public final class FnMemorySnapshotForkDriver {

    private FnMemorySnapshotForkDriver() {
    }

    public static void main(String[] args) {
        FnMemorySnapshot.Info info = FnMemorySnapshot.capture(null);
        System.out.println("heapMaxBytes=" + info.heapMaxBytes());
        System.out.println("metaspaceMaxBytes=" + info.metaspaceMaxBytes());
        System.out.println("directMaxBytes=" + info.directMaxBytes());
    }
}
