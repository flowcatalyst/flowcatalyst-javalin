package io.flowcatalyst.platform.emaildomainmapping;

import io.flowcatalyst.sdk.usecase.UseCaseException;

import java.util.List;

/// The second-factor policy a domain applies to its internal-auth users
/// (spec §1): whether a second factor is required, which mechanisms are
/// permitted, and whether a remembered browser may skip the challenge for
/// `rememberDeviceDays`. Immutable; the invariant *required ⇒ at least one
/// method* is checked by [#checkConsistent], which the aggregate calls on
/// every change — the record itself is lenient so a stored row always reads.
///
/// @param required              whether a second factor is enforced
/// @param allowedMethods        permitted mechanisms (may be empty when not required)
/// @param rememberDeviceEnabled whether a remembered browser skips the challenge
/// @param rememberDeviceDays    how long a browser stays remembered
public record TwoFactorPolicy(boolean required, List<MfaMethod> allowedMethods, boolean rememberDeviceEnabled,
                              int rememberDeviceDays) {

    /// The domain default for `rememberDeviceDays` (spec §1).
    public static final int DEFAULT_REMEMBER_DEVICE_DAYS = 30;

    /// No second factor, nothing remembered — a fresh mapping's policy.
    public static final TwoFactorPolicy OFF = new TwoFactorPolicy(false, List.of(), false, DEFAULT_REMEMBER_DEVICE_DAYS);

    public TwoFactorPolicy {
        allowedMethods = allowedMethods == null ? List.of() : List.copyOf(allowedMethods);
    }

    /// The invariant: a required second factor needs at least one permitted method (spec §4).
    ///
    /// @throws UseCaseException validation `2FA_METHOD_REQUIRED`
    public void checkConsistent() {
        if (required && allowedMethods.isEmpty()) {
            throw UseCaseException.validation("2FA_METHOD_REQUIRED",
                    "at least one 2FA method must be allowed when require2fa is set");
        }
    }
}
