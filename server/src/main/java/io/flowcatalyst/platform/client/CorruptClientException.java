package io.flowcatalyst.platform.client;

import io.flowcatalyst.platform.shared.CorruptRowException;

/// A row read from `tnt_clients` whose `status` column holds a value
/// [ClientStatus#parse] does not recognise (X-06: never a silent default).
/// Carries the offending row's id.
public final class CorruptClientException extends CorruptRowException {

    public CorruptClientException(String clientId, Throwable cause) {
        super("client", clientId, cause);
    }
}
