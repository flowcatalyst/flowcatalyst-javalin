package io.flowcatalyst.platform.function;

/// A DNS lookup could not be completed (timeout, resolver unavailable, a
/// malformed response) — thrown by [TxtResolver#txt] (spec
/// `function-public-routes.md` §1). Checked deliberately: a caller MUST
/// decide what "the DNS system itself is unavailable" means for it (the
/// verify route answers `503 DNS_UNAVAILABLE`, never treating a resolver
/// failure as "not verified" — spec §6 M1).
public class DnsException extends Exception {

    public DnsException(String message, Throwable cause) {
        super(message, cause);
    }

    public DnsException(String message) {
        super(message);
    }
}
