package io.flowcatalyst.platform.function;

import javax.naming.NameNotFoundException;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// The production [TxtResolver]: JDK JNDI DNS (`com.sun.jndi.dns`, part of
/// the JDK — no external dependency), 5 second timeout (spec
/// `function-public-routes.md` §1).
///
/// A name that simply does not exist ([NameNotFoundException]) reads as "no
/// TXT records" — an empty list, not a failure: a hostname whose owner has
/// not published the verification record yet is the ordinary "not verified"
/// case, handled by `VerifyFunctionDomain` finding no matching value. Any
/// OTHER [NamingException] (timeout, `ServiceUnavailableException`,
/// `CommunicationException`, a malformed response) is the DNS system itself
/// being unavailable — [DnsException], mapped to `503 DNS_UNAVAILABLE` by
/// the API layer (spec §1, §6 M1: "resolver failure is 503, not 'not
/// verified'").
public final class JndiTxtResolver implements TxtResolver {

    private static final String TIMEOUT_MS = "5000";
    private static final Pattern QUOTED_CHUNK = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");

    @Override
    public List<String> txt(String name) throws DnsException {
        Hashtable<String, Object> env = new Hashtable<>();
        env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
        env.put("com.sun.jndi.dns.timeout.initial", TIMEOUT_MS);
        env.put("com.sun.jndi.dns.timeout.retries", "1");

        DirContext ctx;
        try {
            ctx = new InitialDirContext(env);
        } catch (NamingException e) {
            throw new DnsException("could not initialise the DNS resolver", e);
        }
        try {
            Attributes attrs;
            try {
                attrs = ctx.getAttributes(name, new String[] {"TXT"});
            } catch (NameNotFoundException e) {
                return List.of();
            } catch (NamingException e) {
                throw new DnsException("TXT lookup failed for '" + name + "'", e);
            }
            Attribute txt = attrs.get("TXT");
            if (txt == null) {
                return List.of();
            }
            List<String> values = new ArrayList<>();
            try {
                NamingEnumeration<?> all = txt.getAll();
                try {
                    while (all.hasMore()) {
                        Object value = all.next();
                        if (value != null) {
                            values.add(joinChunks(value.toString()));
                        }
                    }
                } finally {
                    all.close();
                }
            } catch (NamingException e) {
                throw new DnsException("TXT lookup failed for '" + name + "'", e);
            }
            return List.copyOf(values);
        } finally {
            try {
                ctx.close();
            } catch (NamingException ignored) {
                // closing a resolver context is best-effort
            }
        }
    }

    /// A TXT record's value may be split into several RFC 1035
    /// character-strings; JNDI's DNS provider hands back the raw record text
    /// with each chunk quoted (e.g. `"fc-verify=abc" "def"`) when there is
    /// more than one, or a bare unquoted string when there is exactly one.
    /// Strips the quoting and concatenates every chunk, in order, into one
    /// value — the verification token is never split across chunk
    /// boundaries by this joining.
    static String joinChunks(String raw) {
        if (!raw.contains("\"")) {
            return raw;
        }
        Matcher m = QUOTED_CHUNK.matcher(raw);
        StringBuilder joined = new StringBuilder();
        boolean any = false;
        while (m.find()) {
            any = true;
            joined.append(m.group(1).replace("\\\"", "\""));
        }
        return any ? joined.toString() : raw;
    }
}
