package io.flowcatalyst.platform.function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// `PoolUrlTemplate` — `FC_FN_POOL_URL` (spec `function-invocation.md` §4,
/// R8): `{pool}` optional, at most one occurrence, an absolute http/https
/// URL with no userinfo, no path beyond an optional trailing slash (stripped
/// at resolve time), no query, no fragment.
class PoolUrlTemplateTest {

    // ── accept table ─────────────────────────────────────────────────────

    static Stream<Arguments> accepted() {
        return Stream.of(
                Arguments.of("default shape, {pool} present", "http://fn-{pool}:8080"),
                Arguments.of("https scheme", "https://fn-{pool}:8443"),
                Arguments.of("{pool} absent — single-pool/fcdev", "http://127.0.0.1:8080"),
                Arguments.of("{pool} absent, https", "https://fn-pool.internal:8080"),
                Arguments.of("root path only", "http://fn-{pool}:8080/"),
                Arguments.of("{pool} in the host label, no port", "http://fn-{pool}.internal"),
                Arguments.of("{pool} is the whole host", "http://{pool}:8080")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("accepted")
    void accepts(String rule, String raw) {
        PoolUrlTemplate template = PoolUrlTemplate.parse(raw);
        assertThat(template.template()).as(rule).isEqualTo(raw);
    }

    // ── reject table — each row names the rule it pins ──────────────────

    static Stream<Arguments> rejected() {
        return Stream.of(
                Arguments.of("userinfo — the old {pool}-as-userinfo hack", "http://{pool}@127.0.0.1:8080"),
                Arguments.of("userinfo with no placeholder at all", "http://admin@fn-host:8080"),
                Arguments.of("two placeholders", "http://{pool}-{pool}:8080"),
                Arguments.of("two placeholders in different segments", "http://fn-{pool}:8080/{pool}"),
                Arguments.of("a path beyond root", "http://fn-{pool}:8080/base"),
                Arguments.of("a path with no placeholder", "http://fn-host:8080/base"),
                Arguments.of("a query string", "http://fn-{pool}:8080?x=1"),
                Arguments.of("a fragment", "http://fn-{pool}:8080#frag"),
                Arguments.of("not absolute — no scheme", "//fn-{pool}:8080"),
                Arguments.of("not http/https", "ftp://fn-{pool}:8080"),
                Arguments.of("not a URL at all", "not a url"),
                Arguments.of("blank", "")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejected")
    void rejects(String rule, String raw) {
        assertThatThrownBy(() -> PoolUrlTemplate.parse(raw))
                .as(rule)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FC_FN_POOL_URL");
    }

    // ── resolve: substitution + trailing-slash strip ────────────────────

    @Test
    void resolveSubstitutesThePlaceholderAndStripsATrailingSlash() {
        PoolUrlTemplate template = PoolUrlTemplate.parse("http://fn-{pool}:8080/");
        assertThat(template.resolve(new DnsLabel("orders")))
                .as("mutant: leave the trailing slash — the caller string-concatenates '/functions/…' directly")
                .isEqualTo("http://fn-orders:8080");
    }

    @Test
    void resolveWithNoPlaceholderIgnoresThePool() {
        PoolUrlTemplate template = PoolUrlTemplate.parse("http://127.0.0.1:9090");
        assertThat(template.resolve(new DnsLabel("orders")))
                .as("mutant: require {pool} to resolve at all — a single-pool/fcdev template names the host directly")
                .isEqualTo("http://127.0.0.1:9090");
        assertThat(template.resolve(new DnsLabel("billing")))
                .as("the same template resolves identically for every pool when it names no placeholder")
                .isEqualTo("http://127.0.0.1:9090");
    }

    @Test
    void resolveWithNoTrailingSlashIsUnchangedBeyondSubstitution() {
        PoolUrlTemplate template = PoolUrlTemplate.parse("http://fn-{pool}:8080");
        assertThat(template.resolve(new DnsLabel("orders"))).isEqualTo("http://fn-orders:8080");
    }

    // ── errors name FC_FN_POOL_URL ───────────────────────────────────────

    @Test
    void everyRejectionNamesTheEnvironmentVariable() {
        assertThatThrownBy(() -> PoolUrlTemplate.parse("http://{pool}@host:8080"))
                .as("mutant: an error message that does not name FC_FN_POOL_URL leaves an operator guessing")
                .hasMessageContaining("FC_FN_POOL_URL");
    }
}
