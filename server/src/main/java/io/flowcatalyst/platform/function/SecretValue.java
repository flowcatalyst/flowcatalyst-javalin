package io.flowcatalyst.platform.function;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Objects;

/// A secret's plaintext, in flight only (spec `function-context.md` §4,
/// X1): the ONE place a caller-supplied secret value lives in memory between
/// the HTTP body and [FunctionSettingsRepository]'s encryption call.
///
/// **This type never reveals [#value] to anything that formats it.**
/// [#masked] is the ONLY representation [io.flowcatalyst.platform.shared.json.Json#MAPPER]
/// (or any other Jackson mapper — `@JsonValue` is a `com.fasterxml.jackson.annotation`
/// contract, honoured by `tools.jackson` the same as by DispatchMode's own
/// use of it) ever writes for this type — in particular, a `SetSecretCommand`
/// carrying one serialises to `"***"` wherever [io.flowcatalyst.sdk.usecase.jdbc.Sink#writeAudit]
/// turns the command into `aud_logs.operation_json`, and the same for
/// `msg_events.data` if a command's shape were ever reused there. `toString`
/// is masked the same way (`CONVENTIONS.md` §2: "carriers of key material,
/// secrets or ciphertext mask `toString`").
public record SecretValue(String value) {

    public SecretValue {
        Objects.requireNonNull(value, "value");
    }

    /// The one thing Jackson ever writes for a `SecretValue` — never [#value].
    @JsonValue
    public String masked() {
        return "***";
    }

    @Override
    public String toString() {
        return "SecretValue[***]";
    }
}
