package io.flowcatalyst.platform.function.operations;

import io.flowcatalyst.platform.function.Function;
import io.flowcatalyst.platform.function.FunctionRepository;
import io.flowcatalyst.platform.function.FunctionSettingsRepository;
import io.flowcatalyst.platform.function.SettingKey;
import io.flowcatalyst.platform.function.operations.FunctionEvents.SecretDeleted;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.TxOperation;

import java.util.Objects;

/// `DELETE /api/functions/{address}/secrets/{key}` (spec `function-context.md`
/// §1): 404 when the key was never set — `FunctionApi` refuses the route
/// with `503 ENCRYPTION_UNCONFIGURED` first when no app key is configured
/// (spec §1), matching `SetFunctionSecret`.
public final class DeleteFunctionSecret {

    private DeleteFunctionSecret() {
    }

    public static TxOperation<DeleteSecretCommand, SecretDeleted> of(FunctionRepository functions,
            FunctionSettingsRepository settings) {
        Objects.requireNonNull(functions, "functions");
        Objects.requireNonNull(settings, "settings");
        return TxOperation.<DeleteSecretCommand, SecretDeleted>named("DeleteFunctionSecret")
                .validate(cmd -> SettingKey.parse(cmd.key())) // throws SETTING_KEY_INVALID
                .authorize(Operation.Authorize.publicAccess()) // load-or-404 + reach is Access.byAddress, below
                .execute((scoped, cmd, ec) -> {
                    Function f = Access.byAddress(functions, cmd.address(), Auth.current());
                    boolean deleted = settings.deleteSecret(f.id(), cmd.key(), scoped.dbTx());
                    if (!deleted) {
                        throw UseCaseException.resourceNotFound("FunctionSecret", cmd.key());
                    }
                    SecretDeleted event = SecretDeleted.of(ec, f, cmd.key());
                    scoped.emitEvent(event, cmd);
                    return event;
                });
    }
}
