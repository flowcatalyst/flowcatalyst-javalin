package io.flowcatalyst.platform.function.operations;

import io.flowcatalyst.platform.function.Function;
import io.flowcatalyst.platform.function.FunctionRepository;
import io.flowcatalyst.platform.function.FunctionSettingsRepository;
import io.flowcatalyst.platform.function.SettingKey;
import io.flowcatalyst.platform.function.operations.FunctionEvents.ConfigUpdated;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.TxOperation;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/// `PUT /api/functions/{address}/config` (spec `function-context.md` §1):
/// full replacement of the function's config map. A [TxOperation] (not the
/// single-aggregate [Operation] this package's other by-address operations
/// use) because `fn_config` is a per-key table, not a single aggregate row
/// — the write goes straight through [FunctionSettingsRepository] on the
/// open transaction, then the event. `Authorize: Public` — load-or-404 +
/// reach is [Access#byAddress], run in `execute` (`CONVENTIONS.md` §3).
public final class SetFunctionConfig {

    /// spec §1: "≤ 100 keys, value ≤ 8 KiB (`SETTING_TOO_LARGE`)".
    public static final int MAX_KEYS = 100;
    public static final int MAX_VALUE_BYTES = 8192;

    private SetFunctionConfig() {
    }

    public static TxOperation<SetConfigCommand, ConfigUpdated> of(FunctionRepository functions,
            FunctionSettingsRepository settings) {
        Objects.requireNonNull(functions, "functions");
        Objects.requireNonNull(settings, "settings");
        return TxOperation.<SetConfigCommand, ConfigUpdated>named("SetFunctionConfig")
                .validate(SetFunctionConfig::validate)
                .authorize(Operation.Authorize.publicAccess()) // load-or-404 + reach is Access.byAddress, below
                .execute((scoped, cmd, ec) -> {
                    Function f = Access.byAddress(functions, cmd.address(), Auth.current());
                    settings.replaceConfig(f.id(), cmd.values(), ec.principalId(), scoped.dbTx());
                    ConfigUpdated event = ConfigUpdated.of(ec, f, cmd.values().keySet());
                    scoped.emitEvent(event, cmd);
                    return event;
                });
    }

    private static void validate(SetConfigCommand cmd) {
        if (cmd.values().size() > MAX_KEYS) {
            throw UseCaseException.validation("SETTING_TOO_LARGE",
                    "config carries " + cmd.values().size() + " keys, which exceeds the limit of " + MAX_KEYS);
        }
        for (var e : cmd.values().entrySet()) {
            SettingKey.parse(e.getKey()); // throws SETTING_KEY_INVALID
            int bytes = e.getValue() == null ? 0 : e.getValue().getBytes(StandardCharsets.UTF_8).length;
            if (bytes > MAX_VALUE_BYTES) {
                throw UseCaseException.validation("SETTING_TOO_LARGE",
                        "config['" + e.getKey() + "'] is " + bytes + " bytes, which exceeds the limit of "
                                + MAX_VALUE_BYTES);
            }
        }
    }
}
