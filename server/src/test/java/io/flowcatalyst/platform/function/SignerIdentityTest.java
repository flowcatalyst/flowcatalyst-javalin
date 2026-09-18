package io.flowcatalyst.platform.function;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SignerIdentityTest {

    @Test
    void bothComponentsRequired() {
        var identity = new SignerIdentity("https://token.actions.githubusercontent.com", "repo:acme/billing:ref:refs/heads/main");
        assertThat(identity.issuer()).isEqualTo("https://token.actions.githubusercontent.com");
        assertThat(identity.subject()).isEqualTo("repo:acme/billing:ref:refs/heads/main");
    }

    @Test
    void nullIssuerRejected() {
        assertThatThrownBy(() -> new SignerIdentity(null, "subject")).isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullSubjectRejected() {
        assertThatThrownBy(() -> new SignerIdentity("issuer", null)).isInstanceOf(NullPointerException.class);
    }
}
