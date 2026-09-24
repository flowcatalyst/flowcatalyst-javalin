package io.flowcatalyst.platform.auth.oauth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PkceTest {

    private static final String VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"; // RFC 7636 appendix B
    private static final String CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";

    @Test
    void rfc7636AppendixBVectorVerifiesUnderS256AndByDefault() {
        assertThat(Pkce.s256(VERIFIER)).isEqualTo(CHALLENGE);
        assertThat(Pkce.verify(CHALLENGE, "S256", VERIFIER)).isEmpty();
        assertThat(Pkce.verify(CHALLENGE, null, VERIFIER)).as("absent method ⇒ S256").isEmpty();
        assertThat(Pkce.verify(CHALLENGE, "", VERIFIER)).isEmpty();
    }

    @Test
    void plainComparesTheVerifierItself() {
        assertThat(Pkce.verify(VERIFIER, "plain", VERIFIER)).isEmpty();
        assertThat(Pkce.verify(CHALLENGE, "plain", VERIFIER).orElseThrow().description()).isEqualTo("Invalid code_verifier");
    }

    @Test
    void theVerifierIsValidatedBeforeItIsCompared() {
        assertThat(Pkce.verify(CHALLENGE, "S256", "").orElseThrow().description()).isEqualTo("Missing code_verifier");
        assertThat(Pkce.verify(CHALLENGE, "S256", null).orElseThrow().description()).isEqualTo("Missing code_verifier");
        assertThat(Pkce.verify(CHALLENGE, "S256", "short").orElseThrow().description()).isEqualTo("code_verifier must be 43-128 characters");
        assertThat(Pkce.verify(CHALLENGE, "S256", "x".repeat(129)).orElseThrow().description()).isEqualTo("code_verifier must be 43-128 characters");
        assertThat(Pkce.verify(CHALLENGE, "S256", "a".repeat(42) + "!").orElseThrow().description()).isEqualTo("code_verifier contains invalid characters");
        var wrong = Pkce.verify(CHALLENGE, "S256", "a".repeat(43)).orElseThrow();
        assertThat(wrong.code()).isEqualTo("invalid_grant");
        assertThat(wrong.status()).isEqualTo(400);
    }
}
