package io.flowcatalyst.platform.auth.oidc;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// A refused code exchange carries the IdP's own reason into the log (the
/// 2026-09-23 incident was an `AADSTS7000218` nobody could see — the log said
/// "answered 401"). Mutant: drop the reason.
class OidcProviderIdpErrorTest {

    @Test
    void theIdpsErrorAndDescriptionAreKept() {
        String body = "{\"error\":\"invalid_client\",\"error_description\":\"AADSTS7000218: The request body must "
                + "contain the following parameter: 'client_assertion' or 'client_secret'.\"}";
        assertThat(OidcProvider.idpError(body))
                .isEqualTo(": invalid_client — AADSTS7000218: The request body must contain the following parameter: "
                        + "'client_assertion' or 'client_secret'.");
        assertThat(OidcProvider.idpError("{\"error\":\"invalid_grant\"}")).isEqualTo(": invalid_grant");
    }

    @Test
    void aBodyWithoutAnErrorAddsNothingAndALongOneIsCapped() {
        assertThat(OidcProvider.idpError("<html>bad gateway</html>")).isEmpty();
        assertThat(OidcProvider.idpError("{\"message\":\"nope\"}")).isEmpty();
        assertThat(OidcProvider.idpError(null)).isEmpty();
        String longDescription = "x".repeat(2000);
        assertThat(OidcProvider.idpError("{\"error\":\"e\",\"error_description\":\"" + longDescription + "\"}"))
                .hasSizeLessThan(520).endsWith("…");
    }
}
