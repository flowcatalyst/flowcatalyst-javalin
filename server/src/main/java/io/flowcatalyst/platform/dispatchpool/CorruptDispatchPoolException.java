package io.flowcatalyst.platform.dispatchpool;

import io.flowcatalyst.platform.shared.CorruptRowException;

/// A row read from `msg_dispatch_pools` whose `status` column holds a value
/// [DispatchPoolStatus#parse] does not recognise (X-06: never a silent
/// default). Carries the offending row's id.
public final class CorruptDispatchPoolException extends CorruptRowException {

    public CorruptDispatchPoolException(String dispatchPoolId, Throwable cause) {
        super("dispatch pool", dispatchPoolId, cause);
    }
}
