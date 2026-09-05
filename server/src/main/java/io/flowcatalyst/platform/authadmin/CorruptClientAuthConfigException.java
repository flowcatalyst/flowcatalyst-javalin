package io.flowcatalyst.platform.authadmin;

import io.flowcatalyst.platform.shared.CorruptRowException;

/// A row read from `tnt_client_auth_configs` whose `config_type` or
/// `auth_provider` column holds a value [ConfigType#parse] / [AuthProvider#parse]
/// does not recognise (spec §2, X-06: never a silent default). Carries the
/// offending row's id so an operator can find it. A list read that hits one
/// corrupt row fails the whole list, not just that row — jOOQ's `.fetch(...)`
/// propagates this naturally, since it is thrown from the per-row mapper.
public final class CorruptClientAuthConfigException extends CorruptRowException {

    private final String clientAuthConfigId;

    public CorruptClientAuthConfigException(String clientAuthConfigId, Throwable cause) {
        super("client auth config " + clientAuthConfigId + " has a corrupt column: " + cause.getMessage(),
                "ClientAuthConfig", clientAuthConfigId, cause);
        this.clientAuthConfigId = clientAuthConfigId;
    }

    public String clientAuthConfigId() {
        return clientAuthConfigId;
    }
}
