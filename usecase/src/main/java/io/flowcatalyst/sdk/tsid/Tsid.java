package io.flowcatalyst.sdk.tsid;

import java.util.Arrays;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/// Crockford Base32 TSID primitives, wire-compatible with the platform's Go
/// generator (`pkg/fcsdk/tsid`):
///
/// ```
/// bits 63..22  timestamp (42 bits, milliseconds since the Unix epoch)
/// bits 21..12  random    (10 bits)
/// bits 11..0   sequence  (12 bits)
/// ```
///
/// rendered as exactly 13 Crockford Base32 characters (`0-9 A-H J-K M-N P-T V-Z`).
/// Typed ids add a short lowercase prefix: `{prefix}_{raw}` (e.g. `aud_0HZXEQ5Y8JY5Z`).
///
/// Uniqueness within one process is structural: the last issued
/// (millisecond, sequence) pair is advanced with a CAS, so two ids can never
/// share both. A fresh millisecond restarts the sequence at a random offset;
/// exhausting 4096 ids inside one millisecond borrows the next millisecond
/// rather than reusing a sequence value; a wall-clock step backwards cannot
/// cause reuse because the state only moves forward. The 10 random bits only
/// have to defend against *other* processes minting in the same millisecond.
public final class Tsid {

    private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    private static final int LENGTH = 13;
    private static final long MS_MASK = 0x3FF_FFFF_FFFFL; // 42 bits
    private static final int SEQ_BITS = 12;
    private static final int SEQ_MASK = 0xFFF;
    private static final int RANDOM_MASK = 0x3FF;

    /// Bits 63..12 = last issued millisecond, bits 11..0 = last issued sequence.
    private static final AtomicLong STATE = new AtomicLong();

    private static final byte[] DECODE = buildDecodeTable();

    private Tsid() {}

    /// A raw 13-character TSID with no prefix — event ids, execution ids,
    /// outbox row ids, and other non-entity contexts.
    public static String generate() {
        var ms = nextMsSeq();
        long random = ThreadLocalRandom.current().nextInt(RANDOM_MASK + 1);
        long value = ((ms.ms() & MS_MASK) << 22) | (random << SEQ_BITS) | ms.seq();
        return encode(value);
    }

    /// A typed TSID `{prefix}_{raw}`. The prefix must be non-empty and contain
    /// no underscore; the platform uses fixed three-letter prefixes per entity.
    public static String generateWithPrefix(String prefix) {
        if (prefix == null || prefix.isEmpty() || prefix.indexOf('_') >= 0) {
            throw new IllegalArgumentException("TSID prefix must be non-empty and contain no underscore");
        }
        return prefix + "_" + generate();
    }

    /// Numeric form of a typed or raw TSID; empty when the input is not a
    /// valid 13-character Crockford Base32 string.
    public static OptionalLong toLong(String tsid) {
        if (tsid == null) return OptionalLong.empty();
        String raw = tsid;
        if (tsid.length() > 14 && tsid.indexOf('_') >= 0) {
            raw = tsid.substring(tsid.indexOf('_') + 1);
        }
        return decode(raw);
    }

    /// Raw string form (no prefix) of a numeric TSID.
    public static String fromLong(long value) {
        return encode(value);
    }

    /// True when the string is a well-formed raw (unprefixed) TSID.
    public static boolean isValid(String tsid) {
        return tsid != null && decode(tsid).isPresent();
    }

    private record MsSeq(long ms, long seq) {}

    private static MsSeq nextMsSeq() {
        while (true) {
            long now = System.currentTimeMillis();
            long old = STATE.get();
            long lastMs = old >>> SEQ_BITS;
            long lastSeq = old & SEQ_MASK;
            long ms;
            long seq;
            if (now > lastMs) {
                ms = now;
                seq = randomSeq();
            } else if (lastSeq < SEQ_MASK) {
                ms = lastMs;
                seq = lastSeq + 1;
            } else {
                ms = lastMs + 1;
                seq = randomSeq();
            }
            if (STATE.compareAndSet(old, (ms << SEQ_BITS) | seq)) {
                return new MsSeq(ms, seq);
            }
        }
    }

    private static long randomSeq() {
        return ThreadLocalRandom.current().nextInt(SEQ_MASK + 1);
    }

    private static String encode(long value) {
        char[] out = new char[LENGTH];
        long v = value;
        for (int i = LENGTH - 1; i >= 0; i--) {
            out[i] = ALPHABET.charAt((int) (v & 0x1F));
            v >>>= 5;
        }
        return new String(out);
    }

    private static OptionalLong decode(String raw) {
        if (raw.length() != LENGTH) return OptionalLong.empty();
        String upper = raw.toUpperCase(Locale.ROOT);
        long v = 0;
        for (int i = 0; i < LENGTH; i++) {
            char c = upper.charAt(i);
            int digit = c < DECODE.length ? DECODE[c] : -1;
            if (digit < 0) return OptionalLong.empty();
            v = (v << 5) | digit;
        }
        return OptionalLong.of(v);
    }

    private static byte[] buildDecodeTable() {
        byte[] table = new byte[128];
        Arrays.fill(table, (byte) -1);
        for (int i = 0; i < ALPHABET.length(); i++) {
            table[ALPHABET.charAt(i)] = (byte) i;
        }
        return table;
    }
}
