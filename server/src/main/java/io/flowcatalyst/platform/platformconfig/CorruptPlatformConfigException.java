package io.flowcatalyst.platform.platformconfig;

import io.flowcatalyst.platform.shared.CorruptRowException;

/// A row read from `app_platform_configs` whose `scope` or `value_type`
/// column holds a value [ConfigScope#parse] / [ConfigValueType#parse] does
/// not recognise (X-06: never a silent default). Carries the offending
/// row's id.
public final class CorruptPlatformConfigException extends CorruptRowException {

    public CorruptPlatformConfigException(String configId, Throwable cause) {
        super("platform config", configId, cause);
    }
}
