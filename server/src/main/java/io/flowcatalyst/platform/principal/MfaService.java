package io.flowcatalyst.platform.principal;

import io.flowcatalyst.sdk.usecase.UseCaseException;

import java.util.List;

/// The two-factor facts this package needs (spec §3, §10): a user's
/// confirmed second factors (for the by-id read) and the administrative
/// "clear everything" reset. The mfa subsystem is not ported yet;
/// [#notConfigured()] answers as Go does without an MFA service.
public interface MfaService {

    /// The user's confirmed method names (`TOTP`, `EMAIL_PIN`); empty when none.
    List<String> confirmedMethods(String principalId);

    /// Clears factors, recovery codes, pending PINs and trusted devices.
    ///
    /// @throws UseCaseException internal `MFA_NOT_CONFIGURED` | `MFA`
    void resetAll(String principalId);

    /// The admin reset (auth-identity §6.9): clears everything like
    /// [#resetAll] and writes the `2FA_RESET_BY_ADMIN` audit row with the
    /// administrator as the actor.
    void resetAllByAdmin(String principalId, String adminId, String adminName);

    /// Whether a real service is wired; the by-id read enriches only then.
    boolean configured();

    static MfaService notConfigured() {
        return new MfaService() {
            @Override
            public List<String> confirmedMethods(String principalId) {
                return List.of();
            }

            @Override
            public void resetAll(String principalId) {
                throw UseCaseException.internal("MFA_NOT_CONFIGURED", "Two-factor service not configured", null);
            }

            @Override
            public void resetAllByAdmin(String principalId, String adminId, String adminName) {
                throw UseCaseException.internal("MFA_NOT_CONFIGURED", "Two-factor service not configured", null);
            }

            @Override
            public boolean configured() {
                return false;
            }
        };
    }
}
