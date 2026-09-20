package io.flowcatalyst.platform.function.operations;

import io.flowcatalyst.platform.function.DnsException;

/// `VerifyFunctionDomain`'s wrapper around a [DnsException] the resolver
/// threw — unchecked so it can cross `Operation.Execute`'s functional
/// interface, and a distinct type (never a plain [RuntimeException]) so
/// `FunctionDomainApi`'s verify handler can catch it specifically and answer
/// `503 DNS_UNAVAILABLE`, the same "let the failure propagate to a specific
/// catch at the API layer" shape `BackoffCheck`'s `503 BACKOFF_UNAVAILABLE`
/// uses (spec `function-public-routes.md` §1, §6 M1: "resolver failure is
/// 503, not 'not verified'" — this type is how that failure is told apart
/// from an ordinary UseCaseException, whose kinds do not include 503).
public final class DnsUnavailableException extends RuntimeException {

    public DnsUnavailableException(DnsException cause) {
        super(cause.getMessage(), cause);
    }
}
