package io.flowcatalyst.platform.shared;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;

import static org.assertj.core.api.Assertions.assertThat;

class FailuresTest {

    // The JDK HttpClient's ConnectException often has a null message — the class must still show.
    @Test
    void aNullMessageStillNamesTheClassAndTheCause() {
        var e = new ConnectException();
        e.initCause(new IOException("Connection refused"));
        assertThat(Failures.describe(e)).isEqualTo("ConnectException; caused by IOException: Connection refused");
    }

    @Test
    void theChainStopsAtFourLinksAndCyclesAreCut() {
        var a = new RuntimeException("a");
        var b = new RuntimeException("b");
        a.initCause(b);
        b.initCause(a);
        assertThat(Failures.describe(a)).isEqualTo("RuntimeException: a; caused by RuntimeException: b");

        Throwable deep = new RuntimeException("5", new RuntimeException("4",
                new RuntimeException("3", new RuntimeException("2", new RuntimeException("1")))));
        assertThat(Failures.describe(deep)).doesNotContain(": 1").contains(": 2");
    }
}
