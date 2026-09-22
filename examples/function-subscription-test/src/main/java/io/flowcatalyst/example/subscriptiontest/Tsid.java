package io.flowcatalyst.example.subscriptiontest;

import java.security.SecureRandom;
import java.time.Instant;

/// A time-sorted, Crockford-base32 id: 42 bits of milliseconds since the
/// TSID epoch (2020-01-01) then 22 bits of randomness, rendered as 13
/// characters — the same shape the platform's own ids use. Small enough to
/// live here rather than pull a library into a sample function.
final class Tsid {

    private static final long EPOCH_MS = Instant.parse("2020-01-01T00:00:00Z").toEpochMilli();
    private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    private static final SecureRandom RANDOM = new SecureRandom();

    private Tsid() {
    }

    static String next() {
        long time = (System.currentTimeMillis() - EPOCH_MS) & ((1L << 42) - 1);
        long random = RANDOM.nextLong() & ((1L << 22) - 1);
        long value = (time << 22) | random;
        char[] out = new char[13];
        for (int i = 12; i >= 0; i--) {
            out[i] = ALPHABET.charAt((int) (value & 31));
            value >>>= 5;
        }
        return new String(out);
    }
}
