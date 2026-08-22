package io.flowcatalyst.sdk.tsid;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TsidTest {

    @Test
    void rawIdsAreThirteenCrockfordCharacters() {
        String id = Tsid.generate();
        assertThat(id).hasSize(13).matches("[0-9A-HJKMNP-TV-Z]{13}");
        assertThat(Tsid.isValid(id)).isTrue();
        assertThat(Tsid.isValid("ILOU000000000")).isFalse();
        assertThat(Tsid.isValid("TOOSHORT")).isFalse();
    }

    @Test
    void typedIdsCarryThePrefix() {
        String id = Tsid.generateWithPrefix("aud");
        assertThat(id).hasSize(17).startsWith("aud_");
        assertThat(Tsid.toLong(id)).isPresent();
        assertThatThrownBy(() -> Tsid.generateWithPrefix("a_b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Tsid.generateWithPrefix("")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void idsAreUniqueAndTimeOrderedWithinAProcess() {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 20_000; i++) ids.add(Tsid.generate());
        assertThat(new HashSet<>(ids)).hasSize(ids.size());
        // Only the timestamp + sequence are monotonic; the 10 random bits sit between them,
        // so compare on the decoded (ms, seq) instead of the raw string.
        long previous = -1;
        for (String id : ids) {
            long v = Tsid.toLong(id).orElseThrow();
            long msSeq = ((v >>> 22) << 12) | (v & 0xFFF);
            assertThat(msSeq).isGreaterThan(previous);
            previous = msSeq;
        }
    }

    @Test
    void longRoundTrip() {
        String id = Tsid.generate();
        long v = Tsid.toLong(id).orElseThrow();
        assertThat(Tsid.fromLong(v)).isEqualTo(id);
        assertThat(Tsid.toLong("not-a-tsid")).isEmpty();
        assertThat(Tsid.toLong(null)).isEmpty();
    }

    @Test
    void timestampBitsAreMillisecondsSinceUnixEpoch() {
        long before = System.currentTimeMillis();
        long v = Tsid.toLong(Tsid.generate()).orElseThrow();
        long ms = v >>> 22;
        assertThat(ms).isBetween(before, System.currentTimeMillis() + 5);
    }
}
