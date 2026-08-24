package io.flowcatalyst.platform.principal.operations;

import com.fasterxml.jackson.annotation.JsonIgnore;

/// The input DTO for [CreateUser] (audit `operation` = `CreateCommand`).
///
/// `password` is plaintext and must never leave the JVM: it is excluded from
/// the audit row's `operation_json` ([JsonIgnore]) and masked in `toString`
/// (spec §8 — Go wrote it to the audit log; deliberate deviation D5).
///
/// @param email    login identity (normalised by the operation)
/// @param name     display name; `null` = the email
/// @param scope    `ANCHOR` | `PARTNER` | `CLIENT`, exact
/// @param clientId home client; required for `CLIENT` / `PARTNER`
/// @param password optional plaintext password, policy-checked
/// @param idpType  `OIDC` marks the user federated (no password); anything else is ignored
public record CreateCommand(String email, String name, String scope, String clientId,
                            @JsonIgnore String password, String idpType) {

    public boolean hasPassword() {
        return password != null && !password.isEmpty();
    }

    @Override
    public String toString() {
        return "CreateCommand[email=" + email + ", name=" + name + ", scope=" + scope + ", clientId=" + clientId
                + ", password=" + (password == null ? "null" : "***") + ", idpType=" + idpType + "]";
    }
}
