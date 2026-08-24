package io.flowcatalyst.platform.principal.operations;

/// The input of [SendPasswordReset]: the user and whether the reset should
/// also clear their enrolled second factors on confirm (lost-device recovery).
public record SendPasswordResetCommand(String id, boolean reset2fa) {
}
