package io.flowcatalyst.platform.function;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/// The aggregate's pure rules (spec `function-registry.md` §6.5, amended
/// `function-domains-no-dns.md`): `claim` alone — a claim is verified by
/// being made, so there is no pending/verified transition left to pin here.
class FunctionDomainTest {

    private static final Hostname HOST = Hostname.parse("api.acme.com");
    private static final FunctionOwner CLIENT_1 = FunctionOwner.ofClientId("clt_1");

    @Test
    void claimAssignsAnIdOwnerHostnameAndCreationTime() {
        Instant now = Instant.now();
        FunctionDomain d = FunctionDomain.claim(CLIENT_1, HOST, now);
        assertThat(d.id()).startsWith("fnd_");
        assertThat(d.owner()).isEqualTo(CLIENT_1);
        assertThat(d.hostname()).isEqualTo(HOST);
        assertThat(d.createdAt()).isEqualTo(now);
    }
}
