package io.flowcatalyst.server;

import io.flowcatalyst.router.standby.LeaderElection;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// [Router#electionConfig]: the standby [LeaderElection.Config] built from
/// [Env] (`docs/spec/router-env.md` §3, the 2026-09-11 Rust `fc-router`
/// drop-in brief). Same shape as [RouterConfigSourceTest] — a package-private
/// static composition-root method asserted directly rather than through a
/// full [Router#start].
class RouterElectionConfigTest {

    private static Env env(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return Env.load(m);
    }

    @Test
    void standbyDisabledNeedsNoValidConfig() {
        var config = Router.electionConfig(env());
        assertThat(config.enabled()).isFalse();
    }

    @Test
    void defaultsAreUnchanged() {
        var config = Router.electionConfig(env("FC_STANDBY_ENABLED", "true"));

        assertThat(config.enabled()).isTrue();
        // owner ruling 2026-09-11: fc:router:leader; Go still defaults to
        // fc:server:leader (docs/spec/router-env.md §3).
        assertThat(config.lockKey()).isEqualTo("fc:router:leader");
        assertThat(config.lockTtl()).isEqualTo(Duration.ofSeconds(30));
        assertThat(config.heartbeat()).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void everyAliasReachesTheBuiltConfig() {
        var config = Router.electionConfig(env(
                "FC_STANDBY_ENABLED", "true",
                "FC_STANDBY_LOCK_KEY", "k1", "FLOWCATALYST_STANDBY_LOCK_KEY", "k2",
                "FC_STANDBY_LOCK_TTL_SECONDS", "45", "FLOWCATALYST_STANDBY_LOCK_TTL", "99",
                "FC_STANDBY_HEARTBEAT_SECONDS", "5", "FLOWCATALYST_STANDBY_HEARTBEAT_INTERVAL", "20",
                "FC_INSTANCE_ID", "instance-a"));

        assertThat(config.lockKey()).isEqualTo("k1");
        assertThat(config.lockTtl()).isEqualTo(Duration.ofSeconds(45));
        assertThat(config.heartbeat()).isEqualTo(Duration.ofSeconds(5));
        assertThat(config.instanceId()).isEqualTo("instance-a");
    }

    @Test
    void legacyAliasesAloneStillReachTheBuiltConfig() {
        var config = Router.electionConfig(env(
                "FLOWCATALYST_STANDBY_ENABLED", "true",
                "FLOWCATALYST_STANDBY_LOCK_KEY", "legacy-key",
                "FLOWCATALYST_STANDBY_LOCK_TTL", "60",
                "FLOWCATALYST_STANDBY_HEARTBEAT_INTERVAL", "15",
                "FLOWCATALYST_INSTANCE_ID", "legacy-instance"));

        assertThat(config.enabled()).isTrue();
        assertThat(config.lockKey()).isEqualTo("legacy-key");
        assertThat(config.lockTtl()).isEqualTo(Duration.ofSeconds(60));
        assertThat(config.heartbeat()).isEqualTo(Duration.ofSeconds(15));
        assertThat(config.instanceId()).isEqualTo("legacy-instance");
    }

    @Test
    void blankInstanceIdIsDerivedRatherThanUsedLiterally() {
        var config = Router.electionConfig(env("FC_STANDBY_ENABLED", "true"));

        assertThat(config.instanceId()).isNotBlank();
    }

    @Test
    void heartbeatAtOrBeyondTheTtlFailsClearlyNamingBothVariables() {
        // The generic Config constructor already refuses this, but its
        // message names neither variable — this is the wiring that makes it
        // a startup failure an operator can act on instead of a stack trace
        // deep inside LeaderElection.Config's constructor.
        assertThatThrownBy(() -> Router.electionConfig(env(
                "FC_STANDBY_ENABLED", "true",
                "FC_STANDBY_HEARTBEAT_SECONDS", "30",
                "FC_STANDBY_LOCK_TTL_SECONDS", "30")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FC_STANDBY_HEARTBEAT_SECONDS")
                .hasMessageContaining("30s")
                .hasMessageContaining("FC_STANDBY_LOCK_TTL_SECONDS");

        assertThatThrownBy(() -> Router.electionConfig(env(
                "FC_STANDBY_ENABLED", "true",
                "FC_STANDBY_HEARTBEAT_SECONDS", "45",
                "FC_STANDBY_LOCK_TTL_SECONDS", "30")))
                .isInstanceOf(IllegalStateException.class);
    }
}
