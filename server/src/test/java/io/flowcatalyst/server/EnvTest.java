package io.flowcatalyst.server;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

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

        assertThat(env.platformEnabled()).isTrue();
        assertThat(env.routerEnabled()).isFalse();
        assertThat(env.schedulerEnabled()).isFalse();
        assertThat(env.scheduledJobEnabled()).isFalse();
        assertThat(env.streamEnabled()).isFalse();
        assertThat(env.outboxEnabled()).isFalse();
        assertThat(env.mcpEnabled()).isFalse();

        assertThat(env.routerHttpPrefix()).isEqualTo("/router");
        assertThat(env.defaultBroker()).isEmpty();
        assertThat(env.dispatchProcessingEndpoint()).isEqualTo("http://localhost:8080/api/dispatch/process");
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
        assertThat(env.routerDevMode()).isFalse();
        assertThat(env.routerNotifyWebhookUrl()).isEmpty();
        assertThat(env.routerDrainTimeoutSec()).isEqualTo(60);
        assertThat(env.routerAuthMode()).isEmpty();
        assertThat(env.routerAuthUser()).isEmpty();
        assertThat(env.routerAuthPass()).isEmpty();
        assertThat(env.routerAuthEnabled()).isFalse();

        assertThat(env.albEnabled()).isFalse();
        assertThat(env.albTargetGroupArn()).isEmpty();
        assertThat(env.albInstanceIp()).isEmpty();
        assertThat(env.albPort()).isEqualTo(8080);
        assertThat(env.albRegion()).isEmpty();
        assertThat(env.albDeregDelaySec()).isZero();

        assertThat(env.standbyEnabled()).isFalse();
        assertThat(env.standbyRedisUrl()).isEqualTo("redis://127.0.0.1:6379");
        assertThat(env.standbyLockKey()).isEqualTo("fc:server:leader");

        assertThat(env.jwtSigningKeyPath()).isEmpty();
        assertThat(env.jwtPreviousPublicKey()).isEmpty();
        assertThat(env.authAllowTestHeaders()).isFalse();

        assertThat(env.mcpPlatformUrl()).isEmpty();
        assertThat(env.mcpClientId()).isEmpty();
        assertThat(env.mcpClientSecret()).isEmpty();

        assertThat(env.webauthnRpId()).isEqualTo("localhost");
        assertThat(env.webauthnOrigins()).containsExactly("http://localhost:8080");
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

    @Test
    void explicitDispatchEndpointWins() {
        assertThat(load("FC_DISPATCH_PROCESSING_ENDPOINT", "https://fc.example/api/dispatch/process", "FC_API_PORT", "1234")
                .dispatchProcessingEndpoint()).isEqualTo("https://fc.example/api/dispatch/process");
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
                "FLOWCATALYST_CLIENT_ID", "cid", "FLOWCATALYST_CLIENT_SECRET", "sec", "FC_WEBAUTHN_RP_ID", "example.com");
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
}
