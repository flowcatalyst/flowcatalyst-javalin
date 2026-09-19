package io.flowcatalyst.platform.function;

import io.flowcatalyst.sdk.usecase.UseCaseException;

import java.util.Objects;
import java.util.regex.Pattern;

/// A config/secret key name — shared by `fn_config`/`fn_secrets` (spec
/// `function-context.md` §1) and by a manifest's own `config`, `secrets` and
/// `db[].secretRef` entries, which are held to the SAME rule (spec §1: "the
/// manifest's config/secrets/secretRef entries are held to the same rule").
///
/// Wire/stored form is [#value] itself — there is no other representation.
/// `parse` is the ONE place `SETTING_KEY_INVALID` is thrown; callers that
/// need a different error code (the manifest's `CONFIG_INVALID`/`DB_INVALID`)
/// catch it and rethrow with their own code, carrying this class's message.
///
/// @param value the key text, already validated
public record SettingKey(String value) {

    /// `^[A-Za-z][A-Za-z0-9_./-]{0,99}$` (spec §1): a letter, then up to 99
    /// letters/digits/`_`/`.`/`/`/`-`. Matches `fn_config.key`'s/`fn_secrets.key`'s
    /// `VARCHAR(100)` check constraint exactly.
    private static final Pattern PATTERN = Pattern.compile("^[A-Za-z][A-Za-z0-9_./-]{0,99}$");

    public SettingKey {
        Objects.requireNonNull(value, "value");
        if (!PATTERN.matcher(value).matches()) {
            throw UseCaseException.validation("SETTING_KEY_INVALID",
                    "'" + value + "' is not a valid setting key: expected " + PATTERN.pattern());
        }
    }

    /// Non-throwing check for a tolerant reader (`Manifest#readStored`), which
    /// drops an invalid entry rather than failing the whole document.
    public static boolean isValid(String value) {
        return value != null && PATTERN.matcher(value).matches();
    }

    /// @throws UseCaseException validation `SETTING_KEY_INVALID`
    public static SettingKey parse(String value) {
        return new SettingKey(value);
    }
}
