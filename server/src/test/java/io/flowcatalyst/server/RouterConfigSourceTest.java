package io.flowcatalyst.server;

import io.flowcatalyst.router.config.RouterConfig;
import io.flowcatalyst.router.config.http.HttpConfigSource;
import io.flowcatalyst.router.observability.Warnings;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Pins where a running router takes its configuration from
/// (`docs/spec/router.md` §8.4, Go `server/run.go:346` — **superseded by R4**,
/// `docs/go-mirror/2026-09-12-dispatch-rulings.md`): a config URL wins;
/// without one, the router starts with no queues, full stop.
/// `FC_DEFAULT_BROKER` no longer changes anything here — R4 removed the
/// fixed single-queue branch entirely, "not just for dev": one code path, as
/// intended. These tests used to pin the OPPOSITE of several of the
/// assertions below (a synthesised default-broker queue); they are rewritten
/// to pin the new contract rather than deleted, per CONVENTIONS' preference
/// for recording a superseded rule rather than silently erasing it.
class RouterConfigSourceTest {

    private static final Warnings NO_WARNINGS = (severity, category, message) -> { };

    private static Env env(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return Env.load(m);
    }

    private static RouterConfig fetched(Env env) {
        return Router.configSource(env, NO_WARNINGS).fetch().orElseThrow();
    }

    @Test
    void noConfigUrlAndNoDefaultBrokerStartsNothing() {
        var config = fetched(env("FC_DATABASE_URL", "postgresql://u@db:5432/fc"));
        assertThat(config.queues()).isEmpty();
        assertThat(config.processingPools()).isEmpty();
    }

    /// **Reversed by R4.** Before this ruling, `FC_DEFAULT_BROKER=postgres`
    /// with no config URL synthesised one queue on the database URL. Mutant
    /// this pins: restoring that branch — a non-empty queue list would then
    /// come back here.
    @Test
    void defaultBrokerPostgresNoLongerSynthesisesAQueue() {
        var config = fetched(env(
                "FC_DEFAULT_BROKER", "postgres",
                "FC_DATABASE_URL", "postgresql://u@db:5432/fc"));
        assertThat(config.queues()).isEmpty();
        assertThat(config.processingPools()).isEmpty();
    }

    /// **Reversed by R4.** Before this ruling, a missing `FC_DATABASE_URL`
    /// with `FC_DEFAULT_BROKER=postgres` fell back to a local Postgres URL
    /// (matching Go) rather than starting with nothing.
    @Test
    void defaultBrokerWithoutDatabaseUrlStillStartsNothing() {
        var config = fetched(env("FC_DEFAULT_BROKER", "postgres"));
        assertThat(config.queues()).isEmpty();
    }

    @Test
    void anyOtherDefaultBrokerValueStartsNothing() {
        var config = fetched(env("FC_DEFAULT_BROKER", "sqs", "FC_DATABASE_URL", "postgresql://u@db:5432/fc"));
        assertThat(config.queues()).isEmpty();
    }

    @Test
    void configUrlWinsRegardlessOfDefaultBroker() {
        var source = Router.configSource(env(
                "FLOWCATALYST_CONFIG_URL", "http://config.local/router",
                "FC_DEFAULT_BROKER", "postgres",
                "FC_DATABASE_URL", "postgresql://u@db:5432/fc"), NO_WARNINGS);
        assertThat(source).isInstanceOf(HttpConfigSource.class);
    }

    /// A config URL is used even with `FC_DEFAULT_BROKER` entirely unset —
    /// R4's "one code path" means the broker setting plays no role at all in
    /// choosing the source any more, only in whether the URL is present.
    @Test
    void configUrlUsedWithNoDefaultBrokerSet() {
        var source = Router.configSource(env("FLOWCATALYST_CONFIG_URL", "http://config.local/router"), NO_WARNINGS);
        assertThat(source).isInstanceOf(HttpConfigSource.class);
    }

    // ── Client-credentials pairing (`docs/spec/router-config-auth.md` §2) ──

    /// `FC_ROUTER_CLIENT_ID` without `FC_ROUTER_CLIENT_SECRET` (both paired
    /// with a platform URL) is refused loudly rather than silently fetching
    /// unauthenticated. A mutant that dropped either half of the pairing
    /// check (e.g. only checking `hasId`) would let this construct a
    /// working, credential-less source instead of throwing.
    @Test
    void clientIdWithoutSecretIsRefused() {
        var env = env(
                "FLOWCATALYST_CONFIG_URL", "http://config.local/router",
                "FC_ROUTER_PLATFORM_URL", "http://config.local",
                "FC_ROUTER_CLIENT_ID", "router-client");
        assertThatThrownBy(() -> Router.configSource(env, NO_WARNINGS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FC_ROUTER_CLIENT_ID").hasMessageContaining("FC_ROUTER_CLIENT_SECRET");
    }

    /// The other half of the pairing.
    @Test
    void clientSecretWithoutIdIsRefused() {
        var env = env(
                "FLOWCATALYST_CONFIG_URL", "http://config.local/router",
                "FC_ROUTER_PLATFORM_URL", "http://config.local",
                "FC_ROUTER_CLIENT_SECRET", "shh");
        assertThatThrownBy(() -> Router.configSource(env, NO_WARNINGS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FC_ROUTER_CLIENT_ID").hasMessageContaining("FC_ROUTER_CLIENT_SECRET");
    }

    /// Both credentials set, but no `FC_ROUTER_PLATFORM_URL` to mint against
    /// (§2: "credentials set without it are refused at the same place") — a
    /// mutant that skipped this check would silently build a `TokenManager`
    /// pointed at an empty base URL instead of refusing at startup.
    @Test
    void credentialsWithoutPlatformUrlAreRefused() {
        var env = env(
                "FLOWCATALYST_CONFIG_URL", "http://config.local/router",
                "FC_ROUTER_CLIENT_ID", "router-client",
                "FC_ROUTER_CLIENT_SECRET", "shh");
        assertThatThrownBy(() -> Router.configSource(env, NO_WARNINGS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FC_ROUTER_PLATFORM_URL");
    }

    /// Neither credential set: no refusal, and the source built is the same
    /// unauthenticated `HttpConfigSource` as before — the router's default,
    /// zero-config path must not regress.
    @Test
    void noCredentialsAtAllBuildsAnOrdinarySource() {
        var source = Router.configSource(env(
                "FLOWCATALYST_CONFIG_URL", "http://config.local/router",
                "FC_ROUTER_PLATFORM_URL", "http://config.local"), NO_WARNINGS);
        assertThat(source).isInstanceOf(HttpConfigSource.class);
    }

    /// Both credentials set together, with a platform URL: no refusal.
    @Test
    void bothCredentialsWithPlatformUrlAreAccepted() {
        var source = Router.configSource(env(
                "FLOWCATALYST_CONFIG_URL", "http://config.local/router",
                "FC_ROUTER_PLATFORM_URL", "http://config.local",
                "FC_ROUTER_CLIENT_ID", "router-client",
                "FC_ROUTER_CLIENT_SECRET", "shh"), NO_WARNINGS);
        assertThat(source).isInstanceOf(HttpConfigSource.class);
    }
}
