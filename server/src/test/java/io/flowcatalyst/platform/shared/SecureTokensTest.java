package io.flowcatalyst.platform.shared;

import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecureTokensTest {

    @Test
    void aTokenIsUrlSafeUnpaddedAndOfTheRightLength() {
        String t = SecureTokens.urlSafe(32);
        assertThat(t).hasSize(43).matches("[A-Za-z0-9_-]+");
        assertThat(SecureTokens.urlSafe(64)).hasSize(86);
    }

    @Test
    void tokensDoNotRepeat() {
        var seen = new HashSet<String>();
        for (int i = 0; i < 1000; i++) {
            assertThat(seen.add(SecureTokens.urlSafe(16))).isTrue();
        }
    }

    @Test
    void fewerThanSixteenBytesIsRefused() {
        assertThatThrownBy(() -> SecureTokens.urlSafe(8)).isInstanceOf(IllegalArgumentException.class);
    }
}
