package io.flowcatalyst.platform.principal.operations;

import com.fasterxml.jackson.annotation.JsonIgnore;

/// The input DTO for [ResetPassword] (audit `operation` = `ResetPasswordCommand`).
///
/// `newPassword` is plaintext: excluded from the audit row ([JsonIgnore]) and
/// masked in `toString`. `enforcePasswordComplexity` defaults to `true`
/// (`null`): the full policy runs; `false` is the SDK's relaxed path, whose
/// only floor is two characters — the caller owns its policy by contract.
public record ResetPasswordCommand(String id, @JsonIgnore String newPassword, Boolean enforcePasswordComplexity) {

    public boolean strict() {
        return enforcePasswordComplexity == null || enforcePasswordComplexity;
    }

    @Override
    public String toString() {
        return "ResetPasswordCommand[id=" + id + ", newPassword=***, enforcePasswordComplexity=" + enforcePasswordComplexity + "]";
    }
}
