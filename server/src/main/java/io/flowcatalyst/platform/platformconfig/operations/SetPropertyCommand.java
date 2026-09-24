package io.flowcatalyst.platform.platformconfig.operations;

import io.flowcatalyst.platform.platformconfig.ConfigCoordinate;
import io.flowcatalyst.sdk.usecase.AuditMasked;

import java.util.Set;

/// The input DTO for [SetProperty] (audit `operation` = `SetPropertyCommand`).
///
/// @param applicationCode the application the setting belongs to
/// @param section         setting group
/// @param property        setting name
/// @param value           the text to store (empty allowed, absent is not)
/// @param valueType       `PLAIN` | `SECRET`; `null` keeps the current type (`PLAIN` on first set)
/// @param description     optional; `null` clears
/// @param clientId        the client for a `CLIENT`-scoped value; `null` means `GLOBAL`
public record SetPropertyCommand(String applicationCode, String section, String property, String value,
                                 String valueType, String description, String clientId) implements AuditMasked {

    private static final Set<String> VALUE_MASKED = Set.of("value");
    private static final Set<String> NONE_MASKED = Set.of();

    /// The coordinate this command addresses (scope derived from `clientId`).
    public ConfigCoordinate coordinate() {
        return ConfigCoordinate.of(applicationCode, section, property, clientId);
    }

    /// `docs/spec/audit-redaction.md`: `value` is plaintext for a `SECRET`
    /// config value — the name rule alone would keep it (`value` matches no
    /// secret-key pattern), so this command declares it explicitly.
    @Override
    public Set<String> auditMaskedFields() {
        return maskedFieldsFor(valueType);
    }

    /// The masking rule as a function of just `valueType`, shared with the
    /// dashboard's temporary row-by-row redaction of already-stored
    /// `aud_logs` rows (`docs/spec/audit-redaction.md` "Temporary: redact
    /// existing rows"), which only has the stored JSON's `valueType`, not a
    /// live command instance to call [#auditMaskedFields] on: `value` is
    /// masked unless `valueType` is exactly `PLAIN` (a `null`/absent type
    /// keeps the current type, which may be `SECRET`, so it is treated as
    /// still-secret here too).
    public static Set<String> maskedFieldsFor(String valueType) {
        return "PLAIN".equals(valueType) ? NONE_MASKED : VALUE_MASKED;
    }
}
