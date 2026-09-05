package io.flowcatalyst.parity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/// The `location-param:<name>` capture form (spec §3): the authorization
/// code an `/oauth/authorize` redirect carries in its `Location`.
class RunnerTest {

    @Test
    void locationParamReadsOneDecodedQueryParameter() {
        String loc = "https://parity.example/cb?code=abc%2Bdef&state=st-1#frag";
        assertThat(Runner.queryParam(loc, "code")).contains("abc+def");
        assertThat(Runner.queryParam(loc, "state")).contains("st-1");
        assertThat(Runner.queryParam(loc, "missing")).isEmpty();
        assertThat(Runner.queryParam("https://parity.example/cb", "code")).isEmpty();
    }
}
