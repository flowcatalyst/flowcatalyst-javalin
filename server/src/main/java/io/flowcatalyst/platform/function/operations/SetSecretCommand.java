package io.flowcatalyst.platform.function.operations;

import io.flowcatalyst.platform.function.FunctionAddress;
import io.flowcatalyst.platform.function.SecretValue;

import java.util.Objects;

/// `SetFunctionSecret`'s command (spec `function-context.md` §1, X1): `value`
/// is a [SecretValue], not a bare `String` — the ONE reason this command's
/// serialised form (`aud_logs.operation_json`, via
/// `io.flowcatalyst.sdk.usecase.jdbc.Sink#writeAudit`) never carries the
/// plaintext: [SecretValue] masks itself for every consumer of Jackson,
/// there is no second place that must remember to redact it.
public record SetSecretCommand(FunctionAddress address, String key, SecretValue value) {
    public SetSecretCommand {
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
    }
}
