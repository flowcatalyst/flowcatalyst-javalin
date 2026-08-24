package io.flowcatalyst.router.queue.nats;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// URI parsing (`docs/spec/router.md` §7.4). No connection is attempted —
/// [NatsQueueUri#parse] is pure.
class NatsQueueUriTest {

    @Test
    @DisplayName("a bare host URI applies every documented default")
    void appliesEveryDefault() {
        var cfg = NatsQueueUri.parse("nats://localhost:4222");

        assertThat(cfg.servers()).containsExactly("nats://localhost:4222");
        assertThat(cfg.streamName()).isEqualTo("FLOWCATALYST");
        assertThat(cfg.consumerName()).isEqualTo("fc-router");
        assertThat(cfg.subject()).isEqualTo("flowcatalyst.>");
        assertThat(cfg.maxMessagesPerPoll()).isEqualTo(10);
        assertThat(cfg.pollTimeout()).isEqualTo(Duration.ofSeconds(20));
        assertThat(cfg.ackWait()).isEqualTo(Duration.ofSeconds(120));
        assertThat(cfg.maxDeliver()).isEqualTo(10);
        assertThat(cfg.maxAckPending()).isEqualTo(1000);
        assertThat(cfg.storage()).isEqualTo("file");
        assertThat(cfg.replicas()).isEqualTo(1);
        assertThat(cfg.maxAge()).isEqualTo(Duration.ofDays(7));
        assertThat(cfg.identifier()).isEqualTo("FLOWCATALYST/fc-router");
    }

    @Test
    @DisplayName("every query parameter overrides its default")
    void everyParameterOverrides() {
        var cfg = NatsQueueUri.parse("nats://localhost:4222"
                + "?stream=CUSTOM&consumer=my-consumer&subject=custom.>"
                + "&max-messages=25&poll-timeout-ms=5000&ack-wait-secs=60"
                + "&max-deliver=3&max-ack-pending=500&storage=memory"
                + "&replicas=3&max-age-days=1");

        assertThat(cfg.streamName()).isEqualTo("CUSTOM");
        assertThat(cfg.consumerName()).isEqualTo("my-consumer");
        assertThat(cfg.subject()).isEqualTo("custom.>");
        assertThat(cfg.maxMessagesPerPoll()).isEqualTo(25);
        assertThat(cfg.pollTimeout()).isEqualTo(Duration.ofMillis(5000));
        assertThat(cfg.ackWait()).isEqualTo(Duration.ofSeconds(60));
        assertThat(cfg.maxDeliver()).isEqualTo(3);
        assertThat(cfg.maxAckPending()).isEqualTo(500);
        assertThat(cfg.storage()).isEqualTo("memory");
        assertThat(cfg.replicas()).isEqualTo(3);
        assertThat(cfg.maxAge()).isEqualTo(Duration.ofDays(1));
        assertThat(cfg.identifier()).isEqualTo("CUSTOM/my-consumer");
    }

    @Test
    @DisplayName("comma-separated hosts become one server entry each")
    void commaSeparatedHosts() {
        var cfg = NatsQueueUri.parse("nats://host1:4222,host2:4222,host3:4222?stream=S");

        assertThat(cfg.servers()).containsExactly(
                "nats://host1:4222", "nats://host2:4222", "nats://host3:4222");
    }

    @Test
    @DisplayName("max-age-days of zero or negative means unlimited retention")
    void nonPositiveMaxAgeIsUnlimited() {
        assertThat(NatsQueueUri.parse("nats://localhost:4222?max-age-days=0").maxAge())
                .isEqualTo(Duration.ZERO);
        assertThat(NatsQueueUri.parse("nats://localhost:4222?max-age-days=-5").maxAge())
                .isEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("a present but empty query value is treated as absent, not as an override")
    void emptyValueIsTreatedAsAbsent() {
        var cfg = NatsQueueUri.parse("nats://localhost:4222?stream=&max-messages=&storage=");

        assertThat(cfg.streamName()).isEqualTo("FLOWCATALYST");
        assertThat(cfg.maxMessagesPerPoll()).isEqualTo(10);
        assertThat(cfg.storage()).isEqualTo("file");
    }

    @Test
    @DisplayName("an unparseable numeric value is ignored, keeping the default")
    void unparseableNumberIsIgnored() {
        var cfg = NatsQueueUri.parse("nats://localhost:4222?max-messages=not-a-number&replicas=3.5");

        assertThat(cfg.maxMessagesPerPoll()).isEqualTo(10);
        assertThat(cfg.replicas()).isEqualTo(1);
    }

    @Test
    @DisplayName("a query value is percent- and form-decoded")
    void queryValueIsDecoded() {
        var cfg = NatsQueueUri.parse("nats://localhost:4222?subject=flowcatalyst%2Etest.%3E");

        assertThat(cfg.subject()).isEqualTo("flowcatalyst.test.>");
    }

    @Test
    @DisplayName("a non-nats scheme is rejected")
    void rejectsWrongScheme() {
        assertThatThrownBy(() -> NatsQueueUri.parse("postgres://localhost:5432"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nats://");
    }

    @Test
    @DisplayName("a URI with no host is rejected")
    void rejectsMissingHost() {
        assertThatThrownBy(() -> NatsQueueUri.parse("nats://?stream=X"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing host");
    }

    @Test
    @DisplayName("a URI with no scheme separator is rejected")
    void rejectsMissingSchemeSeparator() {
        assertThatThrownBy(() -> NatsQueueUri.parse("localhost:4222"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nats://");
    }
}
