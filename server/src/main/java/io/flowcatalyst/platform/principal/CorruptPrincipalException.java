package io.flowcatalyst.platform.principal;

import io.flowcatalyst.platform.shared.CorruptRowException;

/// A row read from `iam_principals` whose `type` or `scope` column holds a
/// value [PrincipalType#parse] / [UserScope#parse] does not recognise
/// (X-06: never a silent default). Carries the offending row's id.
public final class CorruptPrincipalException extends CorruptRowException {

    public CorruptPrincipalException(String principalId, Throwable cause) {
        super("principal", principalId, cause);
    }
}
