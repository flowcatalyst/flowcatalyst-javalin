package io.flowcatalyst.platform.oauthclient;

import io.flowcatalyst.platform.shared.CorruptRowException;

/// A row read from `oauth_clients` whose `client_type` column holds a value
/// [ClientType#parse] does not recognise (X-06: never a silent default —
/// coercing to `PUBLIC`, the less-trusted type, would be a security
/// regression, not just a display bug). Carries the offending row's id.
public final class CorruptOAuthClientException extends CorruptRowException {

    public CorruptOAuthClientException(String rowId, Throwable cause) {
        super("OAuthClient", rowId, cause);
    }
}
