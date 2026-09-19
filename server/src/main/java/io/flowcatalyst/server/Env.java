package io.flowcatalyst.server;

import io.flowcatalyst.platform.function.FunctionLimits;
import io.flowcatalyst.platform.function.artifact.SignaturesMode;
import io.flowcatalyst.platform.shared.auth.SigningKeys;
import io.flowcatalyst.platform.shared.encryption.Encryption;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/// Every env-driven knob the server reads, resolved once at boot — the Java
/// reading of `EnvCfg` / `LoadEnv()` in `internal/server/envcfg.go`, plus the
/// three router-auth variables `internal/server/run.go` resolves and the two
/// WebAuthn variables read in `envcfg.go` / `wire_services.go`.
///
/// Variable names, legacy aliases, defaults and precedence are kept exactly:
/// the `FC_*` name is canonical, the alias exists so an existing deployment's
/// task definition drops in unchanged. Booleans accept `1/true/yes/on` and
/// `0/false/no/off`; unparseable numbers and booleans silently fall back to
/// the default. The full reference is `docs/environment-variables.md` in the
/// Go repository.
///
/// Load from the process environment with [#load()] or from any map (tests,
/// [DotEnv]) with [#load(Map)].
///
/// Not here, on purpose: the JWT signing-key material itself
/// ([io.flowcatalyst.platform.shared.auth.SigningKeys] reads the inline PEM
/// variables), the log level/format ([Logging]), and the per-package
/// `FromEnv`-style knobs (email, rate limiting, login backoff) which belong
/// to their own subsystems — the connection-pool sizing knobs among them:
/// `FC_DB_POOL_SIZE` (the budget `B`, default 32) and its four per-group
/// overrides `FC_DB_POOL_SIZE_API` / `_BFF` / `_DISPATCH` / `_BACKGROUND`
/// are read directly by [io.flowcatalyst.platform.shared.database.Pools#open]
/// (`docs/spec/admission.md` §11.7), not through this record. The
/// field-encryption keys *are* here
/// ([#appKey()] / [#appKeyPrevious()]) so that every way of loading the
/// environment — the process, a `.env` file, fcdev's map — reaches the
/// encryption service the same way. The scheduled-job scheduler's own
/// cadence knobs are here too (`FC_SCHEDULED_JOB_*`), read once and passed
/// as values through `ScheduledJobScheduler.Settings.fromEnv`, mirroring how
/// [io.flowcatalyst.stream.StreamProcessor.Settings#fromEnv] takes an [Env].
public record Env(
        // ── listeners ──────────────────────────────────────────────────────
        // `FC_API_PORT`, then `API_PORT`, then `PORT`, default 8080: the
        // unified API listener (the Rust `fc-router` drop-in reads the same
        // two-name chain plus this Java-only `FC_API_PORT` canonical first).
        int apiPort,
        // `FC_METRICS_PORT`, default 9090: Prometheus listener.
        int metricsPort,

        // ── HTTP/2 (h2c/h2) and HTTP/3 (QUIC): docs/spec/http-transport.md ───
        // `FC_TLS_PORT`, default 8443: TLS 1.2/1.3 with ALPN -> h2, http/1.1.
        // The listener only binds when TLS material (below) is configured.
        int tlsPort,
        // `FC_TLS_KEYSTORE_PATH`: a PKCS#12 keystore holding one key entry
        // with its chain. Pairs with [#tlsKeystorePassword]; set one or
        // neither, never one without the other.
        String tlsKeystorePath,
        // `FC_TLS_KEYSTORE_PASSWORD`: the PKCS#12 keystore's password.
        String tlsKeystorePassword,
        // `FC_TLS_CERT_PATH`: a leaf-first PEM certificate chain. Pairs with
        // [#tlsKeyPath]. Mutually exclusive with the keystore pair above —
        // exactly one form, or neither, may be set (`TlsMaterial#resolve`).
        String tlsCertPath,
        // `FC_TLS_KEY_PATH`: an unencrypted PKCS#8 PEM private key.
        String tlsKeyPath,
        // `FC_HTTP3_ENABLED`, default false: QUIC -> h3. A startup error
        // without TLS material (`TlsMaterial#resolve`).
        boolean http3Enabled,
        // `FC_HTTP3_PORT` (UDP), default = [#tlsPort]: resolved from the
        // already-resolved tlsPort, not a separate literal default.
        int http3Port,

        // ── database / identity ────────────────────────────────────────────
        // See [#resolveDatabaseUrl(EnvReader)] for the three-mode precedence.
        String databaseUrl,
        // `FC_JWT_ISSUER` (aliases `FC_EXTERNAL_BASE_URL`, `EXTERNAL_BASE_URL`),
        // default `http://localhost:8080`: JWT issuer/audience and external base URL.
        String jwtIssuer,
        // `FC_JWT_ACCESS_TOKEN_TTL_SECS` (alias `OIDC_ACCESS_TOKEN_TTL`), default
        // 3600: the access-token lifetime — sets the minted `exp` and the
        // advertised `expires_in` together (A-23). Zero or negative refuses to
        // start (TokenIssuer.Config).
        long jwtAccessTokenTtlSeconds,
        // `FC_SESSION_TTL_SECS` (new; alias `OIDC_SESSION_TTL`), default 86400
        // (24h): the session cookie's `Max-Age` and the session JWT's `exp`,
        // kept equal (owner ruling 2026-09-11 supersedes C-Q16's "session TTL
        // is compile-time" — see `docs/spec/deployed-dispatch.md` §4). Zero or
        // negative refuses to start (TokenIssuer.Config).
        long sessionTtlSeconds,
        // `FC_REFRESH_TOKEN_TTL_SECS` (new; alias `OIDC_REFRESH_TOKEN_TTL`),
        // default 604800 (7d): the refresh token's lifetime at issuance (owner
        // ruling 2026-09-11 supersedes C-Q16's "7 days is the refresh family's
        // absolute cap" — see `docs/spec/deployed-dispatch.md` §4). Rotation
        // still never extends a family past its original expiry, and the
        // `GrantStore` hydration fallback for legacy null-`expires_at` rows
        // deliberately keeps the historical 7-day constant (`GrantStore.java`).
        long refreshTokenTtlSeconds,

        // ── subsystem toggles ──────────────────────────────────────────────
        // `FC_PLATFORM_ENABLED` (alias `PLATFORM_ENABLED`), default true.
        boolean platformEnabled,
        // `FC_ROUTER_ENABLED` (alias `MESSAGE_ROUTER_ENABLED`), default false.
        boolean routerEnabled,
        // `FC_SCHEDULER_ENABLED` (alias `DISPATCH_SCHEDULER_ENABLED`), default false.
        boolean schedulerEnabled,
        // `FC_SCHEDULED_JOB_ENABLED` (alias `SCHEDULED_JOB_SCHEDULER_ENABLED`), default false.
        boolean scheduledJobEnabled,
        // `FC_STREAM_PROCESSOR_ENABLED` (alias `STREAM_PROCESSOR_ENABLED`), default false.
        boolean streamEnabled,
        // `FC_OUTBOX_ENABLED` (alias `OUTBOX_PROCESSOR_ENABLED`), default false.
        boolean outboxEnabled,
        // `FC_MCP_ENABLED`, default false.
        boolean mcpEnabled,
        // `FC_EXIT_AFTER_START`, default false: build-time only, the image's
        // AOT training run (`docs/spec/jvm-memory.md` §1a). When true,
        // [Main#main] starts every enabled subsystem exactly as normal, logs
        // `training run complete` once the server is up, then stops it and
        // returns — giving JEP 514's one-step AOT training a clean process
        // exit instead of a server that runs forever. Never set outside the
        // Dockerfile's training `RUN` step.
        boolean exitAfterStart,

        // ── router mount / broker / dispatch callback ──────────────────────
        // `FC_ROUTER_HTTP_PREFIX`, default `/router`.
        String routerHttpPrefix,
        // `FC_DEFAULT_BROKER`, default `""` (no pools start); fcdev sets `postgres`.
        String defaultBroker,
        // `FC_DISPATCH_PROCESSING_ENDPOINT` (alias `DISPATCH_SCHEDULER_PROCESSING_ENDPOINT`,
        // the IaC's Service Connect name for this callback); empty →
        // `http://localhost:<apiPort>/api/dispatch/process`.
        String dispatchProcessingEndpoint,
        // `FC_DISPATCH_QUEUE_TYPE` (alias `DISPATCH_QUEUE_TYPE`), default `""`:
        // `SQS` (case-insensitive) deployed, `postgres` (or unset) in dev.
        // Read by [io.flowcatalyst.platform.dispatch.DispatchQueueSettings#resolve],
        // never here — a startup refusal needs the two-field cross-check
        // against [#dispatchQueuePrefix] below (`docs/spec/deployed-dispatch.md`
        // §3 "Risks to pin with tests").
        String dispatchQueueType,
        // `FC_DISPATCH_QUEUE_URL` (alias `DISPATCH_QUEUE_URL`), default `""`:
        // the IaC's dedicated SQS queue URL. Read **only** to derive the AWS
        // account and region for composing this platform's own per-tenant
        // queue URLs — the single queue that URL names is itself unused.
        String dispatchQueueUrl,
        // `FC_DISPATCH_QUEUE_REGION` (alias `DISPATCH_QUEUE_REGION`), default
        // `""`: overrides the region parsed from [#dispatchQueueUrl] when set.
        String dispatchQueueRegion,
        // `FC_DISPATCH_QUEUE_PREFIX` (no alias — new name, not yet in the
        // IaC), default `""`: the `FC-{env}` prefix composed dispatch queue
        // names start with (e.g. `FC-staging`). Required whenever
        // [#dispatchQueueType] is `SQS` — a blank prefix there is a startup
        // error, never a queue literally named `FC-{env}`.
        String dispatchQueuePrefix,

        // ── MCP ────────────────────────────────────────────────────────────
        // `FC_MCP_PORT`, default 8090.
        int mcpPort,
        // `FC_MCP_BIND`, default `127.0.0.1` (localhost-only; `0.0.0.0` to expose).
        String mcpBind,

        // ── stream processor ───────────────────────────────────────────────
        // `FC_STREAM_EVENTS_ENABLED`, default true.
        boolean streamEventsEnabled,
        // `FC_STREAM_DISPATCH_JOBS_ENABLED`, default true.
        boolean streamDispatchJobsEnabled,
        // `FC_STREAM_FAN_OUT_ENABLED`, default true.
        boolean streamFanOutEnabled,
        // `FC_STREAM_PARTITION_MANAGER_ENABLED` (alias `FC_STREAM_PARTITIONS_ENABLED`), default true.
        boolean streamPartitionsEnabled,
        // `FC_STREAM_BATCH_SIZE`, default 0 (per-projection defaults).
        int streamBatchSize,
        // `FC_STREAM_FAN_OUT_BATCH_SIZE`, default 0 (falls back to `streamBatchSize`, then 200).
        int streamFanOutBatchSizeOverride,
        // `FC_STREAM_EVENTS_BATCH_SIZE`, default 0 (falls back to `streamBatchSize`, then 100).
        int streamEventsBatchSizeOverride,
        // `FC_STREAM_DISPATCH_JOBS_BATCH_SIZE`, default 0 (falls back to `streamBatchSize`, then 100).
        int streamDispatchJobsBatchSizeOverride,
        // `FC_STREAM_FAN_OUT_SUBS_REFRESH_SECS`, default 0 (= 5s).
        int streamFanOutSubsRefreshSecs,
        // `FC_STREAM_PARTITION_MONTHS_FORWARD`, default 0 (= 3).
        int streamPartitionMonthsForward,
        // `FC_STREAM_PARTITION_RETENTION_DAYS`, default 0 (= 90).
        int streamPartitionRetentionDays,
        // `FC_STREAM_PARTITION_RETENTION_DAYS_SCHEDULED_JOBS`, default 0 (= 30).
        int streamPartitionScheduledJobRetentionDays,
        // `FC_STREAM_PARTITION_TICK_HOURS`, default 0 (= 24).
        int streamPartitionTickHours,

        // ── scheduled-job scheduler (docs/spec/scheduled-job-scheduler.md §1) ─
        // `FC_SCHEDULED_JOB_POLL_SECONDS`, default 0 (= 30s).
        int scheduledJobPollSeconds,
        // `FC_SCHEDULED_JOB_DISPATCH_SECONDS`, default 0 (= 5s).
        int scheduledJobDispatchSeconds,
        // `FC_SCHEDULED_JOB_DISPATCH_BATCH`, default 0 (= 32).
        int scheduledJobDispatchBatch,
        // `FC_SCHEDULED_JOB_HTTP_TIMEOUT_SECONDS`, default 0 (= 10s).
        int scheduledJobHttpTimeoutSeconds,

        // ── outbox processor ───────────────────────────────────────────────
        // `FC_OUTBOX_PLATFORM_URL` (aliases `FC_OUTBOX_API_URL`, `FC_API_BASE_URL`, `FLOWCATALYST_URL`), no default.
        String outboxPlatformUrl,
        // `FC_OUTBOX_PLATFORM_AUTH_TOKEN` (aliases `FC_OUTBOX_TOKEN`, `FC_API_TOKEN`), no default.
        String outboxPlatformAuthToken,
        // `FC_OUTBOX_BATCH_SIZE`, default 0 (library default 100).
        int outboxBatchSize,
        // `FC_OUTBOX_MAX_IN_FLIGHT`, default 0 (library default 1000).
        int outboxMaxInFlight,
        // `FC_OUTBOX_POLL_INTERVAL_MS`, default 0 (library default 1000).
        int outboxPollIntervalMs,
        // `FC_OUTBOX_MAX_CONCURRENT_GROUPS` (alias `FC_MAX_CONCURRENT_GROUPS`), default 0 (= 10).
        int outboxMaxConcurrentGroups,
        // `FC_OUTBOX_BLOCK_ON_ERROR`, default true.
        boolean outboxBlockOnError,
        // `FC_OUTBOX_ADMIN_PORT`, default 0 (off); serves on `127.0.0.1:<port>`.
        int outboxAdminPort,
        // `FC_OUTBOX_BACKEND` (alias `FC_OUTBOX_DB_TYPE`), default `postgres`.
        String outboxBackend,
        // `FC_OUTBOX_MONGO_URI` (alias `FC_OUTBOX_DB_URL`), no default.
        String outboxMongoUri,
        // `FC_OUTBOX_MONGO_DB`, default `flowcatalyst`.
        String outboxMongoDb,

        // ── router ─────────────────────────────────────────────────────────
        // `FLOWCATALYST_CONFIG_URL`, no default.
        String routerConfigUrl,
        // `FC_ROUTER_CONFIG_INTERVAL_SECONDS` (alias `FLOWCATALYST_CONFIG_INTERVAL`),
        // seconds, no default here — `""` when unset. Carried as the raw string
        // per `CONVENTIONS.md` §8; the composition root parses it with
        // [io.flowcatalyst.router.manager.RouterServer#parseConfigPollInterval],
        // which owns the 300s default and the set-but-invalid WARN.
        String routerConfigIntervalRaw,
        // `FLOWCATALYST_DEV_MODE`, default false.
        boolean routerDevMode,
        // `FC_NOTIFY_WEBHOOK_URL` (alias `NOTIFICATION_TEAMS_WEBHOOK_URL`), no default.
        String routerNotifyWebhookUrl,
        // Raw `NOTIFICATION_TEAMS_ENABLED` (`""` when unset). Rust: a non-empty
        // webhook alone means notify, and this flag can only ever widen that —
        // an explicit `false` is ignored once a URL is configured. Java
        // deliberately deviates: an explicit `false` always disables, even with
        // a URL set — see [io.flowcatalyst.router.observability.WarningNotifier#create].
        String routerNotifyTeamsEnabledRaw,
        // `FC_NOTIFY_MIN_SEVERITY` (alias `NOTIFICATION_MIN_SEVERITY`), default `WARNING`
        // (X-04). Carried here as the raw string per `CONVENTIONS.md` §8; the composition
        // root parses it with `Warnings.parseMinSeverity`, which also owns the
        // invalid-value fallback and its WARN log, and accepts `WARN` as well as
        // `WARNING` (Rust accepts both).
        String routerNotifyMinSeverity,
        // `FC_NOTIFY_BATCH_INTERVAL_SECONDS` (alias `NOTIFICATION_BATCH_INTERVAL`),
        // default 300 (Rust's `NotificationConfig::default`); `0` means no
        // batching — every notice is sent immediately as its own card.
        int routerNotifyBatchIntervalSeconds,
        // `FC_DRAIN_TIMEOUT_SECONDS`, default 60.
        int routerDrainTimeoutSec,
        // `FC_ROUTER_STRICT_ROUTING`, default false (R-13/R-16, §2.3): a
        // message reaching the router with no poolCode, no dispatchMode, or
        // an ordered dispatchMode with no messageGroupId is ACKed as
        // malformed instead of defaulted. Off until every producer is
        // confirmed to send the routing fields.
        boolean routerStrictRouting,
        // `FC_ROUTER_SYNTH_POOL_IDLE_SECS`, default 0 (R-59, §2.2): idle TTL
        // for a synthesised `{client}-DEFAULT-POOL`. 0/unset means "use the
        // implementation's own default" ([io.flowcatalyst.router.manager.RouterManager#DEFAULT_SYNTH_POOL_IDLE_TTL]),
        // never "never evict".
        int routerSynthPoolIdleSecs,
        // Raw `AUTH_MODE` (trimmed); `NONE` (case-insensitive) forces router BasicAuth off.
        String routerAuthMode,
        // `FC_ROUTER_AUTH_USER` (alias `AUTH_BASIC_USERNAME`); `""` when unset or when
        // `AUTH_MODE=NONE`. Empty disables auth on the router surface.
        String routerAuthUser,
        // `FC_ROUTER_AUTH_PASS` (alias `AUTH_BASIC_PASSWORD`); `""` when unset or when `AUTH_MODE=NONE`.
        String routerAuthPass,
        // `FC_ROUTER_PLATFORM_URL`, no alias (alias sprawl is an open owner question), default `""`.
        // The A-01 gate (`docs/spec/router-completion.md` §2 ruling 3): blank means every
        // `BLOCK_ON_ERROR` group's untried siblings are released back to the broker; set, they are
        // ACKed and reported to this platform base URL's `/api/dispatch/settled` hook instead.
        String routerPlatformUrl,
        // `FC_ROUTER_CLIENT_ID`, default `""` (`docs/spec/router-config-auth.md` §2): the
        // router's OAuth client_credentials identity for fetching the router-config document
        // from `routerPlatformUrl`. Set together with [#routerClientSecret] or not at all —
        // `Router#configSource` refuses one without the other, and both without
        // [#routerPlatformUrl].
        String routerClientId,
        // `FC_ROUTER_CLIENT_SECRET`, default `""`. See [#routerClientId].
        String routerClientSecret,

        // ── ALB self-registration ──────────────────────────────────────────
        // `FC_ALB_ENABLED`, default false.
        boolean albEnabled,
        // `FC_ALB_TARGET_GROUP_ARN`, no default.
        String albTargetGroupArn,
        // `FC_ALB_TARGET_ID` (alias `FC_ALB_INSTANCE_IP`), no default.
        String albInstanceIp,
        // `FC_ALB_TARGET_PORT`, default 8080.
        int albPort,
        // `FC_ALB_REGION`, no default (AWS SDK default region chain).
        String albRegion,
        // `FC_ALB_DEREGISTRATION_DELAY_SECONDS`, default 0.
        int albDeregDelaySec,

        // ── standby / HA ───────────────────────────────────────────────────
        // `FC_STANDBY_ENABLED`, then `FLOWCATALYST_STANDBY_ENABLED`, then
        // `STANDBY_ENABLED`, default false.
        boolean standbyEnabled,
        // `FC_STANDBY_REDIS_URL`, then `FLOWCATALYST_STANDBY_REDIS_URL`, then
        // `FLOWCATALYST_REDIS_URL`, then `REDIS_URL`, default `redis://127.0.0.1:6379`.
        String standbyRedisUrl,
        // `FC_STANDBY_LOCK_KEY`, then `FLOWCATALYST_STANDBY_LOCK_KEY`, default
        // `fc:router:leader` (owner ruling 2026-09-11: overrides the drop-in
        // brief's original "leave it fc:server:leader" call — Go still
        // defaults to `fc:server:leader`, so a mixed Go/Java fleet running
        // standby must set this explicitly on one side; see
        // `docs/spec/router-env.md`).
        String standbyLockKey,
        // `FC_STANDBY_LOCK_TTL_SECONDS`, then `FLOWCATALYST_STANDBY_LOCK_TTL`,
        // default 30 (today's `LeaderElection.LOCK_TTL`).
        int standbyLockTtlSeconds,
        // `FC_STANDBY_HEARTBEAT_SECONDS`, then `FLOWCATALYST_STANDBY_HEARTBEAT_INTERVAL`,
        // default 10 (today's `LeaderElection.HEARTBEAT`).
        int standbyHeartbeatSeconds,
        // `FC_INSTANCE_ID`, then `FLOWCATALYST_INSTANCE_ID`, then `HOSTNAME`,
        // default `""` — blank means [io.flowcatalyst.server.Router] derives one
        // itself (a random UUID), same as today when none is set.
        String standbyInstanceId,

        // ── JWT signing ────────────────────────────────────────────────────
        // `FC_JWT_SIGNING_KEY_PATH`, no default.
        String jwtSigningKeyPath,
        // `FLOWCATALYST_JWT_PREVIOUS_PUBLIC_KEY`, PEM-normalized; `""` when unset or not a
        // real PEM (no `-----BEGIN` marker) — optional, so a junk value must not stop boot.
        String jwtPreviousPublicKey,
        // `FC_AUTH_ALLOW_TEST_HEADERS`, default false: enables the `X-FC-Test-Principal` dev fallback.
        boolean authAllowTestHeaders,

        // ── CORS filter (spec docs/spec/cors.md §9) ────────────────────────
        // `FC_CORS_CACHE_TTL_MS`, default 30000: how long the CORS filter's
        // in-process allowlist cache is trusted before it re-reads
        // `CorsOriginRepository.allowedOrigins()` — the fallback refresh for
        // the multi-node case, alongside the same-node `onChange` invalidation
        // fired by an add/delete.
        long corsCacheTtlMs,

        // ── field encryption ───────────────────────────────────────────────
        // `FLOWCATALYST_APP_KEY`, no default: the AES-256-GCM key (`docs/spec/encryption.md` §1);
        // `""` = field encryption disabled (plaintext secrets are refused, never stored raw).
        String appKey,
        // `FLOWCATALYST_APP_KEY_PREVIOUS`, no default: the previous key during rotation.
        String appKeyPrevious,

        // ── MCP credentials ────────────────────────────────────────────────
        // `FLOWCATALYST_URL` (alias `FC_MCP_PLATFORM_URL`), no default.
        String mcpPlatformUrl,
        // `FLOWCATALYST_CLIENT_ID`, no default.
        String mcpClientId,
        // `FLOWCATALYST_CLIENT_SECRET`, no default.
        String mcpClientSecret,
        // `FC_MCP_PLATFORM_AUTH_TOKEN`, no default: a Java-only interim static
        // bearer, used in place of the client_credentials token manager until
        // the Java platform has an `/oauth/token` endpoint of its own
        // (`docs/spec/mcp.md` §2).
        String mcpPlatformAuthToken,

        // ── WebAuthn ───────────────────────────────────────────────────────
        // `FC_WEBAUTHN_RP_ID`, default `localhost`.
        String webauthnRpId,
        // `FC_WEBAUTHN_ORIGINS` (comma-separated; alias the legacy singular
        // `FC_WEBAUTHN_RP_ORIGIN`), default `[http://localhost:8080]`; blank entries dropped.
        List<String> webauthnOrigins,

        // ── function registry (docs/spec/function-registry.md §4.6) ──────────
        // `FC_FN_DEFAULT_MAX_DURATION_MS` (default 30000), `FC_FN_DEFAULT_MAX_CONCURRENCY`
        // (default 32), `FC_FN_DEFAULT_WASM_MEMORY_MB` (default 64), `FC_FN_DEFAULT_DB_POOL_SIZE`
        // (default 4), `FC_FN_MAX_WARM_PER_HOST` (default 200): the platform's function-limit
        // defaults, resolved straight into `FunctionLimits` — whose constructor already refuses
        // any component `<= 0` (`IllegalArgumentException`), so a set-but-non-positive value is a
        // startup error the same way a malformed value elsewhere in this file silently falls back
        // to a default: unparseable input still yields the default (`EnvReader#integer`), but a
        // parseable non-positive one reaches the record and fails loudly instead of arming a
        // function pool with a zero or negative limit.
        FunctionLimits functionLimits,
        // `FC_FN_SIGNATURES` (`required` default | `off`), spec `function-api.md` §5.1
        // step 5, §8 P9: parsed with [SignaturesMode#parse] (unset/blank/"required"/any
        // unrecognised typo ⇒ `REQUIRED` — the only way to reach `OFF` is to spell it
        // exactly). Carried here as the parsed enum, not the raw string, but the
        // "off requires FLOWCATALYST_DEV_MODE=true" refusal is NOT enforced here — it is
        // the composition root's job ([io.flowcatalyst.platform.function.artifact.Signatures#resolve]),
        // same division of labour as [#functionLimits]'s ceilings vs. this record's own
        // positivity check.
        SignaturesMode fnSignaturesMode,
        // `FC_FN_TRUST_ROOT`, no default: an operator-supplied `trusted_root.json` path,
        // for a private/on-prem Sigstore instance whose Fulcio and Rekor the committed
        // public-good root (`TrustRoot#sigstorePublicGood`) does not cover — the same
        // "path to real key material" shape as `FC_JWT_SIGNING_KEY_PATH`. Blank (the
        // default) means the composition root uses the committed public-good root, same
        // as before this existed. Read here, resolved to a `TrustRoot` in `Platform`
        // (not here) so a test can inject its own `TrustRoot` supplier without touching
        // the classpath resource or a file on disk, same division of labour as
        // `#fnSignaturesMode` above.
        String fnTrustRootPath,
        // The reader every value above came from. Subsystems that parse their own
        // knobs (backoff, mail, passkeys, rate limits) read it too — never the process
        // environment directly, or fcdev's map-loaded environment and the parity
        // harness's per-side environment are silently ignored (found by the harness).
        EnvReader reader
) {

    public static final String DEFAULT_DATABASE_URL = "postgresql://postgres@localhost:5432/flowcatalyst";

    public Env {
        webauthnOrigins = List.copyOf(webauthnOrigins);
    }

    /// `LoadEnv()` over the process environment.
    public static Env load() {
        return load(EnvReader.system());
    }

    /// `LoadEnv()` over an arbitrary environment — tests, or the merged map
    /// [DotEnv] produces.
    public static Env load(Map<String, String> environment) {
        return load(new EnvReader(environment));
    }

    public static Env load(EnvReader e) {
        var apiPort = e.integerAlias("FC_API_PORT", "API_PORT", "PORT", 8080);
        var dispatch = e.firstSet("FC_DISPATCH_PROCESSING_ENDPOINT", "DISPATCH_SCHEDULER_PROCESSING_ENDPOINT").orElse("");
        if (dispatch.isEmpty()) {
            // Default the dispatch callback to the local API listener: the router
            // consumes a queued job and POSTs {messageId} here for delivery.
            dispatch = "http://localhost:" + apiPort + "/api/dispatch/process";
        }
        var authMode = e.get("AUTH_MODE").trim();
        var authOff = authMode.equalsIgnoreCase("NONE");

        var tlsPort = e.integer("FC_TLS_PORT", 8443);

        return new Env(
                apiPort,
                e.integer("FC_METRICS_PORT", 9090),

                tlsPort,
                e.get("FC_TLS_KEYSTORE_PATH"),
                e.get("FC_TLS_KEYSTORE_PASSWORD"),
                e.get("FC_TLS_CERT_PATH"),
                e.get("FC_TLS_KEY_PATH"),
                e.bool("FC_HTTP3_ENABLED", false),
                e.integer("FC_HTTP3_PORT", tlsPort),

                resolveDatabaseUrl(e),
                e.firstSet("FC_JWT_ISSUER", "FC_EXTERNAL_BASE_URL", "EXTERNAL_BASE_URL").orElse("http://localhost:8080"),
                e.positiveLongAlias("FC_JWT_ACCESS_TOKEN_TTL_SECS", "OIDC_ACCESS_TOKEN_TTL", 3600L),
                e.positiveLongAlias("FC_SESSION_TTL_SECS", "OIDC_SESSION_TTL", 86400L),
                e.positiveLongAlias("FC_REFRESH_TOKEN_TTL_SECS", "OIDC_REFRESH_TOKEN_TTL", 604800L),

                e.boolAlias("FC_PLATFORM_ENABLED", "PLATFORM_ENABLED", true),
                e.boolAlias("FC_ROUTER_ENABLED", "MESSAGE_ROUTER_ENABLED", false),
                e.boolAlias("FC_SCHEDULER_ENABLED", "DISPATCH_SCHEDULER_ENABLED", false),
                e.boolAlias("FC_SCHEDULED_JOB_ENABLED", "SCHEDULED_JOB_SCHEDULER_ENABLED", false),
                e.boolAlias("FC_STREAM_PROCESSOR_ENABLED", "STREAM_PROCESSOR_ENABLED", false),
                e.boolAlias("FC_OUTBOX_ENABLED", "OUTBOX_PROCESSOR_ENABLED", false),
                e.bool("FC_MCP_ENABLED", false),
                e.bool("FC_EXIT_AFTER_START", false),

                e.or("FC_ROUTER_HTTP_PREFIX", "/router"),
                e.or("FC_DEFAULT_BROKER", ""),
                dispatch,
                e.firstSet("FC_DISPATCH_QUEUE_TYPE", "DISPATCH_QUEUE_TYPE").orElse(""),
                e.firstSet("FC_DISPATCH_QUEUE_URL", "DISPATCH_QUEUE_URL").orElse(""),
                e.firstSet("FC_DISPATCH_QUEUE_REGION", "DISPATCH_QUEUE_REGION").orElse(""),
                e.or("FC_DISPATCH_QUEUE_PREFIX", ""),

                e.integer("FC_MCP_PORT", 8090),
                e.or("FC_MCP_BIND", "127.0.0.1"),

                // Stream sub-toggles default ON so FC_STREAM_PROCESSOR_ENABLED=true
                // is sufficient to bring up the whole stream pipeline.
                e.bool("FC_STREAM_EVENTS_ENABLED", true),
                e.bool("FC_STREAM_DISPATCH_JOBS_ENABLED", true),
                e.bool("FC_STREAM_FAN_OUT_ENABLED", true),
                e.boolAlias("FC_STREAM_PARTITION_MANAGER_ENABLED", "FC_STREAM_PARTITIONS_ENABLED", true),
                e.integer("FC_STREAM_BATCH_SIZE", 0),
                e.integer("FC_STREAM_FAN_OUT_BATCH_SIZE", 0),
                e.integer("FC_STREAM_EVENTS_BATCH_SIZE", 0),
                e.integer("FC_STREAM_DISPATCH_JOBS_BATCH_SIZE", 0),
                e.integer("FC_STREAM_FAN_OUT_SUBS_REFRESH_SECS", 0),
                e.integer("FC_STREAM_PARTITION_MONTHS_FORWARD", 0),
                e.integer("FC_STREAM_PARTITION_RETENTION_DAYS", 0),
                e.integer("FC_STREAM_PARTITION_RETENTION_DAYS_SCHEDULED_JOBS", 0),
                e.integer("FC_STREAM_PARTITION_TICK_HOURS", 0),

                e.integer("FC_SCHEDULED_JOB_POLL_SECONDS", 0),
                e.integer("FC_SCHEDULED_JOB_DISPATCH_SECONDS", 0),
                e.integer("FC_SCHEDULED_JOB_DISPATCH_BATCH", 0),
                e.integer("FC_SCHEDULED_JOB_HTTP_TIMEOUT_SECONDS", 0),

                // FC_OUTBOX_API_URL / FC_OUTBOX_TOKEN and FC_API_BASE_URL / FC_API_TOKEN are
                // legacy outbox-processor names honoured so existing deployments drop in.
                e.firstSet("FC_OUTBOX_PLATFORM_URL", "FC_OUTBOX_API_URL", "FC_API_BASE_URL", "FLOWCATALYST_URL").orElse(""),
                e.firstSet("FC_OUTBOX_PLATFORM_AUTH_TOKEN", "FC_OUTBOX_TOKEN", "FC_API_TOKEN").orElse(""),
                e.integer("FC_OUTBOX_BATCH_SIZE", 0),
                e.integer("FC_OUTBOX_MAX_IN_FLIGHT", 0),
                e.integer("FC_OUTBOX_POLL_INTERVAL_MS", 0),
                e.integerAlias("FC_OUTBOX_MAX_CONCURRENT_GROUPS", "FC_MAX_CONCURRENT_GROUPS", 0),
                e.bool("FC_OUTBOX_BLOCK_ON_ERROR", true),
                e.integer("FC_OUTBOX_ADMIN_PORT", 0),
                e.firstSet("FC_OUTBOX_BACKEND", "FC_OUTBOX_DB_TYPE").orElse("postgres"),
                e.firstSet("FC_OUTBOX_MONGO_URI", "FC_OUTBOX_DB_URL").orElse(""),
                e.or("FC_OUTBOX_MONGO_DB", "flowcatalyst"),

                e.get("FLOWCATALYST_CONFIG_URL"),
                e.firstSet("FC_ROUTER_CONFIG_INTERVAL_SECONDS", "FLOWCATALYST_CONFIG_INTERVAL").orElse(""),
                e.bool("FLOWCATALYST_DEV_MODE", false),
                e.firstSet("FC_NOTIFY_WEBHOOK_URL", "NOTIFICATION_TEAMS_WEBHOOK_URL").orElse(""),
                e.get("NOTIFICATION_TEAMS_ENABLED"),
                e.firstSet("FC_NOTIFY_MIN_SEVERITY", "NOTIFICATION_MIN_SEVERITY").orElse("WARNING"),
                e.integerAlias("FC_NOTIFY_BATCH_INTERVAL_SECONDS", "NOTIFICATION_BATCH_INTERVAL", 300),
                e.integer("FC_DRAIN_TIMEOUT_SECONDS", 60),
                e.bool("FC_ROUTER_STRICT_ROUTING", false),
                e.integer("FC_ROUTER_SYNTH_POOL_IDLE_SECS", 0),
                authMode,
                authOff ? "" : e.firstSet("FC_ROUTER_AUTH_USER", "AUTH_BASIC_USERNAME").orElse(""),
                authOff ? "" : e.firstSet("FC_ROUTER_AUTH_PASS", "AUTH_BASIC_PASSWORD").orElse(""),
                e.get("FC_ROUTER_PLATFORM_URL"),
                e.get("FC_ROUTER_CLIENT_ID"),
                e.get("FC_ROUTER_CLIENT_SECRET"),

                e.bool("FC_ALB_ENABLED", false),
                e.get("FC_ALB_TARGET_GROUP_ARN"),
                e.firstSet("FC_ALB_TARGET_ID", "FC_ALB_INSTANCE_IP").orElse(""),
                e.integer("FC_ALB_TARGET_PORT", 8080),
                e.get("FC_ALB_REGION"),
                e.integer("FC_ALB_DEREGISTRATION_DELAY_SECONDS", 0),

                e.boolAlias("FC_STANDBY_ENABLED", "FLOWCATALYST_STANDBY_ENABLED", "STANDBY_ENABLED", false),
                e.firstSet("FC_STANDBY_REDIS_URL", "FLOWCATALYST_STANDBY_REDIS_URL", "FLOWCATALYST_REDIS_URL", "REDIS_URL")
                        .orElse("redis://127.0.0.1:6379"),
                // owner ruling 2026-09-11: fc:router:leader; Go still defaults to fc:server:leader.
                e.firstSet("FC_STANDBY_LOCK_KEY", "FLOWCATALYST_STANDBY_LOCK_KEY").orElse("fc:router:leader"),
                e.integerAlias("FC_STANDBY_LOCK_TTL_SECONDS", "FLOWCATALYST_STANDBY_LOCK_TTL", 30),
                e.integerAlias("FC_STANDBY_HEARTBEAT_SECONDS", "FLOWCATALYST_STANDBY_HEARTBEAT_INTERVAL", 10),
                e.firstSet("FC_INSTANCE_ID", "FLOWCATALYST_INSTANCE_ID", "HOSTNAME").orElse(""),

                e.get("FC_JWT_SIGNING_KEY_PATH"),
                normalizedPreviousPublicKey(e),
                e.bool("FC_AUTH_ALLOW_TEST_HEADERS", false),

                e.longValue("FC_CORS_CACHE_TTL_MS", 30_000L),

                e.get(Encryption.ENV_APP_KEY),
                e.get(Encryption.ENV_APP_KEY_PREVIOUS),

                e.firstSet("FLOWCATALYST_URL", "FC_MCP_PLATFORM_URL").orElse(""),
                e.get("FLOWCATALYST_CLIENT_ID"),
                e.get("FLOWCATALYST_CLIENT_SECRET"),
                e.get("FC_MCP_PLATFORM_AUTH_TOKEN"),

                e.or("FC_WEBAUTHN_RP_ID", "localhost"),
                webauthnOrigins(e),

                new FunctionLimits(
                        e.integer("FC_FN_DEFAULT_MAX_DURATION_MS", FunctionLimits.DEFAULT_MAX_DURATION_MS),
                        e.integer("FC_FN_DEFAULT_MAX_CONCURRENCY", FunctionLimits.DEFAULT_MAX_CONCURRENCY),
                        e.integer("FC_FN_DEFAULT_WASM_MEMORY_MB", FunctionLimits.DEFAULT_WASM_MEMORY_MB),
                        e.integer("FC_FN_DEFAULT_DB_POOL_SIZE", FunctionLimits.DEFAULT_DB_POOL_SIZE),
                        e.integer("FC_FN_MAX_WARM_PER_HOST", FunctionLimits.DEFAULT_MAX_WARM_PER_HOST)),
                SignaturesMode.parse(e.or("FC_FN_SIGNATURES", "required")),
                e.get("FC_FN_TRUST_ROOT"),
                e
        );
    }

    /// Router HTTP BasicAuth is on iff a username resolved (and `AUTH_MODE` is not `NONE`).
    public boolean routerAuthEnabled() {
        return !routerAuthUser.isEmpty();
    }

    /// `ResolveDatabaseURL`: the three-mode database resolution.
    ///
    ///   1. `FC_DATABASE_URL` / `DATABASE_URL` — full connection string (preferred).
    ///   2. `DB_HOST` + `DB_NAME` (`flowcatalyst`) + `DB_PORT` (`5432`) +
    ///      `DB_USERNAME` (`postgres`) + `DB_PASSWORD` — explicit credentials; a
    ///      host already carrying `:port` is used as-is; the password is
    ///      query-escaped the way Go's `url.QueryEscape` does it.
    ///   3. Neither → [#DEFAULT_DATABASE_URL] (local dev; the server dies at
    ///      connect time if that is wrong).
    ///
    /// (AWS Secrets Manager via `DB_SECRET_ARN` lives in `dbsecret.go` and is
    /// not part of this resolver in Go either.)
    public static String resolveDatabaseUrl(EnvReader e) {
        var full = e.firstSet("FC_DATABASE_URL", "DATABASE_URL");
        if (full.isPresent()) return full.get();
        var host = e.get("DB_HOST");
        if (host.isEmpty()) return DEFAULT_DATABASE_URL;
        var name = e.or("DB_NAME", "flowcatalyst");
        var port = e.or("DB_PORT", "5432");
        var username = e.or("DB_USERNAME", "postgres");
        var password = e.get("DB_PASSWORD");
        var hostPort = host.contains(":") ? host : host + ":" + port;
        if (password.isEmpty()) {
            return "postgresql://" + username + "@" + hostPort + "/" + name;
        }
        return "postgresql://" + username + ":" + queryEscape(password) + "@" + hostPort + "/" + name;
    }

    /// `normalizedPreviousPublicKey`: the SSM/env PEM repaired the same way as
    /// the private key, dropped unless it is a real PEM.
    static String normalizedPreviousPublicKey(EnvReader e) {
        var v = SigningKeys.normalizePem(e.get("FLOWCATALYST_JWT_PREVIOUS_PUBLIC_KEY"));
        return v.contains("-----BEGIN") ? v : "";
    }

    /// `webauthnOrigins`: comma-separated, trimmed, blanks dropped (so a
    /// trailing comma cannot inject an empty origin).
    static List<String> webauthnOrigins(EnvReader e) {
        var raw = e.firstSet("FC_WEBAUTHN_ORIGINS", "FC_WEBAUTHN_RP_ORIGIN").orElse("http://localhost:8080");
        return Arrays.stream(raw.split(",", -1))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /// Go's `url.QueryEscape`: unreserved `A-Z a-z 0-9 - _ . ~` pass through,
    /// space becomes `+`, everything else is `%XX` (upper-case hex) over UTF-8.
    /// (`java.net.URLEncoder` differs on `*` and `~`, hence the hand-rolled one.)
    /// Public: [io.flowcatalyst.server.dbsecret.DbSecretDsn] reuses it for the
    /// same password-in-a-DSN escaping rule.
    public static String queryEscape(String s) {
        var sb = new StringBuilder(s.length() + 8);
        for (var b : s.getBytes(StandardCharsets.UTF_8)) {
            var c = (char) (b & 0xFF);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~') {
                sb.append(c);
            } else if (c == ' ') {
                sb.append('+');
            } else {
                sb.append('%').append(String.format(Locale.ROOT, "%02X", b & 0xFF));
            }
        }
        return sb.toString();
    }
}
