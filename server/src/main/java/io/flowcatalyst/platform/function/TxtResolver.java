package io.flowcatalyst.platform.function;

import java.util.List;

/// Resolves DNS TXT records for domain-verification (spec
/// `function-public-routes.md` §1). A seam: [JndiTxtResolver] is the
/// production implementation (JDK JNDI DNS, no external dependency); tests
/// inject a fake through `FunctionDomainApi.State`'s constructor parameter
/// rather than touching the network.
public interface TxtResolver {

    /// The TXT values found for `name` (e.g. `_flowcatalyst.api.acme.com`),
    /// already de-quoted and with any RFC 1464 multi-chunk value joined into
    /// one string per record — empty when the name resolves but carries no
    /// TXT records.
    ///
    /// @throws DnsException the lookup itself failed (timeout, NXDOMAIN is
    ///                       NOT a failure — it is simply no records, an
    ///                       empty list; a genuine resolver/network problem is)
    List<String> txt(String name) throws DnsException;
}
