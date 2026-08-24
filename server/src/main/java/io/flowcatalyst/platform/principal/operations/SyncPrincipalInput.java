package io.flowcatalyst.platform.principal.operations;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.List;

/// One principal in a sync payload (spec §7). `passwordHash`, when present,
/// is an already-hashed credential stored verbatim so a migrated user keeps
/// their password (the login flow re-encodes it on first success); absent
/// leaves any existing hash untouched. It is excluded from the audit row
/// ([JsonIgnore]) and masked in `toString`.
public record SyncPrincipalInput(String email, String name, List<String> roles, boolean active, @JsonIgnore String passwordHash) {

    public SyncPrincipalInput {
        roles = roles == null ? List.of() : List.copyOf(roles);
    }

    public boolean hasPasswordHash() {
        return passwordHash != null && !passwordHash.isEmpty();
    }

    @Override
    public String toString() {
        return "SyncPrincipalInput[email=" + email + ", name=" + name + ", roles=" + roles + ", active=" + active
                + ", passwordHash=" + (passwordHash == null ? "null" : "***") + "]";
    }
}
