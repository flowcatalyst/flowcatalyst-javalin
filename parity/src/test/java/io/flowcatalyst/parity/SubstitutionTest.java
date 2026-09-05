package io.flowcatalyst.parity;

import io.flowcatalyst.platform.shared.json.Json;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Pins `${…}` substitution (parity-harness spec §3): a defined name is
/// resolved verbatim, but an undefined one is a scenario error — never
/// silently interpolated as an empty string.
class SubstitutionTest {

    private static Vars vars() {
        return new Vars("admin@example.com", "hunter2-hunter2", "run1", "client-1", "app-1", "admin-1");
    }

    @Test
    void aDefinedBuiltinIsSubstitutedVerbatim() {
        assertThat(Substitution.resolve("${admin.email}", vars())).isEqualTo("admin@example.com");
    }

    @Test
    void multiplePlaceholdersInOneStringAreAllResolved() {
        assertThat(Substitution.resolve("code:${client.id}:${run}", vars())).isEqualTo("code:client-1:run1");
    }

    @Test
    void aCapturedValueIsSubstituted() {
        Vars vars = vars();
        vars.capture("etId", "evt_42");
        assertThat(Substitution.resolve("/api/event-types/${etId}", vars)).isEqualTo("/api/event-types/evt_42");
    }

    /// The behaviour the brief pins by name: "undefined `${x}` is an error,
    /// not empty" — a scenario author's typo must not silently degrade into
    /// a plausible-looking empty path segment.
    @Test
    void anUndefinedNameIsAnErrorNotAnEmptyString() {
        assertThatThrownBy(() -> Substitution.resolve("${doesNotExist}", vars()))
                .isInstanceOf(SubstitutionException.class);
    }

    @Test
    void aStringWithNoPlaceholdersPassesThroughUnchanged() {
        assertThat(Substitution.resolve("/api/event-types", vars())).isEqualTo("/api/event-types");
    }

    @Test
    void jsonTreeSubstitutionAppliesToEveryStringLeafOnly() {
        ObjectNode body = Json.MAPPER.createObjectNode();
        body.put("code", "parity:smoke:eventtype:${run}");
        body.put("count", 3);
        body.putNull("description");

        var out = Substitution.resolve(body, vars());

        assertThat(out.get("code").asString()).isEqualTo("parity:smoke:eventtype:run1");
        assertThat(out.get("count").asInt()).isEqualTo(3);
        assertThat(out.get("description").isNull()).isTrue();
    }

    @Test
    void totpBuiltinComputesACodeFromACapturedSecret() {
        Vars vars = vars();
        vars.capture("mfaSecret", io.flowcatalyst.platform.auth.mfa.Totp.generateSecret());
        String code = Substitution.resolve("${totp:mfaSecret}", vars);
        assertThat(code).matches("\\d{6}");
    }

    @Test
    void b64urlBuiltinReEncodesACapturedValue() {
        Vars vars = vars();
        vars.capture("raw", "hello world");
        assertThat(Substitution.resolve("${b64url:raw}", vars)).isEqualTo("aGVsbG8gd29ybGQ");
    }
}
