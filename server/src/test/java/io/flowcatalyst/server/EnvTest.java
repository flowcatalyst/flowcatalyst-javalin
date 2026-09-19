package io.flowcatalyst.server;

import io.flowcatalyst.platform.function.FunctionLimits;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EnvTest {

    private static Env load(String... kv) {
        var m = new HashMap<String, String>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return Env.load(m);
    }

    @Test
    void defaultsMatchGoLoadEnv() {
        var env = Env.load(Map.of());

        assertThat(env.apiPort()).isEqualTo(8080);
        assertThat(env.metricsPort()).isEqualTo(9090);
        assertThat(env.databaseUrl()).isEqualTo("postgresql://postgres@localhost:5432/flowcatalyst");
        assertThat(env.jwtIssuer()).isEqualTo("http://localhost:8080");
        assertThat(env.jwtAccessTokenTtlSeconds()).isEqualTo(3600L);
        assertThat(env.sessionTtlSeconds()).isEqualTo(86400L);
        assertThat(env.refreshTokenTtlSeconds()).isEqualTo(604800L);

        assertThat(env.platformEnabled()).isTrue();
        assertThat(env.routerEnabled()).isFalse();
        assertThat(env.schedulerEnabled()).isFalse();
        assertThat(env.scheduledJobEnabled()).isFalse();
        assertThat(env.streamEnabled()).isFalse();
        assertThat(env.outboxEnabled()).isFalse();
        assertThat(env.mcpEnabled()).isFalse();
        assertThat(env.exitAfterStart()).as("only ever true in the Dockerfile's AOT training run").isFalse();

        assertThat(env.routerHttpPrefix()).isEqualTo("/router");
        assertThat(env.defaultBroker()).isEmpty();
        assertThat(env.dispatchProcessingEndpoint()).isEqualTo("http://localhost:8080/api/dispatch/process");
        assertThat(env.dispatchQueueType()).isEmpty();
        assertThat(env.dispatchQueueUrl()).isEmpty();
        assertThat(env.dispatchQueueRegion()).isEmpty();
        assertThat(env.dispatchQueuePrefix()).isEmpty();
        assertThat(env.mcpPort()).isEqualTo(8090);
        assertThat(env.mcpBind()).isEqualTo("127.0.0.1");

        assertThat(env.streamEventsEnabled()).isTrue();
        assertThat(env.streamDispatchJobsEnabled()).isTrue();
        assertThat(env.streamFanOutEnabled()).isTrue();
        assertThat(env.streamPartitionsEnabled()).isTrue();
        assertThat(env.streamBatchSize()).isZero();
        assertThat(env.streamFanOutSubsRefreshSecs()).isZero();
        assertThat(env.streamPartitionMonthsForward()).isZero();
        assertThat(env.streamPartitionRetentionDays()).isZero();
        assertThat(env.streamPartitionScheduledJobRetentionDays()).isZero();
        assertThat(env.streamPartitionTickHours()).isZero();

        assertThat(env.outboxPlatformUrl()).isEmpty();
        assertThat(env.outboxPlatformAuthToken()).isEmpty();
        assertThat(env.outboxBatchSize()).isZero();
        assertThat(env.outboxMaxInFlight()).isZero();
        assertThat(env.outboxPollIntervalMs()).isZero();
        assertThat(env.outboxMaxConcurrentGroups()).isZero();
        assertThat(env.outboxBlockOnError()).isTrue();
        assertThat(env.outboxAdminPort()).isZero();
        assertThat(env.outboxBackend()).isEqualTo("postgres");
        assertThat(env.outboxMongoUri()).isEmpty();
        assertThat(env.outboxMongoDb()).isEqualTo("flowcatalyst");

        assertThat(env.routerConfigUrl()).isEmpty();
        assertThat(env.routerConfigIntervalRaw()).isEmpty();
        assertThat(env.routerDevMode()).isFalse();
        assertThat(env.routerNotifyWebhookUrl()).isEmpty();
        assertThat(env.routerNotifyTeamsEnabledRaw()).isEmpty();
        assertThat(env.routerNotifyMinSeverity()).isEqualTo("WARNING");
        assertThat(env.routerNotifyBatchIntervalSeconds()).as("Rust's NotificationConfig::default").isEqualTo(300);
        assertThat(env.routerDrainTimeoutSec()).isEqualTo(60);
        assertThat(env.routerStrictRouting()).as("R-13/R-16: off until every producer is confirmed compliant").isFalse();
        assertThat(env.routerSynthPoolIdleSecs()).as("0 means \"use the implementation default\", not \"never evict\"").isZero();
        assertThat(env.routerAuthMode()).isEmpty();
        assertThat(env.routerAuthUser()).isEmpty();
        assertThat(env.routerAuthPass()).isEmpty();
        assertThat(env.routerAuthEnabled()).isFalse();
        // A-01 gate default: no platform URL, so BLOCK_ON_ERROR siblings are
        // released rather than ACKed (`docs/spec/router-completion.md` §2
        // ruling 3).
        assertThat(env.routerPlatformUrl()).isEmpty();

        assertThat(env.albEnabled()).isFalse();
        assertThat(env.albTargetGroupArn()).isEmpty();
        assertThat(env.albInstanceIp()).isEmpty();
        assertThat(env.albPort()).isEqualTo(8080);
        assertThat(env.albRegion()).isEmpty();
        assertThat(env.albDeregDelaySec()).isZero();

        assertThat(env.standbyEnabled()).isFalse();
        assertThat(env.standbyRedisUrl()).isEqualTo("redis://127.0.0.1:6379");
        // owner ruling 2026-09-11: fc:router:leader; Go still defaults to
        // fc:server:leader (docs/spec/router-env.md §3).
        assertThat(env.standbyLockKey()).isEqualTo("fc:router:leader");
        assertThat(env.standbyLockTtlSeconds()).isEqualTo(30);
        assertThat(env.standbyHeartbeatSeconds()).isEqualTo(10);
        assertThat(env.standbyInstanceId()).isEmpty();

        assertThat(env.jwtSigningKeyPath()).isEmpty();
        assertThat(env.jwtPreviousPublicKey()).isEmpty();
        assertThat(env.authAllowTestHeaders()).isFalse();
        assertThat(env.corsCacheTtlMs()).isEqualTo(30_000L);

        assertThat(env.mcpPlatformUrl()).isEmpty();
        assertThat(env.mcpClientId()).isEmpty();
        assertThat(env.mcpClientSecret()).isEmpty();

        assertThat(env.webauthnRpId()).isEqualTo("localhost");
        assertThat(env.webauthnOrigins()).containsExactly("http://localhost:8080");

        assertThat(env.functionLimits()).isEqualTo(FunctionLimits.defaults());
    }

    /// spec `function-registry.md` §4.6: each `FC_FN_*` var overrides its own
    /// default, and a set-but-non-positive value is a startup error — the
    /// [FunctionLimits] constructor throws, the same way a malformed value
    /// elsewhere in `Env` reaches a value object that refuses to start
    /// (`TokenIssuer.Config`) rather than silently keeping the default.
    @Test
    void functionLimitsOverridesEachVarAndRejectsANonPositiveValue() {
        var env = load(
                "FC_FN_DEFAULT_MAX_DURATION_MS", "1000",
                "FC_FN_DEFAULT_MAX_CONCURRENCY", "5",
                "FC_FN_DEFAULT_WASM_MEMORY_MB", "16",
                "FC_FN_DEFAULT_DB_POOL_SIZE", "2",
                "FC_FN_MAX_WARM_PER_HOST", "50");
        assertThat(env.functionLimits()).isEqualTo(new FunctionLimits(1000, 5, 16, 2, 50));

        // Unparseable falls back to the default, like every other integer var.
        assertThat(load("FC_FN_DEFAULT_MAX_CONCURRENCY", "not-a-number").functionLimits().maxConcurrency())
                .isEqualTo(FunctionLimits.DEFAULT_MAX_CONCURRENCY);

        // Parseable but <= 0 reaches FunctionLimits' constructor and throws —
        // a startup error, not a silent fallback to the default.
        assertThatThrownBy(() -> load("FC_FN_DEFAULT_MAX_CONCURRENCY", "0"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxConcurrency");
        assertThatThrownBy(() -> load("FC_FN_DEFAULT_MAX_DURATION_MS", "-1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxDurationMs");
        assertThatThrownBy(() -> load("FC_FN_DEFAULT_WASM_MEMORY_MB", "0"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("wasmMemoryMb");
        assertThatThrownBy(() -> load("FC_FN_DEFAULT_DB_POOL_SIZE", "0"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("dbPoolSize");
        assertThatThrownBy(() -> load("FC_FN_MAX_WARM_PER_HOST", "0"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxWarmPerHost");
    }

    /// spec `function-api.md` §5.1 step 5, §8 P9: `Env` only PARSES the raw
    /// value — the "off requires dev mode" refusal is
    /// `Signatures#resolve`'s job (`SignaturesTest`), not `Env`'s.
    @Test
    void fnSignaturesModeDefaultsToRequiredAndOnlyOffParsesToOff() {
        assertThat(load().fnSignaturesMode())
                .isEqualTo(io.flowcatalyst.platform.function.artifact.SignaturesMode.REQUIRED);
        assertThat(load("FC_FN_SIGNATURES", "off").fnSignaturesMode())
                .isEqualTo(io.flowcatalyst.platform.function.artifact.SignaturesMode.OFF);
        assertThat(load("FC_FN_SIGNATURES", "required").fnSignaturesMode())
                .isEqualTo(io.flowcatalyst.platform.function.artifact.SignaturesMode.REQUIRED);
        assertThat(load("FC_FN_SIGNATURES", "OFF").fnSignaturesMode()).as("case-insensitive")
                .isEqualTo(io.flowcatalyst.platform.function.artifact.SignaturesMode.OFF);
        assertThat(load("FC_FN_SIGNATURES", "offf").fnSignaturesMode())
                .as("mutant: a typo must never silently mean off")
                .isEqualTo(io.flowcatalyst.platform.function.artifact.SignaturesMode.REQUIRED);
    }

    @Test
    void canonicalNameWinsOverAlias() {
        var env = load(
                "FC_API_PORT", "3000", "PORT", "4000",
                "FC_PLATFORM_ENABLED", "false", "PLATFORM_ENABLED", "true",
                "FC_ROUTER_ENABLED", "true", "MESSAGE_ROUTER_ENABLED", "false",
                "FC_JWT_ISSUER", "https://a", "FC_EXTERNAL_BASE_URL", "https://b", "EXTERNAL_BASE_URL", "https://c",
                "FC_OUTBOX_PLATFORM_URL", "https://p", "FC_OUTBOX_API_URL", "https://q", "FC_API_BASE_URL", "https://r", "FLOWCATALYST_URL", "https://s",
                "FC_OUTBOX_MAX_CONCURRENT_GROUPS", "7", "FC_MAX_CONCURRENT_GROUPS", "8",
                "FC_STANDBY_REDIS_URL", "redis://a", "REDIS_URL", "redis://b",
                "FC_ALB_TARGET_ID", "10.0.0.1", "FC_ALB_INSTANCE_IP", "10.0.0.2",
                "FC_OUTBOX_BACKEND", "mongo", "FC_OUTBOX_DB_TYPE", "postgres",
                "FC_OUTBOX_MONGO_URI", "mongodb://a", "FC_OUTBOX_DB_URL", "mongodb://b",
                "FC_STREAM_PARTITION_MANAGER_ENABLED", "false", "FC_STREAM_PARTITIONS_ENABLED", "true",
                "FC_ROUTER_AUTH_USER", "u1", "AUTH_BASIC_USERNAME", "u2",
                "FC_ROUTER_AUTH_PASS", "p1", "AUTH_BASIC_PASSWORD", "p2",
                "FC_NOTIFY_MIN_SEVERITY", "ERROR", "NOTIFICATION_MIN_SEVERITY", "INFO",
                "FC_WEBAUTHN_ORIGINS", "https://x, https://y,,", "FC_WEBAUTHN_RP_ORIGIN", "https://z");

        assertThat(env.apiPort()).isEqualTo(3000);
        assertThat(env.platformEnabled()).isFalse();
        assertThat(env.routerEnabled()).isTrue();
        assertThat(env.jwtIssuer()).isEqualTo("https://a");
        assertThat(env.outboxPlatformUrl()).isEqualTo("https://p");
        assertThat(env.outboxMaxConcurrentGroups()).isEqualTo(7);
        assertThat(env.standbyRedisUrl()).isEqualTo("redis://a");
        assertThat(env.albInstanceIp()).isEqualTo("10.0.0.1");
        assertThat(env.outboxBackend()).isEqualTo("mongo");
        assertThat(env.outboxMongoUri()).isEqualTo("mongodb://a");
        assertThat(env.streamPartitionsEnabled()).isFalse();
        assertThat(env.routerAuthUser()).isEqualTo("u1");
        assertThat(env.routerAuthPass()).isEqualTo("p1");
        assertThat(env.routerAuthEnabled()).isTrue();
        assertThat(env.routerNotifyMinSeverity()).isEqualTo("ERROR");
        assertThat(env.webauthnOrigins()).containsExactly("https://x", "https://y");
        // FLOWCATALYST_URL also feeds the MCP platform URL
        assertThat(env.mcpPlatformUrl()).isEqualTo("https://s");
    }

    @Test
    void aliasesAreHonouredWhenCanonicalUnset() {
        var env = load(
                "PORT", "4000",
                "PLATFORM_ENABLED", "no",
                "MESSAGE_ROUTER_ENABLED", "yes",
                "DISPATCH_SCHEDULER_ENABLED", "on",
                "SCHEDULED_JOB_SCHEDULER_ENABLED", "1",
                "STREAM_PROCESSOR_ENABLED", "true",
                "OUTBOX_PROCESSOR_ENABLED", "TRUE",
                "STANDBY_ENABLED", "true",
                "EXTERNAL_BASE_URL", "https://c",
                "FC_API_TOKEN", "tok",
                "FC_OUTBOX_DB_TYPE", "mongo",
                "FC_STREAM_PARTITIONS_ENABLED", "off",
                "FC_MAX_CONCURRENT_GROUPS", "3",
                "REDIS_URL", "redis://legacy",
                "FC_ALB_INSTANCE_IP", "10.0.0.2",
                "AUTH_BASIC_USERNAME", "legacy-user",
                "AUTH_BASIC_PASSWORD", "legacy-pass",
                "FC_MCP_PLATFORM_URL", "http://mcp",
                "NOTIFICATION_MIN_SEVERITY", "CRITICAL",
                "FC_WEBAUTHN_RP_ORIGIN", "https://legacy");

        assertThat(env.apiPort()).isEqualTo(4000);
        assertThat(env.dispatchProcessingEndpoint()).isEqualTo("http://localhost:4000/api/dispatch/process");
        assertThat(env.platformEnabled()).isFalse();
        assertThat(env.routerEnabled()).isTrue();
        assertThat(env.schedulerEnabled()).isTrue();
        assertThat(env.scheduledJobEnabled()).isTrue();
        assertThat(env.streamEnabled()).isTrue();
        assertThat(env.outboxEnabled()).isTrue();
        assertThat(env.standbyEnabled()).isTrue();
        assertThat(env.jwtIssuer()).isEqualTo("https://c");
        assertThat(env.outboxPlatformAuthToken()).isEqualTo("tok");
        assertThat(env.outboxBackend()).isEqualTo("mongo");
        assertThat(env.streamPartitionsEnabled()).isFalse();
        assertThat(env.outboxMaxConcurrentGroups()).isEqualTo(3);
        assertThat(env.standbyRedisUrl()).isEqualTo("redis://legacy");
        assertThat(env.albInstanceIp()).isEqualTo("10.0.0.2");
        assertThat(env.routerAuthUser()).isEqualTo("legacy-user");
        assertThat(env.routerAuthPass()).isEqualTo("legacy-pass");
        assertThat(env.mcpPlatformUrl()).isEqualTo("http://mcp");
        assertThat(env.routerNotifyMinSeverity()).isEqualTo("CRITICAL");
        assertThat(env.webauthnOrigins()).containsExactly("https://legacy");
    }

    @Test
    void unparseableValuesFallBackToDefaults() {
        var env = load(
                "FC_API_PORT", "eighty", "PORT", "8181",
                "FC_METRICS_PORT", "",
                "FC_PLATFORM_ENABLED", "maybe", "PLATFORM_ENABLED", "false",
                "FC_OUTBOX_BLOCK_ON_ERROR", "nah",
                "FC_DRAIN_TIMEOUT_SECONDS", "1.5");
        assertThat(env.apiPort()).as("unparseable primary int falls through to the alias").isEqualTo(8181);
        assertThat(env.metricsPort()).isEqualTo(9090);
        assertThat(env.platformEnabled()).as("set-but-unparseable primary bool yields the default, not the alias").isTrue();
        assertThat(env.outboxBlockOnError()).isTrue();
        assertThat(env.routerDrainTimeoutSec()).isEqualTo(60);
    }

    /// `FC_EXIT_AFTER_START` (docs/spec/jvm-memory.md §1a): plain boolean
    /// parse, no alias — pins both that the accepted vocabulary works and
    /// that an unparseable value falls back to the documented default
    /// (false), the same as every other bare `e.bool(...)` field.
    @Test
    void exitAfterStartParsesAsAPlainBooleanWithNoAlias() {
        assertThat(load().exitAfterStart()).isFalse();
        assertThat(load("FC_EXIT_AFTER_START", "true").exitAfterStart()).isTrue();
        assertThat(load("FC_EXIT_AFTER_START", "1").exitAfterStart()).isTrue();
        assertThat(load("FC_EXIT_AFTER_START", "false").exitAfterStart()).isFalse();
        assertThat(load("FC_EXIT_AFTER_START", "not-a-bool").exitAfterStart())
                .as("unparseable falls back to the default, not to some alias").isFalse();
    }

    @Test
    void routerCompletionUnit3Knobs() {
        var env = load("FC_ROUTER_STRICT_ROUTING", "true", "FC_ROUTER_SYNTH_POOL_IDLE_SECS", "1800");

        assertThat(env.routerStrictRouting()).isTrue();
        assertThat(env.routerSynthPoolIdleSecs()).isEqualTo(1800);
    }

    /// The Rust `fc-router` drop-in brief (2026-09-11): `apiPort` is a
    /// three-name chain, `FC_API_PORT` then `API_PORT` then `PORT` — pins the
    /// order explicitly (an alias-order-reversed mutant would swap `API_PORT`
    /// and `PORT` and still pass every other test here, since they never
    /// disagree elsewhere in this file).
    @Test
    void apiPortThreeWayAliasPrecedence() {
        assertThat(load("FC_API_PORT", "3000", "API_PORT", "4000", "PORT", "5000").apiPort()).isEqualTo(3000);
        assertThat(load("API_PORT", "4000", "PORT", "5000").apiPort())
                .as("API_PORT must win over PORT when FC_API_PORT is unset").isEqualTo(4000);
        assertThat(load("PORT", "5000").apiPort()).isEqualTo(5000);
        assertThat(load().apiPort()).isEqualTo(8080);
        assertThat(load("FC_API_PORT", "not-a-number", "API_PORT", "4000").apiPort())
                .as("an unparseable FC_API_PORT falls through to API_PORT").isEqualTo(4000);
        assertThat(load("FC_API_PORT", "not-a-number", "PORT", "5000").apiPort())
                .as("an unparseable FC_API_PORT falls through past an unset API_PORT to PORT").isEqualTo(5000);
    }

    @Test
    void routerNotifyWebhookAndTeamsEnabledAndBatchInterval() {
        assertThat(load("FC_NOTIFY_WEBHOOK_URL", "https://a", "NOTIFICATION_TEAMS_WEBHOOK_URL", "https://b")
                .routerNotifyWebhookUrl()).isEqualTo("https://a");
        assertThat(load("NOTIFICATION_TEAMS_WEBHOOK_URL", "https://b").routerNotifyWebhookUrl())
                .as("legacy Rust name honoured when the canonical one is unset").isEqualTo("https://b");

        assertThat(load().routerNotifyTeamsEnabledRaw()).isEmpty();
        assertThat(load("NOTIFICATION_TEAMS_ENABLED", "false").routerNotifyTeamsEnabledRaw()).isEqualTo("false");

        assertThat(load().routerNotifyBatchIntervalSeconds()).isEqualTo(300);
        assertThat(load("FC_NOTIFY_BATCH_INTERVAL_SECONDS", "60", "NOTIFICATION_BATCH_INTERVAL", "90")
                .routerNotifyBatchIntervalSeconds()).isEqualTo(60);
        assertThat(load("NOTIFICATION_BATCH_INTERVAL", "90").routerNotifyBatchIntervalSeconds()).isEqualTo(90);
        assertThat(load("FC_NOTIFY_BATCH_INTERVAL_SECONDS", "0").routerNotifyBatchIntervalSeconds())
                .as("0 means no batching, not \"unset\"").isZero();
    }

    @Test
    void routerConfigIntervalRawCarriesTheUnparsedValue() {
        // The 300s default and the set-but-invalid WARN both live in
        // RouterServer.parseConfigPollInterval, not here (CONVENTIONS §8) —
        // Env only carries what was actually typed.
        assertThat(load().routerConfigIntervalRaw()).isEmpty();
        assertThat(load("FC_ROUTER_CONFIG_INTERVAL_SECONDS", "60", "FLOWCATALYST_CONFIG_INTERVAL", "90")
                .routerConfigIntervalRaw()).isEqualTo("60");
        assertThat(load("FLOWCATALYST_CONFIG_INTERVAL", "90").routerConfigIntervalRaw()).isEqualTo("90");
    }

    @Test
    void standbyAliasesAndPrecedence() {
        // enabled: FC_STANDBY_ENABLED, then FLOWCATALYST_STANDBY_ENABLED, then STANDBY_ENABLED
        assertThat(load("FC_STANDBY_ENABLED", "false", "FLOWCATALYST_STANDBY_ENABLED", "true").standbyEnabled()).isFalse();
        assertThat(load("FLOWCATALYST_STANDBY_ENABLED", "true", "STANDBY_ENABLED", "false").standbyEnabled()).isTrue();
        assertThat(load("STANDBY_ENABLED", "true").standbyEnabled()).isTrue();

        // redis URL: FC_STANDBY_REDIS_URL, FLOWCATALYST_STANDBY_REDIS_URL, FLOWCATALYST_REDIS_URL, REDIS_URL
        assertThat(load("FC_STANDBY_REDIS_URL", "redis://a", "REDIS_URL", "redis://d").standbyRedisUrl())
                .isEqualTo("redis://a");
        assertThat(load("FLOWCATALYST_STANDBY_REDIS_URL", "redis://b", "REDIS_URL", "redis://d").standbyRedisUrl())
                .isEqualTo("redis://b");
        assertThat(load("FLOWCATALYST_REDIS_URL", "redis://c", "REDIS_URL", "redis://d").standbyRedisUrl())
                .isEqualTo("redis://c");
        assertThat(load("REDIS_URL", "redis://d").standbyRedisUrl()).isEqualTo("redis://d");

        // lock key: FC_STANDBY_LOCK_KEY, then FLOWCATALYST_STANDBY_LOCK_KEY
        assertThat(load("FC_STANDBY_LOCK_KEY", "k1", "FLOWCATALYST_STANDBY_LOCK_KEY", "k2").standbyLockKey())
                .isEqualTo("k1");
        assertThat(load("FLOWCATALYST_STANDBY_LOCK_KEY", "k2").standbyLockKey()).isEqualTo("k2");

        // lock TTL: FC_STANDBY_LOCK_TTL_SECONDS, then FLOWCATALYST_STANDBY_LOCK_TTL
        assertThat(load("FC_STANDBY_LOCK_TTL_SECONDS", "45", "FLOWCATALYST_STANDBY_LOCK_TTL", "99")
                .standbyLockTtlSeconds()).isEqualTo(45);
        assertThat(load("FLOWCATALYST_STANDBY_LOCK_TTL", "99").standbyLockTtlSeconds()).isEqualTo(99);

        // heartbeat: FC_STANDBY_HEARTBEAT_SECONDS, then FLOWCATALYST_STANDBY_HEARTBEAT_INTERVAL
        assertThat(load("FC_STANDBY_HEARTBEAT_SECONDS", "5", "FLOWCATALYST_STANDBY_HEARTBEAT_INTERVAL", "20")
                .standbyHeartbeatSeconds()).isEqualTo(5);
        assertThat(load("FLOWCATALYST_STANDBY_HEARTBEAT_INTERVAL", "20").standbyHeartbeatSeconds()).isEqualTo(20);

        // instance id: FC_INSTANCE_ID, then FLOWCATALYST_INSTANCE_ID, then HOSTNAME
        assertThat(load("FC_INSTANCE_ID", "i1", "FLOWCATALYST_INSTANCE_ID", "i2", "HOSTNAME", "i3").standbyInstanceId())
                .isEqualTo("i1");
        assertThat(load("FLOWCATALYST_INSTANCE_ID", "i2", "HOSTNAME", "i3").standbyInstanceId()).isEqualTo("i2");
        assertThat(load("HOSTNAME", "i3").standbyInstanceId()).isEqualTo("i3");
    }

    @Test
    void explicitDispatchEndpointWins() {
        assertThat(load("FC_DISPATCH_PROCESSING_ENDPOINT", "https://fc.example/api/dispatch/process", "FC_API_PORT", "1234")
                .dispatchProcessingEndpoint()).isEqualTo("https://fc.example/api/dispatch/process");
    }

    @Test
    void dispatchQueueSettingsHonourTheDeployedAliasesWithCanonicalFirst() {
        var env = load("FC_DISPATCH_QUEUE_TYPE", "SQS", "DISPATCH_QUEUE_TYPE", "postgres",
                "FC_DISPATCH_QUEUE_URL", "https://sqs.us-east-1.amazonaws.com/111111111111/fc-canonical",
                "DISPATCH_QUEUE_URL", "https://sqs.us-east-1.amazonaws.com/222222222222/fc-alias",
                "FC_DISPATCH_QUEUE_REGION", "us-east-1", "DISPATCH_QUEUE_REGION", "eu-west-1",
                "FC_DISPATCH_QUEUE_PREFIX", "FC-staging");
        assertThat(env.dispatchQueueType()).isEqualTo("SQS");
        assertThat(env.dispatchQueueUrl()).isEqualTo("https://sqs.us-east-1.amazonaws.com/111111111111/fc-canonical");
        assertThat(env.dispatchQueueRegion()).isEqualTo("us-east-1");
        assertThat(env.dispatchQueuePrefix()).isEqualTo("FC-staging");

        // The deployed names alone, no FC_* set — every one of the three aliased
        // settings must still resolve (FC_DISPATCH_QUEUE_PREFIX has no alias: it
        // is a new name, not yet in the IaC).
        var deployed = load("DISPATCH_QUEUE_TYPE", "postgres",
                "DISPATCH_QUEUE_URL", "https://sqs.us-east-1.amazonaws.com/222222222222/fc-alias",
                "DISPATCH_QUEUE_REGION", "eu-west-1");
        assertThat(deployed.dispatchQueueType()).isEqualTo("postgres");
        assertThat(deployed.dispatchQueueUrl()).isEqualTo("https://sqs.us-east-1.amazonaws.com/222222222222/fc-alias");
        assertThat(deployed.dispatchQueueRegion()).isEqualTo("eu-west-1");
    }

    @Test
    void databaseUrlPrecedence() {
        // 1. full URL wins over everything
        assertThat(load("FC_DATABASE_URL", "postgresql://a/x", "DATABASE_URL", "postgresql://b/y", "DB_HOST", "h").databaseUrl())
                .isEqualTo("postgresql://a/x");
        assertThat(load("DATABASE_URL", "postgresql://b/y", "DB_HOST", "h").databaseUrl())
                .isEqualTo("postgresql://b/y");
        // 2. DB_HOST + DB_*
        assertThat(load("DB_HOST", "db.internal").databaseUrl())
                .isEqualTo("postgresql://postgres@db.internal:5432/flowcatalyst");
        assertThat(load("DB_HOST", "db.internal", "DB_PORT", "6543", "DB_NAME", "fc", "DB_USERNAME", "app").databaseUrl())
                .isEqualTo("postgresql://app@db.internal:6543/fc");
        assertThat(load("DB_HOST", "db.internal:7777", "DB_PORT", "6543").databaseUrl())
                .as("host carrying a port is used as-is").isEqualTo("postgresql://postgres@db.internal:7777/flowcatalyst");
        assertThat(load("DB_HOST", "h", "DB_USERNAME", "u", "DB_PASSWORD", "p@ss w/rd+~*").databaseUrl())
                .as("password is query-escaped like Go's url.QueryEscape")
                .isEqualTo("postgresql://u:p%40ss+w%2Frd%2B~%2A@h:5432/flowcatalyst");
        // 3. default
        assertThat(load("DB_NAME", "ignored-without-host").databaseUrl())
                .isEqualTo("postgresql://postgres@localhost:5432/flowcatalyst");
    }

    @Test
    void queryEscapeMatchesGo() {
        assertThat(Env.queryEscape("abcXYZ019-_.~")).isEqualTo("abcXYZ019-_.~");
        assertThat(Env.queryEscape("a b")).isEqualTo("a+b");
        assertThat(Env.queryEscape("*/?&=:@")).isEqualTo("%2A%2F%3F%26%3D%3A%40");
        assertThat(Env.queryEscape("é")).isEqualTo("%C3%A9");
    }

    @Test
    void routerPlatformUrlIsReadThrough() {
        var env = load("FC_ROUTER_PLATFORM_URL", "https://platform.internal");
        assertThat(env.routerPlatformUrl()).isEqualTo("https://platform.internal");
    }

    /// `docs/spec/router-config-auth.md` §2: both default `""`, both read through verbatim.
    @Test
    void routerClientCredentialsDefaultEmptyAndReadThrough() {
        var defaults = load();
        assertThat(defaults.routerClientId()).isEmpty();
        assertThat(defaults.routerClientSecret()).isEmpty();

        var env = load("FC_ROUTER_CLIENT_ID", "router-client", "FC_ROUTER_CLIENT_SECRET", "s3cr3t");
        assertThat(env.routerClientId()).isEqualTo("router-client");
        assertThat(env.routerClientSecret()).isEqualTo("s3cr3t");
    }

    @Test
    void routerAuthModeNoneForcesAuthOff() {
        var env = load("AUTH_MODE", " none ", "FC_ROUTER_AUTH_USER", "u", "FC_ROUTER_AUTH_PASS", "p");
        assertThat(env.routerAuthMode()).isEqualTo("none");
        assertThat(env.routerAuthUser()).isEmpty();
        assertThat(env.routerAuthPass()).isEmpty();
        assertThat(env.routerAuthEnabled()).isFalse();

        var basic = load("AUTH_MODE", "BASIC", "FC_ROUTER_AUTH_USER", "u", "FC_ROUTER_AUTH_PASS", "p");
        assertThat(basic.routerAuthUser()).isEqualTo("u");
        assertThat(basic.routerAuthEnabled()).isTrue();
    }

    @Test
    void previousPublicKeyIsNormalizedAndDroppedUnlessRealPem() {
        assertThat(load("FLOWCATALYST_JWT_PREVIOUS_PUBLIC_KEY", "not-a-pem").jwtPreviousPublicKey()).isEmpty();
        assertThat(load("FLOWCATALYST_JWT_PREVIOUS_PUBLIC_KEY", "").jwtPreviousPublicKey()).isEmpty();
        var mangled = "\"-----BEGIN PUBLIC KEY-----\\nAAAA\\n-----END PUBLIC KEY-----\"";
        assertThat(load("FLOWCATALYST_JWT_PREVIOUS_PUBLIC_KEY", mangled).jwtPreviousPublicKey())
                .isEqualTo("-----BEGIN PUBLIC KEY-----\nAAAA\n-----END PUBLIC KEY-----");
    }

    @Test
    void streamAndOutboxKnobs() {
        var env = load(
                "FC_STREAM_PROCESSOR_ENABLED", "true",
                "FC_STREAM_EVENTS_ENABLED", "false",
                "FC_STREAM_BATCH_SIZE", "250",
                "FC_STREAM_FAN_OUT_SUBS_REFRESH_SECS", "11",
                "FC_STREAM_PARTITION_MONTHS_FORWARD", "2",
                "FC_STREAM_PARTITION_RETENTION_DAYS", "45",
                "FC_STREAM_PARTITION_RETENTION_DAYS_SCHEDULED_JOBS", "7",
                "FC_STREAM_PARTITION_TICK_HOURS", "6",
                "FC_OUTBOX_BATCH_SIZE", "50", "FC_OUTBOX_MAX_IN_FLIGHT", "500", "FC_OUTBOX_POLL_INTERVAL_MS", "250",
                "FC_OUTBOX_ADMIN_PORT", "9100", "FC_OUTBOX_BLOCK_ON_ERROR", "0", "FC_OUTBOX_MONGO_DB", "other",
                "FC_MCP_ENABLED", "true", "FC_MCP_PORT", "9999", "FC_MCP_BIND", "0.0.0.0",
                "FC_DEFAULT_BROKER", "postgres", "FC_ROUTER_HTTP_PREFIX", "/r",
                "FC_AUTH_ALLOW_TEST_HEADERS", "yes", "FC_JWT_SIGNING_KEY_PATH", "/keys/jwt.pem",
                "FC_STANDBY_LOCK_KEY", "k", "FLOWCATALYST_DEV_MODE", "1", "FLOWCATALYST_CONFIG_URL", "http://cfg",
                "FC_NOTIFY_WEBHOOK_URL", "http://hook", "FC_ALB_ENABLED", "true", "FC_ALB_TARGET_GROUP_ARN", "arn:x",
                "FC_ALB_TARGET_PORT", "81", "FC_ALB_REGION", "eu-west-1", "FC_ALB_DEREGISTRATION_DELAY_SECONDS", "5",
                "FLOWCATALYST_CLIENT_ID", "cid", "FLOWCATALYST_CLIENT_SECRET", "sec", "FC_WEBAUTHN_RP_ID", "example.com",
                "FC_CORS_CACHE_TTL_MS", "5000");
        assertThat(env.streamEnabled()).isTrue();
        assertThat(env.streamEventsEnabled()).isFalse();
        assertThat(env.streamDispatchJobsEnabled()).isTrue();
        assertThat(env.streamBatchSize()).isEqualTo(250);
        assertThat(env.streamFanOutSubsRefreshSecs()).isEqualTo(11);
        assertThat(env.streamPartitionMonthsForward()).isEqualTo(2);
        assertThat(env.streamPartitionRetentionDays()).isEqualTo(45);
        assertThat(env.streamPartitionScheduledJobRetentionDays()).isEqualTo(7);
        assertThat(env.streamPartitionTickHours()).isEqualTo(6);
        assertThat(env.outboxBatchSize()).isEqualTo(50);
        assertThat(env.outboxMaxInFlight()).isEqualTo(500);
        assertThat(env.outboxPollIntervalMs()).isEqualTo(250);
        assertThat(env.outboxAdminPort()).isEqualTo(9100);
        assertThat(env.outboxBlockOnError()).isFalse();
        assertThat(env.outboxMongoDb()).isEqualTo("other");
        assertThat(env.mcpEnabled()).isTrue();
        assertThat(env.mcpPort()).isEqualTo(9999);
        assertThat(env.mcpBind()).isEqualTo("0.0.0.0");
        assertThat(env.defaultBroker()).isEqualTo("postgres");
        assertThat(env.routerHttpPrefix()).isEqualTo("/r");
        assertThat(env.authAllowTestHeaders()).isTrue();
        assertThat(env.jwtSigningKeyPath()).isEqualTo("/keys/jwt.pem");
        assertThat(env.standbyLockKey()).isEqualTo("k");
        assertThat(env.routerDevMode()).isTrue();
        assertThat(env.routerConfigUrl()).isEqualTo("http://cfg");
        assertThat(env.routerNotifyWebhookUrl()).isEqualTo("http://hook");
        assertThat(env.albEnabled()).isTrue();
        assertThat(env.albTargetGroupArn()).isEqualTo("arn:x");
        assertThat(env.albPort()).isEqualTo(81);
        assertThat(env.albRegion()).isEqualTo("eu-west-1");
        assertThat(env.albDeregDelaySec()).isEqualTo(5);
        assertThat(env.mcpClientId()).isEqualTo("cid");
        assertThat(env.mcpClientSecret()).isEqualTo("sec");
        assertThat(env.webauthnRpId()).isEqualTo("example.com");
        assertThat(env.corsCacheTtlMs()).isEqualTo(5000L);
    }

    /// The field-encryption keys are read verbatim with no default, so an
    /// environment loaded from a map (fcdev) configures encryption exactly
    /// like the process environment does.
    @Test
    void appKeysAreReadVerbatimWithNoDefault() {
        assertThat(Env.load(Map.of()).appKey()).as("unset = disabled").isEmpty();
        assertThat(Env.load(Map.of()).appKeyPrevious()).isEmpty();
        var env = load("FLOWCATALYST_APP_KEY", "current-key=", "FLOWCATALYST_APP_KEY_PREVIOUS", "previous-key=");
        assertThat(env.appKey()).isEqualTo("current-key=");
        assertThat(env.appKeyPrevious()).isEqualTo("previous-key=");
    }

    @Test
    void webauthnOriginsDropBlanks() {
        assertThat(Env.webauthnOrigins(new EnvReader(Map.of("FC_WEBAUTHN_ORIGINS", " https://a , ,https://b,")))).isEqualTo(List.of("https://a", "https://b"));
    }

    /// `FC_JWT_ACCESS_TOKEN_TTL_SECS` (Go `wire_services.go`): the one server
    /// knob the cutover env-parity check found unread. The value reaches the
    /// minted `exp` and every `expires_in` through `TokenIssuer.Config`
    /// (A-23: `expires_in` is the configured TTL, never a literal).
    @Test
    void accessTokenTtlIsReadFromTheGoVariable() {
        assertThat(Env.load(Map.of("FC_JWT_ACCESS_TOKEN_TTL_SECS", "120")).jwtAccessTokenTtlSeconds()).isEqualTo(120L);
        assertThat(Env.load(Map.of("FC_JWT_ACCESS_TOKEN_TTL_SECS", "not-a-number")).jwtAccessTokenTtlSeconds()).isEqualTo(3600L);
    }

    /// The four deployed-environment variables the ECS task definitions set
    /// and Java previously ignored (owner ruling 2026-09-11,
    /// `docs/spec/deployed-dispatch.md` §4 / `docs/go-mirror/2026-09-11-deployment-env-handoff.md`):
    /// the `FC_*` canonical name wins when both are set, the alias alone is
    /// honoured, neither set yields the documented default, and an
    /// unparseable canonical value falls through to the alias rather than
    /// straight to the default (`EnvReader#integerAlias`/`#longAlias`
    /// semantics). A mutant that reads only the canonical name, or that
    /// swaps precedence, fails at least one line here.
    @Test
    void accessTokenTtlHonoursTheDeployedOidcAlias() {
        assertThat(Env.load(Map.of("FC_JWT_ACCESS_TOKEN_TTL_SECS", "120", "OIDC_ACCESS_TOKEN_TTL", "999")).jwtAccessTokenTtlSeconds())
                .as("canonical wins over the alias").isEqualTo(120L);
        assertThat(Env.load(Map.of("OIDC_ACCESS_TOKEN_TTL", "1800")).jwtAccessTokenTtlSeconds())
                .as("alias alone is honoured").isEqualTo(1800L);
        assertThat(Env.load(Map.of()).jwtAccessTokenTtlSeconds()).as("neither set: the documented default").isEqualTo(3600L);
        assertThat(Env.load(Map.of("FC_JWT_ACCESS_TOKEN_TTL_SECS", "not-a-number", "OIDC_ACCESS_TOKEN_TTL", "1800")).jwtAccessTokenTtlSeconds())
                .as("unparseable canonical falls through to the alias").isEqualTo(1800L);
    }

    @Test
    void sessionTtlHonoursTheDeployedOidcAliasAndDefaultsToTwentyFourHours() {
        assertThat(Env.load(Map.of("FC_SESSION_TTL_SECS", "3600", "OIDC_SESSION_TTL", "28800")).sessionTtlSeconds())
                .as("canonical wins over the alias").isEqualTo(3600L);
        assertThat(Env.load(Map.of("OIDC_SESSION_TTL", "28800")).sessionTtlSeconds())
                .as("alias alone is honoured — the deployed 8h value").isEqualTo(28800L);
        assertThat(Env.load(Map.of()).sessionTtlSeconds()).as("neither set: today's 24h default").isEqualTo(86400L);
        assertThat(Env.load(Map.of("FC_SESSION_TTL_SECS", "not-a-number", "OIDC_SESSION_TTL", "28800")).sessionTtlSeconds())
                .as("unparseable canonical falls through to the alias").isEqualTo(28800L);
    }

    @Test
    void refreshTokenTtlHonoursTheDeployedOidcAliasAndDefaultsToSevenDays() {
        assertThat(Env.load(Map.of("FC_REFRESH_TOKEN_TTL_SECS", "3600", "OIDC_REFRESH_TOKEN_TTL", "2592000")).refreshTokenTtlSeconds())
                .as("canonical wins over the alias").isEqualTo(3600L);
        assertThat(Env.load(Map.of("OIDC_REFRESH_TOKEN_TTL", "2592000")).refreshTokenTtlSeconds())
                .as("alias alone is honoured — the deployed 30d value").isEqualTo(2592000L);
        assertThat(Env.load(Map.of()).refreshTokenTtlSeconds()).as("neither set: today's 7d default").isEqualTo(604800L);
        assertThat(Env.load(Map.of("FC_REFRESH_TOKEN_TTL_SECS", "not-a-number", "OIDC_REFRESH_TOKEN_TTL", "2592000")).refreshTokenTtlSeconds())
                .as("unparseable canonical falls through to the alias").isEqualTo(2592000L);
    }

    /// Go's `positiveOr` (`internal/server/envcfg.go`) sends a zero or negative
    /// TTL to the default. Java must too, and for two different reasons that
    /// each bite a different variable: a non-positive `OIDC_SESSION_TTL` or
    /// `OIDC_ACCESS_TOKEN_TTL` reaches `TokenIssuer.Config`, which rejects it and
    /// **refuses to start the platform**; a negative `OIDC_REFRESH_TOKEN_TTL`
    /// reaches nothing at all and silently mints refresh tokens that expired
    /// before they were handed out. Neither is what the deployed environment
    /// gets from Go, so neither may be what it gets from Java.
    @Test
    void aNonPositiveTtlFallsBackToTheDefaultRatherThanReachingTheCaller() {
        assertThat(Env.load(Map.of("OIDC_SESSION_TTL", "0")).sessionTtlSeconds())
                .as("zero session TTL would refuse to start; Go uses 24h").isEqualTo(86400L);
        assertThat(Env.load(Map.of("OIDC_SESSION_TTL", "-5")).sessionTtlSeconds())
                .as("negative session TTL would refuse to start; Go uses 24h").isEqualTo(86400L);
        assertThat(Env.load(Map.of("OIDC_REFRESH_TOKEN_TTL", "-5")).refreshTokenTtlSeconds())
                .as("a negative refresh TTL would mint already-expired tokens").isEqualTo(604800L);
        assertThat(Env.load(Map.of("FC_JWT_ACCESS_TOKEN_TTL_SECS", "-5")).jwtAccessTokenTtlSeconds())
                .as("negative access TTL would refuse to start; Go uses 1h").isEqualTo(3600L);
        assertThat(Env.load(Map.of("FC_SESSION_TTL_SECS", "-5", "OIDC_SESSION_TTL", "28800")).sessionTtlSeconds())
                .as("a non-positive canonical takes the default, it does not fall through to the alias — Go's positiveOr(envIntAlias(..)) nests the same way")
                .isEqualTo(86400L);
    }

    @Test
    void dispatchProcessingEndpointHonoursTheDeployedSchedulerAlias() {
        assertThat(Env.load(Map.of("FC_DISPATCH_PROCESSING_ENDPOINT", "http://a", "DISPATCH_SCHEDULER_PROCESSING_ENDPOINT", "http://b"))
                .dispatchProcessingEndpoint()).as("canonical wins over the alias").isEqualTo("http://a");
        assertThat(Env.load(Map.of("DISPATCH_SCHEDULER_PROCESSING_ENDPOINT", "http://fc-platform:8080/api/dispatch/process"))
                .dispatchProcessingEndpoint()).as("alias alone is honoured — the deployed Service Connect name")
                .isEqualTo("http://fc-platform:8080/api/dispatch/process");
        assertThat(Env.load(Map.of()).dispatchProcessingEndpoint())
                .as("neither set: the computed localhost default").isEqualTo("http://localhost:8080/api/dispatch/process");
        // Unlike the numeric TTLs, this pair is string-valued, so `firstSet` decides on
        // "set" not "parses" — there is no unparseable form to fall through from.
    }
}
