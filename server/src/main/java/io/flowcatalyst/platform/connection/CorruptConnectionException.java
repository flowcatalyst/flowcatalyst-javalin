package io.flowcatalyst.platform.connection;

import io.flowcatalyst.platform.shared.CorruptRowException;

/// A row read from `msg_connections` whose `status` or `source` column holds
/// a value [ConnectionStatus#parse] / [ConnectionSource#parse] does not
/// recognise (X-06: never a silent default). Carries the offending row's id.
public final class CorruptConnectionException extends CorruptRowException {

    public CorruptConnectionException(String connectionId, Throwable cause) {
        super("connection", connectionId, cause);
    }
}
