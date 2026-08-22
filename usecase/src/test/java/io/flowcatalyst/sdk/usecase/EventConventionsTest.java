package io.flowcatalyst.sdk.usecase;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EventConventionsTest {

    @Test
    void buildersMatchTheCrossSdkConventions() {
        assertThat(EventConventions.buildEventType("platform", "admin", "eventtype", "created"))
                .isEqualTo("platform:admin:eventtype:created");
        assertThat(EventConventions.buildSubject("platform", "eventtype", "evt_01H")).isEqualTo("platform.eventtype.evt_01H");
        assertThat(EventConventions.buildMessageGroup("platform", "eventtype", "evt_01H")).isEqualTo("platform:eventtype:evt_01H");
    }

    @Test
    void extractorsMatchTheGoEdgeCases() {
        assertThat(EventConventions.extractAggregateType("platform.eventtype.123")).isEqualTo("Eventtype");
        assertThat(EventConventions.extractAggregateType("platform")).isEqualTo("Unknown");
        assertThat(EventConventions.extractAggregateType("platform..123")).isEmpty();
        assertThat(EventConventions.extractAggregateType("platform.eventtype.123.extra")).isEqualTo("Eventtype");

        assertThat(EventConventions.extractEntityId("platform.eventtype.123")).isEqualTo("123");
        assertThat(EventConventions.extractEntityId("platform.eventtype")).isEmpty();
        assertThat(EventConventions.extractEntityId("platform.eventtype.123.extra")).isEqualTo("123");
        assertThat(EventConventions.extractEntityId("platform.eventtype.")).isEmpty();
    }
}
