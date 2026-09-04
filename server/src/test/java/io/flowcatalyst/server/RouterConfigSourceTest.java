package io.flowcatalyst.server;

import io.flowcatalyst.router.config.RouterConfig;
import io.flowcatalyst.router.config.http.HttpConfigSource;
import io.flowcatalyst.router.observability.Warnings;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// Pins where a running router takes its configuration from
/// (`docs/spec/router.md` §8.4, Go `server/run.go:346`): a config URL wins;
/// otherwise the built-in Postgres broker exists **only** when
/// `FC_DEFAULT_BROKER=postgres`. A production `fc-server` with neither must
/// start with no queues — it must never synthesise a broker on its own.
class RouterConfigSourceTest {

    private static final Warnings NO_WARNINGS = (severity, category, message) -> { };

    private static Env env(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return Env.load(m);
    }

    private static RouterConfig fetched(Env env) {
        return Router.configSource(env, null, NO_WARNINGS).fetch().orElseThrow();
    }

    @Test
    void noConfigUrlAndNoDefaultBrokerStartsNothing() {
        var config = fetched(env("FC_DATABASE_URL", "postgresql://u@db:5432/fc"));
        assertThat(config.queues()).isEmpty();
        assertThat(config.processingPools()).isEmpty();
    }

    @Test
    void defaultBrokerPostgresSynthesisesOneQueueOnTheDatabaseUrl() {
        var config = fetched(env(
                "FC_DEFAULT_BROKER", "postgres",
                "FC_DATABASE_URL", "postgresql://u@db:5432/fc"));
        assertThat(config.queues()).hasSize(1);
        assertThat(config.queues().getFirst().queueUri()).isEqualTo("postgres://u@db:5432/fc");
    }

    @Test
    void defaultBrokerWithoutDatabaseUrlFallsBackToLocalPostgresLikeGo() {
        var config = fetched(env("FC_DEFAULT_BROKER", "postgres"));
        assertThat(config.queues()).hasSize(1);
        assertThat(config.queues().getFirst().queueUri())
                .isEqualTo("postgres://postgres@localhost:5432/flowcatalyst");
    }

    @Test
    void anyOtherDefaultBrokerValueStartsNothing() {
        var config = fetched(env("FC_DEFAULT_BROKER", "sqs", "FC_DATABASE_URL", "postgresql://u@db:5432/fc"));
        assertThat(config.queues()).isEmpty();
    }

    @Test
    void configUrlWinsOverTheDefaultBroker() {
        var source = Router.configSource(env(
                "FLOWCATALYST_CONFIG_URL", "http://config.local/router",
                "FC_DEFAULT_BROKER", "postgres",
                "FC_DATABASE_URL", "postgresql://u@db:5432/fc"), null, NO_WARNINGS);
        assertThat(source).isInstanceOf(HttpConfigSource.class);
    }
}
