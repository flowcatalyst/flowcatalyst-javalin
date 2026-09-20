package io.flowcatalyst.fcdev.fn;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// [AddressOptions]' XOR/validation rules, exercised through a real `fn`
/// subcommand (`status`) — address resolution happens before ANY network
/// call, so these never need a server: a bad address is caught before
/// [FnCommand#credentials()] is even consulted (`docs/spec/function-developer-surface.md`
/// §2, §4 E4: "two-part address ⇒ exit 2").
class AddressOptionsCliTest {

    @Test
    void twoPartAddressIsAUsageError() {
        var r = FnCliTestSupport.run(Map.of(), "fn", "status", "app.name");
        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains("app.service.name").doesNotContain("Exception");
    }

    @Test
    void fourPartAddressIsAUsageError() {
        var r = FnCliTestSupport.run(Map.of(), "fn", "status", "a.b.c.d");
        assertThat(r.exit()).isEqualTo(2);
    }

    @Test
    void fullAddressAndAppFlagTogetherIsAUsageError() {
        var r = FnCliTestSupport.run(Map.of(), "fn", "status", "app.svc.name", "--app", "other");
        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains("not both");
    }

    @Test
    void appWithoutNameIsAUsageError() {
        var r = FnCliTestSupport.run(Map.of(), "fn", "status", "--app", "myapp");
        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains("--name");
    }

    @Test
    void noAddressAtAllIsAUsageError() {
        var r = FnCliTestSupport.run(Map.of(), "fn", "status");
        assertThat(r.exit()).isEqualTo(2);
    }

    /// `--service` defaults to `default` — proven by resolving all the way
    /// through to a credentials lookup (the next thing after address
    /// resolution), which fails for an unrelated reason (no credentials
    /// configured) — never a usage error, so address resolution succeeded.
    @Test
    void appAndNameDefaultServiceToDefaultAndResolveSucceeds() {
        var r = FnCliTestSupport.run(Map.of(), "fn", "status", "--app", "myapp", "--name", "myfn");
        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("no FlowCatalyst credentials found");
    }
}
