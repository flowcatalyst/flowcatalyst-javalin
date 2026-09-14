package io.flowcatalyst.server;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// docs/spec/jvm-memory.md §2: the summary `Server.start` logs must actually
/// name a collector and a real heap ceiling, not just exist.
class JvmInfoTest {

    @Test
    void summaryNamesACollectorAndAPositiveHeap() {
        JvmInfo.Summary summary = JvmInfo.summary();

        assertThat(summary.collectors()).isNotEmpty();
        assertThat(summary.maxHeapMiB()).isPositive();
        assertThat(summary.processors()).isGreaterThanOrEqualTo(1);
    }
}
