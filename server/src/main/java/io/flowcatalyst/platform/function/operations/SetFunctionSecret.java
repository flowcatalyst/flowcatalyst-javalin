package io.flowcatalyst.platform.function.operations;

import io.flowcatalyst.platform.function.Function;
import io.flowcatalyst.platform.function.FunctionRepository;
import io.flowcatalyst.platform.function.FunctionSettingsRepository;
import io.flowcatalyst.platform.function.SettingKey;
import io.flowcatalyst.platform.function.operations.FunctionEvents.SecretSet;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.TxOperation;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/// `PUT /api/functions/{address}/secrets/{key}` (spec `function-context.md`
/// §1, X1): sets or replaces one secret. `FunctionApi` refuses this route
/// with `503 ENCRYPTION_UNCONFIGURED` before ever building a command when no
/// app key is configured — this operation's own job is everything else:
/// the key format, the size limit, load-or-404 + reach, and writing the
/// value through [FunctionSettingsRepository] (which encrypts it) without
/// the plaintext ever reaching the event or the audit log (`cmd`'s `value`
/// is a [io.flowcatalyst.platform.function.SecretValue], masked for
/// every Jackson consumer — see its own doc).
public final class SetFunctionSecret {

    /// spec §1: "value ≤ 8 KiB, non-empty".
    public static final int MAX_VALUE_BYTES = 8192;

    private SetFunctionSecret() {
    }

    public static TxOperation<SetSecretCommand, SecretSet> of(FunctionRepository functions,
            FunctionSettingsRepository settings) {
        Objects.requireNonNull(functions, "functions");
        Objects.requireNonNull(settings, "settings");
        return TxOperation.<SetSecretCommand, SecretSet>named("SetFunctionSecret")
                .validate(SetFunctionSecret::validate)
                .authorize(Operation.Authorize.publicAccess()) // load-or-404 + reach is Access.byAddress, below
                .execute((scoped, cmd, ec) -> {
                    Function f = Access.byAddress(functions, cmd.address(), Auth.current());
                    settings.putSecret(f.id(), cmd.key(), cmd.value(), ec.principalId(), scoped.dbTx());
                    SecretSet event = SecretSet.of(ec, f, cmd.key());
                    scoped.emitEvent(event, cmd);
                    return event;
                });
    }

    private static void validate(SetSecretCommand cmd) {
        SettingKey.parse(cmd.key()); // throws SETTING_KEY_INVALID
        String value = cmd.value().value();
        if (value.isEmpty()) {
            throw UseCaseException.validation("SETTING_VALUE_REQUIRED", "value is required");
        }
        int bytes = value.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_VALUE_BYTES) {
            throw UseCaseException.validation("SETTING_TOO_LARGE",
                    "value is " + bytes + " bytes, which exceeds the limit of " + MAX_VALUE_BYTES);
        }
    }
}
