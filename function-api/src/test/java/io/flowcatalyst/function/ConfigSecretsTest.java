package io.flowcatalyst.function;

import java.util.NoSuchElementException;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// `Config`/`Secrets` share a shape: `require` is a default method over
/// `get`, and it must actually fail loudly when the key was never declared
/// rather than handing back `null` or an empty string.
class ConfigSecretsTest {

    @Test
    void configRequireReturnsTheDeclaredValue() {
        Config config = key -> "db.url".equals(key) ? Optional.of("jdbc:postgresql://x") : Optional.empty();
        assertThat(config.require("db.url")).isEqualTo("jdbc:postgresql://x");
    }

    @Test
    void configRequireThrowsWhenKeyNotDeclared() {
        Config config = key -> Optional.empty();
        assertThatThrownBy(() -> config.require("missing")).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void secretsRequireReturnsTheDeclaredValue() {
        Secrets secrets = key -> "api.key".equals(key) ? Optional.of("s3cr3t") : Optional.empty();
        assertThat(secrets.require("api.key")).isEqualTo("s3cr3t");
    }

    @Test
    void secretsRequireThrowsWhenKeyNotDeclared() {
        Secrets secrets = key -> Optional.empty();
        assertThatThrownBy(() -> secrets.require("missing")).isInstanceOf(NoSuchElementException.class);
    }
}
