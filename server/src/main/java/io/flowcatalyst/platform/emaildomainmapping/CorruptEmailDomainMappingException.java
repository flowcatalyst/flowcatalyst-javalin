package io.flowcatalyst.platform.emaildomainmapping;

import io.flowcatalyst.platform.shared.CorruptRowException;

/// A row read from `tnt_email_domain_mappings` whose `scope_type` column
/// holds a value [ScopeType#parse] does not recognise (X-06: never a silent
/// default — the lenient default here used to be `ANCHOR`, the MOST
/// privileged scope, so a corrupted column would have silently granted
/// platform-staff scope). Carries the offending row's id.
public final class CorruptEmailDomainMappingException extends CorruptRowException {

    public CorruptEmailDomainMappingException(String mappingId, Throwable cause) {
        super("email domain mapping", mappingId, cause);
    }
}
