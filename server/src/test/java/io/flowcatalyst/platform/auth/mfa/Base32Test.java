package io.flowcatalyst.platform.auth.mfa;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// RFC 4648 §10 vectors, padding stripped.
class Base32Test {

    @ParameterizedTest(name = "\"{0}\" → {1}")
    @CsvSource({
            "'', ''",
            "f, MY",
            "fo, MZXQ",
            "foo, MZXW6",
            "foob, MZXW6YQ",
            "fooba, MZXW6YTB",
            "foobar, MZXW6YTBOI",
    })
    void rfc4648Vectors(String text, String expected) {
        assertThat(Base32.encode(text.getBytes(StandardCharsets.US_ASCII))).isEqualTo(expected);
        assertThat(Base32.decode(expected)).isEqualTo(text.getBytes(StandardCharsets.US_ASCII));
    }

    @Test
    void decodeIsLenientAboutCasePaddingAndWhitespace() {
        assertThat(Base32.decode("mzxw 6ytb oi==")).isEqualTo("foobar".getBytes(StandardCharsets.US_ASCII));
        assertThatThrownBy(() -> Base32.decode("MZXW1")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void roundTripsArbitraryBytes() {
        var rnd = new Random(42);
        for (int len = 0; len < 64; len++) {
            byte[] b = new byte[len];
            rnd.nextBytes(b);
            assertThat(Base32.decode(Base32.encode(b))).as("len " + len).isEqualTo(b);
        }
    }
}
