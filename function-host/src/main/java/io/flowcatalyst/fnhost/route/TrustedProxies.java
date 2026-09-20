package io.flowcatalyst.fnhost.route;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/// A CIDR allow-list for the public listener's `X-Forwarded-For` trust
/// decision (spec `function-public-routes.md` §3: "the TCP peer is in
/// `FC_FN_TRUSTED_PROXIES`"). Matching compares plain address bytes,
/// IPv4-to-IPv4 or IPv6-to-IPv6 only — no separate IPv4-mapped-IPv6 unwrap
/// step is needed: every `java.net.InetAddress` factory (`getByName`,
/// `getByAddress`) already folds an IPv4-mapped IPv6 literal or byte array
/// (`::ffff:a.b.c.d`) down to a plain 4-byte [java.net.Inet4Address] itself
/// (verified directly — `InetAddress.getByAddress(mappedBytes).getClass()`
/// is `Inet4Address`, not `Inet6Address`), so a genuinely 16-byte mapped
/// candidate can never reach this class through any standard JDK path in
/// the first place.
public final class TrustedProxies {

    /// RFC 1918 + loopback + IPv6 ULA/loopback (spec §3's own default).
    public static final TrustedProxies DEFAULT = of(List.of(
            "127.0.0.0/8", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "::1/128", "fc00::/7"));

    /// No peer is ever trusted — every `X-Forwarded-For` is ignored, `remoteAddress`
    /// is always the TCP peer. A test-only extreme; never the production default.
    public static final TrustedProxies NONE = new TrustedProxies(List.of());

    private final List<Cidr> cidrs;

    private TrustedProxies(List<Cidr> cidrs) {
        this.cidrs = List.copyOf(cidrs);
    }

    /// @throws IllegalArgumentException any entry is not a parseable CIDR
    public static TrustedProxies of(List<String> raw) {
        Objects.requireNonNull(raw, "raw");
        List<Cidr> cidrs = new ArrayList<>();
        for (String entry : raw) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            cidrs.add(Cidr.parse(trimmed));
        }
        return new TrustedProxies(cidrs);
    }

    /// Comma-separated CIDR list (`FC_FN_TRUSTED_PROXIES`'s own wire shape);
    /// blank ⇒ {@link #DEFAULT} — an operator who names the variable but
    /// leaves it empty gets the safe default, not "trust nobody".
    public static TrustedProxies parseCsv(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT;
        }
        return of(List.of(raw.split(",")));
    }

    /// True when `address` falls inside any configured network.
    public boolean isTrusted(InetAddress address) {
        Objects.requireNonNull(address, "address");
        byte[] candidate = address.getAddress();
        for (Cidr cidr : cidrs) {
            if (cidr.matches(candidate)) {
                return true;
            }
        }
        return false;
    }

    /// Same as {@link #isTrusted(InetAddress)} for a bare host string — a TCP
    /// peer or socket address always hands this an IP literal, never a
    /// hostname, so this NEVER performs a DNS lookup: a syntactic pre-check
    /// (never network I/O) rejects anything that is not textually an IPv4 or
    /// IPv6 literal before [InetAddress#getByName] ever runs (which would
    /// otherwise fall through to actual name resolution for a string that
    /// merely looks address-ish, e.g. `999.999.999.999`).
    public boolean isTrusted(String hostAddress) {
        if (hostAddress == null || !isIpLiteral(hostAddress)) {
            return false;
        }
        try {
            return isTrusted(InetAddress.getByName(hostAddress));
        } catch (UnknownHostException e) {
            return false;
        }
    }

    private static final java.util.regex.Pattern IPV4_LITERAL =
            java.util.regex.Pattern.compile("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$");

    /// Syntactic only — never resolves anything, never performs DNS. A
    /// dotted-quad with every octet 0-255, or a string containing a colon
    /// and only hex-digit/colon/dot characters (adequate for an IPv6
    /// literal). Shared by [io.flowcatalyst.fnhost.http.FnHttpServer]'s own
    /// "is the right-most `X-Forwarded-For` entry malformed" check (spec
    /// `function-public-routes.md` §3: "malformed entry ⇒ the peer").
    public static boolean isIpLiteral(String s) {
        var m = IPV4_LITERAL.matcher(s);
        if (m.matches()) {
            for (int i = 1; i <= 4; i++) {
                if (Integer.parseInt(m.group(i)) > 255) {
                    return false;
                }
            }
            return true;
        }
        return s.indexOf(':') >= 0 && s.matches("^[0-9A-Fa-f:.]+$");
    }

    private record Cidr(byte[] network, int prefixLength) {

        static Cidr parse(String raw) {
            int slash = raw.indexOf('/');
            String hostPart = slash < 0 ? raw : raw.substring(0, slash);
            InetAddress addr;
            try {
                addr = InetAddress.getByName(hostPart);
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException("not a valid CIDR/address: '" + raw + "'", e);
            }
            byte[] network = addr.getAddress();
            int maxPrefix = network.length * 8;
            int prefixLength = maxPrefix;
            if (slash >= 0) {
                try {
                    prefixLength = Integer.parseInt(raw.substring(slash + 1).trim());
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("not a valid CIDR prefix length: '" + raw + "'", e);
                }
            }
            if (prefixLength < 0 || prefixLength > maxPrefix) {
                throw new IllegalArgumentException(
                        "prefix length out of range for '" + raw + "' (0.." + maxPrefix + ")");
            }
            return new Cidr(network, prefixLength);
        }

        boolean matches(byte[] candidate) {
            if (candidate.length != network.length) {
                return false;
            }
            int fullBytes = prefixLength / 8;
            int remainingBits = prefixLength % 8;
            for (int i = 0; i < fullBytes; i++) {
                if (candidate[i] != network[i]) {
                    return false;
                }
            }
            if (remainingBits > 0) {
                int mask = (0xFF << (8 - remainingBits)) & 0xFF;
                if ((candidate[fullBytes] & mask) != (network[fullBytes] & mask)) {
                    return false;
                }
            }
            return true;
        }
    }

    @Override
    public String toString() {
        return "TrustedProxies[" + cidrs.size() + " network(s)]";
    }
}
