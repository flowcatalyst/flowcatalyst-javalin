package io.flowcatalyst.platform.function;

import io.flowcatalyst.platform.function.operations.SetSecretCommand;
import io.flowcatalyst.platform.shared.json.Json;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// [SecretValue] masks itself for every Jackson consumer AND for `toString`
/// (spec `function-context.md` §1/§4, X1) — the narrowest possible pin for
/// "the command's `toString`/JSON masks `value`": no HTTP layer, no
/// database, just the type itself and a command that carries it, exactly
/// as `io.flowcatalyst.sdk.usecase.jdbc.Sink#writeAudit` serialises it for
/// `aud_logs.operation_json` (`PlatformSink#toJson` is `mapper.writeValueAsString(command)`,
/// the SAME [Json#MAPPER] this test uses).
class SecretValueTest {

    private static final String SECRET = "sv-marker-do-not-leak-9f3c7a";

    @Test
    void toStringNeverContainsTheValue() {
        SecretValue v = new SecretValue(SECRET);
        assertThat(v.toString()).as("mutant: unmask toString").doesNotContain(SECRET);
        assertThat(v.toString()).isEqualTo("SecretValue[***]");
    }

    @Test
    void jacksonSerialisesToTheMaskedLiteralNeverTheValue() {
        SecretValue v = new SecretValue(SECRET);
        String json = Json.write(v);
        assertThat(json).as("mutant: serialise the real value").doesNotContain(SECRET);
        assertThat(json).isEqualTo("\"***\"");
    }

    /// The exact seam X1 pins: a command carrying a [SecretValue], serialised
    /// the same way `PlatformSink.writeAudit` serialises it for
    /// `aud_logs.operation_json`.
    @Test
    void aCommandCarryingASecretValueSerialisesWithoutTheValue() {
        var address = FunctionAddress.of(new DnsLabel("acme"), new DnsLabel("svc"), new DnsLabel("fn"));
        var cmd = new SetSecretCommand(address, "API_KEY", new SecretValue(SECRET));
        String json = Json.write(cmd);
        assertThat(json).as("mutant: the audit row would carry the plaintext secret").doesNotContain(SECRET);
        assertThat(json).contains("\"value\":\"***\"");
    }

    @Test
    void valueAccessorStillReturnsTheRealPlaintextForTheOperationToUse() {
        SecretValue v = new SecretValue(SECRET);
        assertThat(v.value()).as("the wrapper must still carry the real value for encryption").isEqualTo(SECRET);
    }
}
