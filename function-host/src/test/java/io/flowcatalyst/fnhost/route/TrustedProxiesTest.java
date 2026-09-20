package io.flowcatalyst.fnhost.route;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.InetAddress;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// `function-public-routes.md` §3's CIDR matcher — a pinned accept/reject
/// table (task brief), including IPv4-mapped IPv6.
class TrustedProxiesTest {

    @ParameterizedTest(name = "{0} in {1} -> {2}")
    @CsvSource({
            "10.1.2.3,        10.0.0.0/8,      true",
            "9.255.255.255,   10.0.0.0/8,      false",
            "11.0.0.0,        10.0.0.0/8,      false",
            "172.16.5.5,      172.16.0.0/12,   true",
            "172.32.0.1,      172.16.0.0/12,   false",
            "192.168.1.1,     192.168.0.0/16,  true",
            "192.169.0.1,     192.168.0.0/16,  false",
            "127.0.0.1,       127.0.0.0/8,     true",
            "8.8.8.8,         127.0.0.0/8,     false",
    })
    void pinnedIpv4AcceptRejectTable(String candidate, String cidr, boolean expected) throws Exception {
        TrustedProxies proxies = TrustedProxies.of(List.of(cidr));
        assertThat(proxies.isTrusted(InetAddress.getByName(candidate))).isEqualTo(expected);
    }

    @Test
    void ipv6LoopbackMatchesItsOwnCidr() throws Exception {
        TrustedProxies proxies = TrustedProxies.of(List.of("::1/128"));
        assertThat(proxies.isTrusted(InetAddress.getByName("::1"))).isTrue();
        assertThat(proxies.isTrusted(InetAddress.getByName("::2"))).isFalse();
    }

    @Test
    void ipv6UlaCidrMatchesAPrefix() throws Exception {
        TrustedProxies proxies = TrustedProxies.of(List.of("fc00::/7"));
        assertThat(proxies.isTrusted(InetAddress.getByName("fd00::1"))).isTrue();
        assertThat(proxies.isTrusted(InetAddress.getByName("2001:db8::1"))).isFalse();
    }

    /// An IPv4-mapped IPv6 LITERAL (`::ffff:10.1.2.3`) still matches a plain
    /// IPv4 CIDR — not because this class unwraps anything itself, but
    /// because `InetAddress.getByName`/`getByAddress` fold a mapped address
    /// down to a plain [java.net.Inet4Address] BEFORE this class ever sees
    /// it (verified directly below): every standard JDK path a real
    /// `remoteAddress` could arrive through already hands this class 4
    /// bytes for an IPv4 peer, so there is no separate "unwrap" code path
    /// left to pin here — pinning one anyway would be decorative (it would
    /// pass whether or not an unwrap step existed).
    @Test
    void ipv4MappedIpv6LiteralMatchesAnIpv4Cidr() throws Exception {
        TrustedProxies proxies = TrustedProxies.of(List.of("10.0.0.0/8"));
        assertThat(proxies.isTrusted(InetAddress.getByName("::ffff:10.1.2.3"))).isTrue();
        assertThat(proxies.isTrusted(InetAddress.getByName("::ffff:11.1.2.3"))).isFalse();
    }

    /// The JDK behaviour the above test (and this class's whole design)
    /// relies on, pinned directly: neither `getByName` on a mapped literal
    /// nor `getByAddress` on genuinely 16 mapped bytes ever yields a real
    /// 16-byte `Inet6Address` — both fold to `Inet4Address`. If a future JDK
    /// changed this, `TrustedProxies` would need its own unwrap step again;
    /// this test would be the one to catch that silently changing.
    @Test
    void jdkFoldsIpv4MappedAddressesToInet4AddressAlways() throws Exception {
        InetAddress fromLiteral = InetAddress.getByName("::ffff:10.1.2.3");
        assertThat(fromLiteral).isInstanceOf(java.net.Inet4Address.class);
        assertThat(fromLiteral.getAddress()).hasSize(4);

        byte[] mappedBytes = new byte[16];
        mappedBytes[10] = (byte) 0xFF;
        mappedBytes[11] = (byte) 0xFF;
        mappedBytes[12] = 10;
        mappedBytes[13] = 1;
        mappedBytes[14] = 2;
        mappedBytes[15] = 3;
        InetAddress fromBytes = InetAddress.getByAddress(mappedBytes);
        assertThat(fromBytes).isInstanceOf(java.net.Inet4Address.class);
        assertThat(fromBytes.getAddress()).hasSize(4);
    }

    @Test
    void defaultCoversRfc1918LoopbackAndUla() throws Exception {
        TrustedProxies d = TrustedProxies.DEFAULT;
        assertThat(d.isTrusted(InetAddress.getByName("10.0.0.1"))).isTrue();
        assertThat(d.isTrusted(InetAddress.getByName("172.16.0.1"))).isTrue();
        assertThat(d.isTrusted(InetAddress.getByName("192.168.0.1"))).isTrue();
        assertThat(d.isTrusted(InetAddress.getByName("127.0.0.1"))).isTrue();
        assertThat(d.isTrusted(InetAddress.getByName("::1"))).isTrue();
        assertThat(d.isTrusted(InetAddress.getByName("fd12::1"))).isTrue();
        assertThat(d.isTrusted(InetAddress.getByName("203.0.113.7"))).as("a real internet address is never trusted by default")
                .isFalse();
    }

    @Test
    void blankCsvFallsBackToDefault() {
        assertThat(TrustedProxies.parseCsv("").isTrusted("10.0.0.1")).isTrue();
        assertThat(TrustedProxies.parseCsv(null).isTrusted("10.0.0.1")).isTrue();
    }

    @Test
    void csvOfMultipleCidrs() {
        TrustedProxies p = TrustedProxies.parseCsv("192.0.2.0/24, 198.51.100.5/32");
        assertThat(p.isTrusted("192.0.2.17")).isTrue();
        assertThat(p.isTrusted("198.51.100.5")).isTrue();
        assertThat(p.isTrusted("198.51.100.6")).isFalse();
    }

    @Test
    void unparseableCidrThrowsAtConstruction() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> TrustedProxies.of(List.of("not-a-cidr")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void noneTrustsNobody() throws Exception {
        assertThat(TrustedProxies.NONE.isTrusted(InetAddress.getByName("10.0.0.1"))).isFalse();
    }

    @Test
    void malformedHostStringIsNeverTrusted() {
        assertThat(TrustedProxies.DEFAULT.isTrusted("not-an-ip-address")).isFalse();
    }
}
