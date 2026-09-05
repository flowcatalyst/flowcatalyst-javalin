package io.flowcatalyst.platform.identityprovider;

import io.flowcatalyst.platform.shared.CorruptRowException;

/// A row read from `oauth_identity_providers` whose `type` column holds a
/// value [IdentityProviderType#parse] does not recognise (X-06: never a
/// silent default). Carries the offending row's id.
public final class CorruptIdentityProviderException extends CorruptRowException {

    public CorruptIdentityProviderException(String identityProviderId, Throwable cause) {
        super("identity provider", identityProviderId, cause);
    }
}
