# Phase 0 — deployment inventory

Produced per `docs/verification-plan.md` "Phase 0 — deployment inventory".
Source: the Pulumi IaC at `../inhance/iac` (read-only; nothing there was
edited). Primary file `compute/index.ts` (4,994 lines) plus
`compute/Pulumi.nonprod.yaml` and `compute/Pulumi.prod.yaml` for stack config
values. All line numbers below are `compute/index.ts:<n>` unless stated
otherwise.

Three FlowCatalyst ECS services exist in this IaC, each × two environments
(nonprod `np`, prod `prod`):

1. **fc-platform** — the API/identity tier (task family `inhance-fc-{env}-platform`)
2. **fc-worker** — dispatch scheduler / scheduled-job scheduler (task family `inhance-fc-{env}-worker`)
3. **fc-router** — the message router (task family `inhance-fcr-{env}`)

No other FlowCatalyst ECS service exists in this repo. A fourth adjacent
service, **Postbox Processor** (`index.ts:1582` on), is a separate Go app
(`apps/postbox-processor-go`) that is not part of FlowCatalyst and is out of
scope here. Two Laravel apps (HR Administrator, RFP — `index.ts:3761` and
`:4046`+) consume the FlowCatalyst platform as an OIDC/webhook **client**
(`FLOWCATALYST_*` env vars pointed at `https://platform.inhanceapps.com`);
they are not FlowCatalyst deployments and are also out of scope, noted only
where their env vars name a FlowCatalyst-side secret.

## Which binary each task is written for — a discrepancy in the IaC's own comments

The task explicitly asked this to be recorded rather than resolved:

- The **platform** task's header comment (`index.ts:877`) reads: *"Rust
  fc-server binary — runs platform API with embedded frontend."* The comment
  above the shared env block (`index.ts:850-852`) says: *"The Rust fc-server
  binary accepts both FC_* and TS-style env var names. We use the TS names
  here for backward compatibility with the existing task definitions; the
  Rust binary resolves them via aliases."*
- The **worker** task's header comment (`index.ts:952-954`), for the **same
  ECR image** (`inhance/flowcatalyst`, same `imageTag`) as the platform task,
  reads: *"Go fc-server binary (flowcatalyst-go) — runs Dispatch Scheduler
  only... Message Router runs as a separate standalone binary (fc-router)."*
- The **router** section header (`index.ts:1321`) reads *"FC Router (Rust
  Message Router)"*, but the router container's own inline comment
  (`index.ts:1503-1509`) says: *"the Go fc-server is a single binary; these
  [`MESSAGE_ROUTER_ENABLED`/`PLATFORM_ENABLED`] select the router-only
  role... The Go router is DB-less — config comes from the platform API
  (`FLOWCATALYST_CONFIG_URL`)... Ignored by the legacy Rust standalone
  fc-router binary."*

So: the platform task's own header calls the image "Rust", the worker task's
header calls the *identical* image "Go", and the router task's section
header calls it "Rust" while its own inline comment describes Go-fc-server
subsystem-toggle behaviour and says the toggles are meaningless to the
"legacy Rust standalone" binary. Read literally, the IaC's own evidence
(subsystem toggles, DB-less router deriving queues from `FLOWCATALYST_CONFIG_URL`,
one image shared by platform+worker) is consistent with **all three tasks
today running the unified Go `fc-server` binary** in different subsystem
combinations, and the "Rust" wording in the two header comments is stale.
This is exactly the ambiguity Phase 1 must resolve against the *actual*
images in ECR (`inhance/flowcatalyst:{imageTag}`, `inhance/fc-router:{routerImageTag}`)
before picking which behaviour Java must match.

---

## Shared infrastructure (both environments)

- **Stack → env mapping**: Pulumi stack `nonprod` → `env="np"`, `prod` → `env="prod"` (`index.ts:5-8`).
- **VPC / hosted zone**: from StackReference `organization/inhance-shared/{stack}` outputs `vpcId`, `hostedZoneId` (`index.ts:184-187`, exact StackReference name not re-confirmed above line 184 window — see `sharedStack` definition near there).
- **RDS**: StackReference `organization/inhance-database/{stack}` outputs `dbSecurityGroupId`, `dbEndpoint`, `dbName`, `dbMasterUserSecretArn` (`index.ts:196-200`). One shared Postgres instance/database for platform+worker; router is DB-less by design.
- **Cache**: StackReference `organization/inhance-cache/{stack}` outputs `cacheSecurityGroupId`, `cacheEndpoint` (`index.ts:203-205`) — ElastiCache Valkey, TLS (`rediss://`).
- **ECS cluster**: `inhance-{env}-cluster`, Container Insights enabled (`index.ts:317-319`), EC2 launch type only (capacity provider `ec2CapacityProvider`, `index.ts:479`+), instance type from stack config (`ec2InstanceType`, default `t4g.large` np / `m7g.large` prod per yaml), ASG min/max from stack config. **No Fargate** anywhere in this file.
- **ALB**: one shared internet-facing ALB (`index.ts:384`+) for the whole cluster. HTTPS listener on 443 (`index.ts:637-647`, default action 404 fixed-response, TLS policy `ELBSecurityPolicy-TLS13-1-2-2021-06`), HTTP listener on 80 redirects to 443 (`index.ts:649-657`). Host-header `ListenerRule`s route to each service's target group.
- **Certificates**: prod uses one `*.inhanceapps.com` wildcard cert as the listener default (`index.ts:588-608`), covering both `platform.inhanceapps.com` and `fc-router.inhanceapps.com`. Nonprod issues per-service certs: `platform-np.inhanceapps.com` (`index.ts:610-629`) is the listener default; `qa-fc-router.inhanceapps.com` is added as an **additional SNI cert** via `aws.lb.ListenerCertificate` (`index.ts:1327-1351`, np only).
- **Service discovery**: two mechanisms in play —
  - A `PrivateDnsNamespace` `{env}.inhance2.local` (`index.ts:257-261`) with low-TTL (10s) `A` records `svc-fc-platform` / `svc-fc-worker` (`index.ts:266-284`) — these are declared but the actual east-west routing used by the tasks is:
  - An ECS **Service Connect** `HttpNamespace` `{env}.inhance.sc` (`index.ts:293-297`). Platform is a Service Connect **server** at alias `fc-platform:8080`; worker is a **client-only** member; router is a server at alias `fc-router:8080` (also gets client access to `fc-platform:8080`).
- **Dispatch queue**: one SQS FIFO queue `inhance-fc-{env}-dispatch.fifo` (`index.ts:244-251`, content-based dedup, visibility timeout 300s, retention 86400s = 1 day). Platform's PostCommitDispatcher publishes job notifications here; worker's Dispatch Scheduler polls PENDING jobs and publishes full MessagePointers here (comment `index.ts:238-242`).
- **SSM prefix**: `/inhance/{env}/fc-platform` for platform+worker secrets (`index.ts:844`). Execution role is granted `ssm:GetParameter(s)` on `arn:aws:ssm:{region}:{account}:parameter/inhance/{env}/*` (`index.ts:778-795`) — a wildcard over the **whole env's** SSM tree, not scoped to the `fc-platform` prefix.
- **CPU/memory sizing note (applies to all three FlowCatalyst services)**: every task definition sets only a container-level **`memoryReservation`** (soft limit, bin-packing hint) from stack config (`memory`/`routerMemory`). **No task- or container-level hard `memory` limit and no `cpu` reservation/limit are ever set** — the `cpu`/`routerCpu` stack config values (`index.ts:24`, `36`) are declared (`cpu` via `config.require`, so it must be present in every stack file) but **never referenced anywhere else in this file**. Tasks are unbounded on CPU and can burst past their memory reservation up to the host's free capacity.
- **Autoscaling**: none. `desiredCount` / `workerDesiredCount` / `routerDesiredCount` are static Pulumi config values with no `aws.appautoscaling.Target`/`Policy` anywhere for these three services (confirmed by grep — Application Auto Scaling in this file exists only for the unrelated Integral fleet: staging/ceramic/spar/montego processors).
- **Health check grace period**: not set on any of the three `aws.ecs.Service` resources (`healthCheckGracePeriodSeconds` absent) → ECS default (0s) applies.
- **Container-level Docker health check**: none defined in any of the three `containerDefinitions` (no `healthCheck` block) — health is entirely the ALB target group's `/health` HTTP check (platform, router) or nothing at all (worker, which is not ALB-fronted).

---

## 1. fc-platform (API/identity tier)

- **Task family**: `inhance-fc-{env}-platform` (`index.ts:881-949`, resource `fc-platform-task`).
- **Service**: `inhance-fc-{env}-svc` (`index.ts:1010-1049`, resource `fc-service`).
- **Image / tag logic**: `{ecrRepoUrl}:{imageTag}` — ECR repo `inhance/flowcatalyst` (`index.ts:209-221`; nonprod stack creates the repo, prod does `aws.ecr.getRepository` on the same name — **one shared ECR repo across both environments**). `imageTag` = stack config `imageTag`, default `"latest"` (`index.ts:26`) — neither `Pulumi.nonprod.yaml` nor `Pulumi.prod.yaml` overrides it, so **both environments float `:latest`** unless a deploy pipeline sets stack config explicitly outside this repo.
- **Launch type / arch / network mode**: EC2 (`requiresCompatibilities: ["EC2"]`), `runtimePlatform.cpuArchitecture: "ARM64"` (Graviton), `networkMode: "bridge"`.
- **CPU/memory**: `memoryReservation: parseInt(memory)` — stack config `memory`, `512` MB in both `Pulumi.nonprod.yaml` and `Pulumi.prod.yaml`. No hard memory limit, no CPU units (see shared-infrastructure note above).
- **Port mapping**: container port 8080, `hostPort: 0` (dynamic host port, bridge mode), protocol tcp, named `http` (`index.ts:897`).
- **Container health check**: none. **ALB target group** `flowcatalyst` (`inhance-fc-{env}-tg`, `index.ts:661-669`): path `/health`, port `traffic-port`, protocol HTTP, `healthyThreshold: 2`, `unhealthyThreshold: 3`; **interval/timeout/matcher not set in IaC** → AWS defaults (30s interval, 5s timeout, HTTP 200 matcher).
- **Desired count / autoscaling**: `desiredCount` stack config — `2` in both nonprod and prod (`Pulumi.nonprod.yaml:3`, `Pulumi.prod.yaml:4`). No autoscaling (see above).
- **IAM roles**: execution role `inhance-{env}-ecs-exec-role` (`index.ts:764-795`, shared with worker and router); task role `inhance-fc-{env}-task-role` (`index.ts:797-826`, shared with worker) — grants `secretsmanager:GetSecretValue` on the RDS master-user secret and `sqs:SendMessage/ReceiveMessage/DeleteMessage/GetQueueAttributes/GetQueueUrl` on the dispatch queue.
- **Log group**: `/ecs/inhance-fc-{env}` (`index.ts:830-834`, `awslogs-stream-prefix: "flowcatalyst"`), retention 30 days.
- **ALB listener rule**: `flowcatalyst-rule`, priority 100, host header = `platformDomain` (`platform-np.inhanceapps.com` np / `platform.inhanceapps.com` prod) → forward to the `flowcatalyst` target group (`index.ts:671-676`). Route53 alias record for `platformDomain` → ALB (`index.ts:1081-1090`).
- **Service Connect**: server, alias `fc-platform:8080`, with a **900s** per-request and idle timeout override for the SC proxy (`index.ts:1029-1044`) — explicitly to avoid the SC proxy's ~15s default 504'ing long dispatch-processing calls and triggering SQS redelivery loops.
- **Dependencies**: RDS (shared instance, via `DB_HOST`/`DB_SECRET_ARN`), ElastiCache Valkey (via `REDIS_URL`, standby leader election only — `STANDBY_ENABLED=false` here so unused today), SQS dispatch queue, no config-service dependency (platform *serves* `/api/config`, doesn't consume it).

### Environment variables and secrets — fc-platform

| Name | Source | Value | Notes |
|---|---|---|---|
| `RUST_LOG` | literal | `info` | shared block (`index.ts:855`) |
| `DB_SECRET_PROVIDER` | literal | `aws` | shared block (`:857`) |
| `DB_SECRET_ARN` | computed | `dbMasterUserSecretArn` (RDS StackReference output) | shared block (`:858`) |
| `DB_HOST` | computed | `dbEndpoint` (RDS StackReference output) | shared block (`:859`) |
| `DB_NAME` | computed | `dbName` (RDS StackReference output) | shared block (`:860`) |
| `REDIS_URL` | computed | `rediss://{cacheEndpoint}:6379` | shared block (`:862`) |
| `DISPATCH_QUEUE_TYPE` | literal | `SQS` | shared block (`:864`) |
| `DISPATCH_QUEUE_URL` | computed | `dispatchQueue.url` | shared block (`:865`) |
| `DISPATCH_QUEUE_REGION` | computed | `region` (stack config `aws:region`, `eu-west-1`) | shared block (`:866`) |
| `PORT` | literal | `8080` | `:909` |
| `PLATFORM_ENABLED` | literal | `true` | `:911` |
| `STREAM_PROCESSOR_ENABLED` | literal | `true` | `:912` |
| `DISPATCH_SCHEDULER_ENABLED` | literal | `false` | `:913` — scheduler runs on worker, not platform |
| `MESSAGE_ROUTER_ENABLED` | literal | `false` | `:914` |
| `STANDBY_ENABLED` | literal | `false` | `:915` |
| `EXTERNAL_BASE_URL` | computed | `https://{platformDomain}` | `:917` |
| `OIDC_ACCESS_TOKEN_TTL` | literal | `3600` | `:918` |
| `OIDC_SESSION_TTL` | literal | `28800` (8h) | `:919` |
| `OIDC_REFRESH_TOKEN_TTL` | literal | `2592000` (30d) | `:920` |
| `FC_WEBAUTHN_RP_ID` | literal | `inhanceapps.com` | `:923` |
| `FC_WEBAUTHN_RP_NAME` | literal | `Inhance` | `:924` |
| `FC_WEBAUTHN_ORIGINS` | computed | `https://platform-np.inhanceapps.com` (np) / `https://platform.inhanceapps.com` (prod) | `:925`, source consts at `:15` |
| `DISPATCH_SCHEDULER_PROCESSING_ENDPOINT` | literal | `http://fc-platform:8080/api/dispatch/process` | `:930-933`, in-VPC Service Connect alias, deliberately not the public ALB domain |
| `SMTP_HOST` | literal | `smtp.sendgrid.net` | `:935` |
| `SMTP_PORT` | literal | `587` | `:936` |
| `SMTP_SECURE` | literal | `false` | `:937` |
| `SMTP_USERNAME` | literal | `apikey` | `:938` |
| `SMTP_FROM` | literal | `mailer@inhancesc.com` | `:939` |
| `FC_STATIC_DIR` | literal | `/app/frontend/dist` | `:941` |
| `FLOWCATALYST_APP_KEY` | SSM | `/inhance/{env}/fc-platform/app-key` | `:870` |
| `FLOWCATALYST_JWT_PRIVATE_KEY` | SSM | `/inhance/{env}/fc-platform/jwt-private-key` | `:871` |
| `FLOWCATALYST_JWT_PUBLIC_KEY` | SSM | `/inhance/{env}/fc-platform/jwt-public-key` | `:872` |
| `FLOWCATALYST_JWT_PREVIOUS_PUBLIC_KEY` | SSM | `/inhance/{env}/fc-platform/jwt-previous-public-key` | `:873` |
| `SMTP_PASSWORD` | SSM | `/inhance/{env}/fc-platform/smtp_password` | `:945` |

31 variables total (22 plain env, 5 SSM secrets counted above as part of the 22... to be precise: **26 plain environment entries + 5 SSM-sourced secrets = 31**).

---

## 2. fc-worker (dispatch scheduler / scheduled-job scheduler)

- **Task family**: `inhance-fc-{env}-worker` (`index.ts:958-1006`, resource `fc-worker-task`).
- **Service**: `inhance-fc-{env}-worker-svc` (`index.ts:1056-1077`, resource `fc-worker-service`).
- **Image / tag logic**: **identical** to platform — `{ecrRepoUrl}:{imageTag}`, same ECR repo `inhance/flowcatalyst`, same floating `:latest` tag. One image, different subsystem toggles.
- **Launch type / arch / network mode**: same as platform — EC2, ARM64, bridge.
- **CPU/memory**: same `memoryReservation: parseInt(memory)` as platform (shares the `memory` stack config value — no independent worker memory knob). No hard limit, no CPU units.
- **Port mapping**: container port 8080, `hostPort: 0`, named `http` — declared "for parity with the platform task def" but the worker service is Service-Connect **client-only** and has no ALB target group, so nothing routes to it (`index.ts:973-975`).
- **Health check**: none — no ALB target group, no container health check. Service health is whatever ECS's own task-state tracking sees.
- **Desired count / autoscaling**: `workerDesiredCount` stack config — `1` in both environments (`Pulumi.nonprod.yaml:4`, `Pulumi.prod.yaml:5`). "Single instance — no standby needed since these are idempotent workers and SQS/DB provide the coordination layer" (`index.ts:955-956`). No autoscaling.
- **IAM roles**: same execution role and task role as platform (`inhance-{env}-ecs-exec-role`, `inhance-fc-{env}-task-role`).
- **Log group**: `/ecs/inhance-fc-{env}-worker` (`index.ts:836-840`, stream prefix `flowcatalyst-worker`), retention 30 days.
- **ALB**: not fronted by the ALB at all — no listener rule, no target group, no public domain.
- **Service Connect**: client-only enrolment in the same namespace as platform/router, so it can resolve `http://fc-platform:8080` in-VPC (`index.ts:1067-1072`). `dependsOn: [fcService]` — "SC clients only learn aliases that exist when their tasks launch" (`:1075-1076`).
- **Security group note**: `fc-worker-sg` (`index.ts:694-699`) has a security-group rule allowing platform→worker on port 8080 "future dashboard access" (`index.ts:701-710`) that is currently unused since the worker publishes no Service Connect server alias.
- **Dependencies**: RDS (shared instance), ElastiCache Valkey (unused today, `STANDBY_ENABLED=false`), SQS dispatch queue (both consumer — Dispatch Scheduler polling PENDING jobs — and producer — publishing full MessagePointers), platform's in-VPC dispatch-process endpoint.

### Environment variables and secrets — fc-worker

| Name | Source | Value | Notes |
|---|---|---|---|
| `RUST_LOG` | literal | `info` | shared block |
| `DB_SECRET_PROVIDER` | literal | `aws` | shared block |
| `DB_SECRET_ARN` | computed | `dbMasterUserSecretArn` | shared block |
| `DB_HOST` | computed | `dbEndpoint` | shared block |
| `DB_NAME` | computed | `dbName` | shared block |
| `REDIS_URL` | computed | `rediss://{cacheEndpoint}:6379` | shared block |
| `DISPATCH_QUEUE_TYPE` | literal | `SQS` | shared block |
| `DISPATCH_QUEUE_URL` | computed | `dispatchQueue.url` | shared block |
| `DISPATCH_QUEUE_REGION` | computed | `region` | shared block |
| `PLATFORM_ENABLED` | literal | `false` | `:987` |
| `STREAM_PROCESSOR_ENABLED` | literal | `false` | `:988` |
| `MESSAGE_ROUTER_ENABLED` | literal | `false` | `:989` |
| `DISPATCH_SCHEDULER_ENABLED` | literal | `true` | `:990` |
| `FC_SCHEDULED_JOB_ENABLED` | literal | `true` | `:991` |
| `STANDBY_ENABLED` | literal | `false` | `:992` |
| `DISPATCH_SCHEDULER_PROCESSING_ENDPOINT` | literal | `http://fc-platform:8080/api/dispatch/process` | `:996-999` |
| `FLOWCATALYST_APP_KEY` | SSM | `/inhance/{env}/fc-platform/app-key` | shared secrets block |
| `FLOWCATALYST_JWT_PRIVATE_KEY` | SSM | `/inhance/{env}/fc-platform/jwt-private-key` | shared secrets block |
| `FLOWCATALYST_JWT_PUBLIC_KEY` | SSM | `/inhance/{env}/fc-platform/jwt-public-key` | shared secrets block |
| `FLOWCATALYST_JWT_PREVIOUS_PUBLIC_KEY` | SSM | `/inhance/{env}/fc-platform/jwt-previous-public-key` | shared secrets block |

16 environment entries + 4 SSM secrets = **20 variables total**.

---

## 3. fc-router (message router)

- **Task family**: `inhance-fcr-{env}` (`index.ts:1464-1528`, resource `router-task`).
- **Service**: `inhance-fcr-{env}-svc` (`index.ts:1532-1565`, resource `router-service`).
- **Image / tag logic**: `{routerEcrRepoUrl}:{routerImageTag}` — separate ECR repo `inhance/fc-router` (`index.ts:301-313`, same shared-repo-across-envs pattern as `inhance/flowcatalyst`). `routerImageTag` = stack config `routerImageTag`, default `"latest"` (`:38`) — **nonprod doesn't override it** (floats `:latest`); **prod pins it to `"prod"`** (`Pulumi.prod.yaml:11`).
- **Launch type / arch / network mode**: EC2, ARM64, bridge — same as platform/worker.
- **CPU/memory**: `memoryReservation: parseInt(routerMemory)` — stack config `routerMemory`, `512` MB in both environments. No hard limit, no CPU units (`routerCpu` stack config exists, `256` in both envs, but is never applied — same dead-config pattern as platform/worker's `cpu`).
- **Port mapping**: container port 8080, `hostPort: 0`, named `http` (`index.ts:1480-1488`).
- **Container health check**: none. **ALB target group** `fc-router` (`inhance-fcr-{env}-tg`, `index.ts:1355-1368`): path `/health`, port `traffic-port`, protocol HTTP, `healthyThreshold: 2`, `unhealthyThreshold: 3`; interval/timeout/matcher not set → AWS defaults.
- **Desired count / autoscaling**: `routerDesiredCount` stack config — `1` in both environments (`Pulumi.nonprod.yaml:10`, `Pulumi.prod.yaml:8`). No autoscaling.
- **IAM roles**: execution role shared (`inhance-{env}-ecs-exec-role`); **own** task role `inhance-fcr-{env}-task-role` (`index.ts:1418-1431`) — "Router needs SQS access for message consumption but no DB or Secrets Manager" (`:1416-1417`). Policy grants `sqs:ReceiveMessage/DeleteMessage/ChangeMessageVisibility/GetQueueAttributes/GetQueueUrl/SendMessage` on `Resource: "*"` (`:1433-1452`, blanket — not scoped to the dispatch queue or any specific ARN, since the router's real queues come from whatever `FLOWCATALYST_CONFIG_URL` returns).
- **Log group**: `/ecs/inhance-fcr-{env}` (`index.ts:1456-1460`, stream prefix `fc-router`), retention 30 days.
- **ALB listener rule**: `router-rule`, priority 300, host header = `routerDomain` (`qa-fc-router.inhanceapps.com` np / `fc-router.inhanceapps.com` prod) → forward to `fc-router` target group (`index.ts:1372-1388`). Route53 alias for `routerDomain` → ALB (`index.ts:1569-1580`). Nonprod additionally provisions/validates its own ACM cert and attaches it as an SNI cert on the shared HTTPS listener (`index.ts:1327-1351`).
- **Service Connect**: server, alias `fc-router:8080`; also gets client access to the other aliases (`fc-platform:8080`) in the same namespace for config-sync and dispatch-callback traffic (`index.ts:1553-1561`).
- **Dependencies**: **no RDS, no Secrets Manager** (DB-less by design per the inline comment). Config service: `FLOWCATALYST_CONFIG_URL` — nonprod points at the **Integral staging platform** (`https://staging-integral.inhanceapps.com/api/config`, `Pulumi.nonprod.yaml:13`), not at the FlowCatalyst platform's own domain, despite the code default (`routerConfigUrl` const, `index.ts:39`) being `https://{platformDomain}/api/config`; prod points at **four** Integral instances' config endpoints, comma-separated (`amsa`, `pilot-value-logistics`, `ceramic`, `spar` — `Pulumi.prod.yaml:15`). No `REDIS_URL` is set for the router at all (standby disabled; if standby were ever turned on without setting one, Java's own default `redis://127.0.0.1:6379` would apply — almost certainly wrong for this deployment). SQS queues are resolved per-queue from the config service response, not from the shared dispatch queue.
- **Consuming the platform's own dispatch queues (R3′, 2026-09-13, `docs/spec/router-config-auth.md`):** add the platform's document URL (`https://{platformDomain}/api/dispatch/router-config`) to `FLOWCATALYST_CONFIG_URL`, set `FC_ROUTER_PLATFORM_URL` to the platform's base URL, and give the task `FC_ROUTER_CLIENT_ID` / `FC_ROUTER_CLIENT_SECRET` for an OAuth client whose SERVICE principal holds the built-in `platform:router` role (create an application for the router, `POST /api/applications/{id}/provision-service-account`, `GET /api/service-accounts/code/app:{applicationCode}` for the service account's id, then `PUT /api/service-accounts/{id}/roles` with `["platform:application-service", "platform:router"]`). The credential is sent only to that origin, never to the Integral URLs. This replaces the withdrawn Service Connect alias for the internal listener; it does mean the fc-router task carries one secret, contrary to the "no Secrets Manager" comment at `:1416-1417`.

### Environment variables and secrets — fc-router

| Name | Source | Value | Notes |
|---|---|---|---|
| `RUST_LOG` | literal | `info` | `:1500` |
| `API_PORT` | literal | `8080` | `:1501` |
| `AWS_REGION` | computed | `region` (`eu-west-1`) | `:1502` |
| `MESSAGE_ROUTER_ENABLED` | literal | `true` | `:1510` |
| `PLATFORM_ENABLED` | literal | `false` | `:1511` |
| `FLOWCATALYST_CONFIG_URL` | stack config | np: `https://staging-integral.inhanceapps.com/api/config`; prod: 4 comma-separated Integral `/api/config` URLs (see above) | `:1513`, default (unused, both envs override) `https://{platformDomain}/api/config` |
| `FLOWCATALYST_CONFIG_INTERVAL` | literal | `300` | `:1514` |
| `FLOWCATALYST_STANDBY_ENABLED` | literal | `false` | `:1516` |
| `AUTH_MODE` | literal | `NONE` | `:1518` |
| `NOTIFICATION_TEAMS_ENABLED` | literal | `true` | `:1520` |
| `NOTIFICATION_TEAMS_WEBHOOK_URL` | literal — **secret, see below** | (secret — literal in IaC, not copied) | `:1521`, same literal value used in both np and prod (block is unconditional) |
| `NOTIFICATION_MIN_SEVERITY` | literal | `WARNING` | `:1522` |
| `NOTIFICATION_BATCH_INTERVAL` | literal | `300` | `:1523` |

13 environment entries, 0 SSM/Secrets Manager secrets, **1 literal secret** — **13 variables total**.

**Changed in the IaC on 2026-09-14** (`compute/index.ts` router task,
`Pulumi.{nonprod,prod}.yaml`, `docs/fc-router-platform-credential.md` in the
IaC repo): `FC_ROUTER_PLATFORM_URL=http://fc-platform:8080` (literal, the
Service Connect alias the dispatch callback already uses); `FC_ROUTER_CLIENT_ID`
/ `FC_ROUTER_CLIENT_SECRET` from SSM `/inhance/{env}/fc-router/{client-id,client-secret}`
(the execution role's existing `parameter/inhance/{env}/*` grant covers them);
`routerConfigUrl` gains `,http://fc-platform:8080/api/dispatch/router-config` in
both stacks; the container's soft `memoryReservation` became a hard `memory`
limit of 512 MB in both environments (owner ruling). The SSM parameters and
the platform-side OAuth client are created by hand per environment — the
procedure and the `aws ssm put-parameter` commands are in that IaC doc.

---

## Secrets committed as literals in the IaC

Only one was found across the three FlowCatalyst services (the HR/RFP client
apps' `FLOWCATALYST_*` secrets are all SSM-sourced, not literals, and are out
of scope as noted above):

- **`NOTIFICATION_TEAMS_WEBHOOK_URL`** — `compute/index.ts:1521`, the
  fc-router task definition. A Power Automate "trigger a flow" URL carrying a
  `sig=` query-string signature, used identically for both the `np` and
  `prod` stacks (the surrounding code is not inside an `env === "..."`
  branch). Anyone with read access to this IaC repo — or to the ECS task
  definition/console — can post to Teams as the FlowCatalyst router. Move to
  SSM (pattern: `/inhance/{env}/fc-router/notify-webhook-url`) and reference
  it as a `secrets` entry the way platform/worker's SSM-sourced secrets
  already do.

---

## Java compatibility

Checked against `server/src/main/java/io/flowcatalyst/server/Env.java`,
`EnvReader.java`, `Logging.java`, `dbsecret/DbSecretMode.java`,
`platform/shared/encryption/Encryption.java`,
`platform/shared/auth/SigningKeys.java`,
`platform/mail/SmtpMailService.java`, `platform/passkey/PasskeyService.java`,
`platform/auth/token/TokenIssuer.java`, `Frontend.java`,
`router/queue/QueueFactory.java`, `router/manager/RouterServer.java`,
`docs/spec/router-env.md`, `docs/spec/dispatch-seam.md` §11. No Java code was
changed to produce this table.

| Variable | Services | Java status | Detail |
|---|---|---|---|
| `RUST_LOG` | platform, worker, router | **aliased** | `Logging.resolveLevels` — consulted **only** when `FC_LOG_LEVEL` is unset; parses as a `tracing`-style filter (bare token = root level, `fc_router=<level>` = `io.flowcatalyst.router`'s level, anything else logged as ignored). |
| `DB_SECRET_PROVIDER` | platform, worker | **read** | `DbSecretMode.resolve` — must be `"aws"` or startup throws. |
| `DB_SECRET_ARN` | platform, worker | **read** | `DbSecretMode.resolve`. |
| `DB_HOST` | platform, worker | **read** | `Env.resolveDatabaseUrl` / `DbSecretMode.resolve`. |
| `DB_NAME` | platform, worker | **read** | `Env.resolveDatabaseUrl` (default `flowcatalyst` if absent) / `DbSecretMode.resolve`. |
| `REDIS_URL` | platform, worker | **aliased (last resort)** | `Env.standbyRedisUrl` — last in the chain `FC_STANDBY_REDIS_URL` → `FLOWCATALYST_STANDBY_REDIS_URL` → `FLOWCATALYST_REDIS_URL` → `REDIS_URL`. Only consulted when `STANDBY_ENABLED` (or an alias) is true — false everywhere in this IaC today, so effectively unread at runtime. |
| `DISPATCH_QUEUE_TYPE` | platform, worker | **⚠ IGNORED — unknown to Java** | No reference anywhere in `server/src/main/java`. Java has no equivalent knob; its dispatch queue is whatever `FC_DEFAULT_BROKER`/router config names, not a single "the dispatch queue" setting. |
| `DISPATCH_QUEUE_URL` | platform, worker | **⚠ IGNORED — unknown to Java** | Same — no reference anywhere. |
| `DISPATCH_QUEUE_REGION` | platform, worker | **⚠ IGNORED — unknown to Java** | Same — no reference anywhere. |
| `FLOWCATALYST_APP_KEY` | platform, worker | **read** | `Encryption.ENV_APP_KEY`, read via `Env.appKey`. |
| `FLOWCATALYST_JWT_PRIVATE_KEY` | platform, worker | **read** | `SigningKeys.INLINE_PEM_VARS` (first of two inline-PEM sources). |
| `FLOWCATALYST_JWT_PUBLIC_KEY` | platform, worker | **⚠ IGNORED — unknown to Java** | Only `FLOWCATALYST_JWT_PREVIOUS_PUBLIC_KEY` is read (`Env.jwtPreviousPublicKey`); the **current** public key has no reference anywhere in `server/src/main/java`. Presumably Java derives the public key from the private key rather than reading it separately — needs an owner ruling on whether that derivation is actually wired up, since the IaC clearly expects this variable to matter (it is fetched from SSM on every boot). |
| `FLOWCATALYST_JWT_PREVIOUS_PUBLIC_KEY` | platform, worker | **read** | `Env.jwtPreviousPublicKey` / `SigningKeys.normalizePem`; dropped unless it parses as a real PEM. |
| `PORT` | platform | **aliased** | `Env.apiPort` — third in `FC_API_PORT` → `API_PORT` → `PORT`. |
| `PLATFORM_ENABLED` | platform, worker, router | **aliased** | `Env.platformEnabled` — `FC_PLATFORM_ENABLED` → `PLATFORM_ENABLED`, default `true`. |
| `STREAM_PROCESSOR_ENABLED` | platform, worker | **aliased** | `Env.streamEnabled` — `FC_STREAM_PROCESSOR_ENABLED` → `STREAM_PROCESSOR_ENABLED`. |
| `DISPATCH_SCHEDULER_ENABLED` | platform, worker | **aliased** | `Env.schedulerEnabled` — `FC_SCHEDULER_ENABLED` → `DISPATCH_SCHEDULER_ENABLED`. |
| `MESSAGE_ROUTER_ENABLED` | platform, worker, router | **aliased** | `Env.routerEnabled` — `FC_ROUTER_ENABLED` → `MESSAGE_ROUTER_ENABLED`. |
| `STANDBY_ENABLED` | platform, worker | **aliased** | `Env.standbyEnabled` — third in `FC_STANDBY_ENABLED` → `FLOWCATALYST_STANDBY_ENABLED` → `STANDBY_ENABLED`. |
| `EXTERNAL_BASE_URL` | platform | **aliased** | `Env.jwtIssuer` — third in `FC_JWT_ISSUER` → `FC_EXTERNAL_BASE_URL` → `EXTERNAL_BASE_URL`. |
| `OIDC_ACCESS_TOKEN_TTL` | platform | **aliased** | `Env.jwtAccessTokenTtlSeconds` — `FC_JWT_ACCESS_TOKEN_TTL_SECS` → `OIDC_ACCESS_TOKEN_TTL`, default 3600, via `EnvReader.longAlias`. Fixed 2026-09-12 (`docs/spec/deployed-dispatch.md` §4). |
| `OIDC_SESSION_TTL` | platform | **aliased** | `Env.sessionTtlSeconds` — `FC_SESSION_TTL_SECS` (new) → `OIDC_SESSION_TTL`, default 86400 (24h). Threaded into `TokenIssuer.Config.sessionTtlSeconds` (the session JWT `exp`) and `SessionCookie`'s `Max-Age`, kept equal. Fixed 2026-09-12 — **this shortens prod sessions from 24h to 8h at deploy**, the owner's decision (`docs/spec/deployed-dispatch.md` §4). |
| `OIDC_REFRESH_TOKEN_TTL` | platform | **aliased** | `Env.refreshTokenTtlSeconds` — `FC_REFRESH_TOKEN_TTL_SECS` (new) → `OIDC_REFRESH_TOKEN_TTL`, default 604800 (7d). Threaded through `Platform` into `RefreshToken.issue`'s TTL at fresh issuance and `OAuthState`/`RefreshRotation`'s TTL for `/oauth/token` and rotation. **Two deliberate carve-outs**: `GrantStore`'s null-`expires_at` hydration fallback keeps the historical 7-day constant (a legacy row's expiry as it *was*, not a new TTL), and rotation still never extends a family's cap regardless of this value. Fixed 2026-09-12. |
| `FC_WEBAUTHN_RP_ID` | platform | **read** | `Env.webauthnRpId` / `PasskeyService.Config.fromEnv`. |
| `FC_WEBAUTHN_RP_NAME` | platform | **⚠ IGNORED — unknown to Java** | `PasskeyService.Config.fromEnv(env, displayName)` takes `displayName` as a **caller-supplied parameter** (`Platform.java:280`, sourced from `mfaBranding.platformName()`), never from this env var. |
| `FC_WEBAUTHN_ORIGINS` | platform | **read** | `Env.webauthnOrigins` (comma-separated, trimmed, blanks dropped). |
| `DISPATCH_SCHEDULER_PROCESSING_ENDPOINT` | platform, worker | **aliased** | `Env.dispatchProcessingEndpoint` — `FC_DISPATCH_PROCESSING_ENDPOINT` → `DISPATCH_SCHEDULER_PROCESSING_ENDPOINT`, via `EnvReader.firstSet`; unset, still defaults to `http://localhost:{apiPort}/api/dispatch/process`. Fixed 2026-09-12 (`docs/spec/deployed-dispatch.md` §4) — the worker task now resolves the real `fc-platform` Service Connect alias instead of the wrong localhost default. |
| `SMTP_HOST` | platform | **aliased** | `SmtpMailService.Config` — `FC_SMTP_HOST` → `SMTP_HOST`. |
| `SMTP_PORT` | platform | **aliased** | `FC_SMTP_PORT` → `SMTP_PORT`, default 587. |
| `SMTP_SECURE` | platform | **aliased** | `FC_SMTP_SECURE` → `SMTP_SECURE`. |
| `SMTP_USERNAME` | platform | **aliased** | `FC_SMTP_USERNAME` → `SMTP_USERNAME`. |
| `SMTP_FROM` | platform | **aliased** | `FC_SMTP_FROM` → `SMTP_FROM`, default `noreply@flowcatalyst.local` if neither set (IaC always sets it). |
| `SMTP_PASSWORD` | platform | **aliased** | `FC_SMTP_PASSWORD` → `SMTP_PASSWORD`. |
| `FC_STATIC_DIR` | platform | **⚠ IGNORED — unknown to Java** | `Frontend.java` serves the SPA from the **classpath** (`frontend/`, embedded at build time) unconditionally — no code path reads `FC_STATIC_DIR` or serves from an external directory. Harmless today only because the embedded copy is expected to match; if the deploy pipeline ever relies on mounting/refreshing `/app/frontend/dist` independently of the image, Java silently ignores that. |
| `FC_SCHEDULED_JOB_ENABLED` | worker | **read (canonical name)** | `Env.scheduledJobEnabled` — this IaC name **is** the Java canonical name (alias is `SCHEDULED_JOB_SCHEDULER_ENABLED`, not set here). |
| `FLOWCATALYST_CONFIG_URL` | router | **read** | `Env.routerConfigUrl`; comma-separated multi-URL supported (`HttpConfigSource.create`, `raw.split(",")`) — matches prod's 4-URL value. |
| `FLOWCATALYST_CONFIG_INTERVAL` | router | **aliased** | `Env.routerConfigIntervalRaw` — `FC_ROUTER_CONFIG_INTERVAL_SECONDS` → `FLOWCATALYST_CONFIG_INTERVAL`; parsed by `RouterServer.parseConfigPollInterval`, WARNs and falls back to 300 if set-but-invalid. |
| `FLOWCATALYST_STANDBY_ENABLED` | router | **aliased** | `Env.standbyEnabled` — same three-way alias as `STANDBY_ENABLED` above. |
| `AUTH_MODE` | router | **read** | `Env.routerAuthMode`; `NONE` (case-insensitive) forces router BasicAuth off. |
| `NOTIFICATION_TEAMS_ENABLED` | router | **read** | Raw string carried as `Env.routerNotifyTeamsEnabledRaw`; `WarningNotifier.create` — **deliberate deviation from Go/Rust**: an explicit `false` always disables even with a URL set (Go/Rust: `false` is ignored once a URL is configured, so this value is a no-op there but load-bearing in Java). |
| `NOTIFICATION_TEAMS_WEBHOOK_URL` | router | **aliased** | `Env.routerNotifyWebhookUrl` — `FC_NOTIFY_WEBHOOK_URL` → `NOTIFICATION_TEAMS_WEBHOOK_URL`. |
| `NOTIFICATION_MIN_SEVERITY` | router | **aliased** | `Env.routerNotifyMinSeverity` — `FC_NOTIFY_MIN_SEVERITY` → `NOTIFICATION_MIN_SEVERITY`; accepts `WARN` and `WARNING`. |
| `NOTIFICATION_BATCH_INTERVAL` | router | **aliased** | `Env.routerNotifyBatchIntervalSeconds` — `FC_NOTIFY_BATCH_INTERVAL_SECONDS` → `NOTIFICATION_BATCH_INTERVAL`, default 300. |
| `API_PORT` | router | **aliased** | Same chain as platform's `PORT` — `FC_API_PORT` → `API_PORT` → `PORT`. |
| `AWS_REGION` | router | **read (implicit)** | Not read by `Env.java` at all; consumed by the AWS SDK for Java's own default region-resolution chain, same as the AWS SDK the Rust/Go binaries use — functionally equivalent, not a `Env`-level "read". |

### Ignored/unknown variables — Phase 1 work list

**Fixed 2026-09-12** (`docs/spec/deployed-dispatch.md` §4,
`docs/go-mirror/2026-09-11-deployment-env-handoff.md`): `OIDC_ACCESS_TOKEN_TTL`,
`OIDC_SESSION_TTL`, `OIDC_REFRESH_TOKEN_TTL` and
`DISPATCH_SCHEDULER_PROCESSING_ENDPOINT` are now all read (see their rows
above); Go mirrors the same four changes. Still outstanding:

**fc-platform** (6): `DISPATCH_QUEUE_TYPE`, `DISPATCH_QUEUE_URL`,
`DISPATCH_QUEUE_REGION`, `FLOWCATALYST_JWT_PUBLIC_KEY`, `FC_WEBAUTHN_RP_NAME`,
`FC_STATIC_DIR`.

**fc-worker** (3): `DISPATCH_QUEUE_TYPE`, `DISPATCH_QUEUE_URL`,
`DISPATCH_QUEUE_REGION`.

**fc-router** (0): every variable this IaC sets for the router task is read
or aliased by Java today (see `docs/spec/router-env.md`, which this table
cross-checks and agrees with).

Recommended Phase 1 order (revised 2026-09-12, the TTL/callback fix now
landed): the `DISPATCH_QUEUE_*` trio and `FLOWCATALYST_JWT_PUBLIC_KEY`
(confirm Java's public-key derivation path actually works without ever
reading the deployed public key material) next, then the smaller items
(`FC_WEBAUTHN_RP_NAME`, `FC_STATIC_DIR`).

### Sizing signal

`docs/spec/jvm-memory.md` §1 fences the heap and direct memory off the
container's `memory` (hard limit) — this task definition value is the only
number an operator sets, and the JVM never asks for more. `docs/spec/jvm-memory.md`
§2.1 puts the answer to "when do we need more memory" on the metrics-port
`/metrics` scrape rather than only on an `OutOfMemoryError` stop reason,
which by definition arrives after the container already died: raise the
container's `memory` when `jvm_memory_pool_collection_used_bytes` for the
old-generation pool exceeds ~75% of `jvm_memory_max_bytes{area="heap"}` for
ten minutes, or when `rate(jvm_gc_collection_seconds_sum[5m])` exceeds 0.1
(the JVM spending more than 6 seconds of every 5 minutes collecting).

---

## 4. fc-fnhost (function host) — new service, not part of the Phase 0 inventory above

Everything above this section was produced by reading the owner's actual
Pulumi IaC (`../inhance/iac`) — a real deployed-service inventory. The
function host (package D slice D5, `docs/spec/function-host-process.md`) has
no entry there yet: it is documented here ahead of that IaC work so whoever
adds the ECS task definition (or equivalent) has the env table, ports and
service-account recipe in one place, in the same shape as §§1–3 above. Treat
every claim below as "what the Java code expects", not as "what is deployed".

### Environment variables (`HostEnv`, `docs/spec/function-host-reconciler.md`
§1.4, extended by `function-host-listener.md` §2 and `function-host-process.md`
§2)

| Name | Default | Notes |
|---|---|---|
| `FC_FN_POOL` | `default` | a [DnsLabel] — which pool of hosts this instance belongs to; the heartbeat's own identity |
| `FC_FN_PLATFORM_URL` | *(required)* | the platform's base URL — control-plane API, `/oauth/token`, `/.well-known/jwks.json` |
| `FC_FN_CLIENT_ID` / `FC_FN_CLIENT_SECRET` | *(required)* | this host's own OAuth client credentials against the platform (see the service-account recipe below) |
| `FC_FN_HOST_ID` | `<hostname>-<6 random base32>` | 1-100 chars of `[A-Za-z0-9._:-]`; explicit values are validated, not sanitised |
| `FC_FN_SIGNATURES` | `required` | `off`/`required` — `off` additionally requires `FLOWCATALYST_DEV_MODE=true` (a safety rail, not a knob for production) |
| `FC_FN_TRUST_ROOT` | — | Sigstore trust-root override, same variable the platform's own publish-side signature verification reads |
| `FC_FN_MAX_LOADED` | `200` | the [FunctionRegistry]'s capacity |
| `FC_FN_CACHE_DIR` | `${java.io.tmpdir}/fc-fn-cache` | fetched/verified artifact cache — `function-host/Dockerfile` points this at a named volume instead |
| `FC_FN_PORT` | `8080` | the PRIVATE function listener (`/functions/...`) — Service Connect only, never internet-reachable |
| `FC_FN_PUBLIC_PORT` | `8081` | the PUBLIC listener (`docs/spec/function-public-routes.md` §3: `Host`-routed, no by-address/versioned access) — the load balancer's target; `0` binds an ephemeral port (tests only); `off` disables the public listener entirely (a deployment that publishes no public routes may turn it off) |
| `FC_FN_TRUSTED_PROXIES` | RFC 1918 + loopback + IPv6 ULA/loopback | comma-separated CIDR list; the public listener's `remoteAddress` trusts the right-most `X-Forwarded-For` entry only when the TCP peer matches one of these — set this to the load balancer's own subnet(s) if they fall outside the default |
| `FC_FN_MAX_CONCURRENCY` | `512` | host-global invocation permit ceiling |
| `FC_DRAIN_TIMEOUT_SECONDS` | `60` | how long `close()` waits for in-flight requests before closing anyway |
| `FC_METRICS_PORT` | `9090` | the observability listener — `/health`, `/ready`, `/metrics` |
| `FC_EXIT_AFTER_START` | `false` | the server's AOT-training convention (exit 0 right after a successful start) — used by a training/smoke run, never a real deployment |

Not `HostEnv` fields, but read by the same composition root transitively
through the `server` module: `FLOWCATALYST_DEV_MODE` (above), and whatever
`Logging.init` reads for log shape (`FC_LOG_FORMAT`/`LOG_FORMAT`,
`FC_LOG_LEVEL`/`RUST_LOG`) — same two variables the platform/router tasks
already carry.

Not a `HostEnv` field either, but set by `function-host/docker/entrypoint.sh`
itself (a `docker/jvm-opts.sh` variable, not an application one —
`docs/spec/jvm-memory.md` §4):

| Name | Default | Notes |
|---|---|---|
| `FC_JVM_METASPACE_PERCENT` | `50` | integer 10–70, the percent of the container's memory limit reserved for `-XX:MaxMetaspaceSize`; an operator override outside that range (or non-integer) fails the container start rather than falling back silently. `fc-server` never sets this and is unaffected. |

Rule of thumb for sizing a pool against this default: for functions shaped
like the benchmark's "typical" fixture (a realistic-worst-case shaded
dependency set, ~700 classes/instance, ~4.4 MB metaspace/instance measured in
`docs/function-runner-report.md` §Performance), the number of such functions
one host can hold before hitting the metaspace fence is approximately

```
reserve MiB   = max(64, 5% × metaspace MiB)                 the headroom guard's own reserve
functions    ≈ (metaspace MiB − 35 − reserve MiB) ÷ 4.4
```

where `metaspace MiB = FC_JVM_METASPACE_PERCENT% of the container's memory
limit`, 35 MiB is the fixed metaspace baseline every host pays once
regardless of function count, and the reserve subtraction is
`function-host-process.md` §3's headroom guard: a load is refused before it
is ever attempted once free metaspace drops below that reserve, which is
what keeps a host from ever running its own per-function OOM-recovery path
out of room (the defect `docs/function-runner-report.md`'s "Metaspace at
50%" section root-caused and fixed). At the 50% default this is ≈35
functions on a 512 MiB host, ≈210 on 2 GiB, and ≈434 on 4 GiB — the 2 GiB/4
GiB figures were directly measured against a real container running the
current image (212 and 438 loaded, `docs/function-runner-report.md`'s
re-measured rows), not just predicted from the slope. **Capacity is now
measurably lower than the raw fence** (226/458 before the guard existed) —
the reserve trades a small amount of capacity for never hitting the wall at
all. Lighter functions (fewer shaded classes) cost proportionally less; this
formula is only calibrated against that one
fixture, cited as a starting point, not a guarantee for every workload.

### Ports

`8080` (the PRIVATE function listener, HTTP/1.1 + h2c), `8081` (the PUBLIC
listener, spec `function-public-routes.md` §3, same protocol) and `9090`
(observability listener, HTTP/1.1) — the same split as
`fc-platform`/`fc-router`'s `8080` + the shared metrics convention, now on
THREE entirely independent Vert.x servers sharing one process
(`function-host-process.md` §2 P7: a saturated function port must never make
`9090` look dead; the same isolation now also means a saturated PUBLIC port
must never starve the PRIVATE one, or vice versa — they share permits and
the registry, never an event loop).

**The load balancer's target group is `8081` (public) ONLY.** `8080`
(private) is Service Connect only and MUST NOT be reachable from the
internet — it serves by address (`/functions/{address}/...`) and the
versioned smoke-test form (`/functions/{address}:{n}/...`), neither of which
carries the public listener's `Host`-based route gate at all: anyone who can
reach `8080` can invoke any function on the host directly, bypassing every
domain claim/route the platform ever validated. Security-group / Service
Connect configuration must enforce this the same way `fc-platform`'s own
internal listener (§0/§1 above) is never exposed. `8081` MAY be turned off
entirely (`FC_FN_PUBLIC_PORT=off`) for a deployment that publishes no public
routes at all.

**Alias prefixes** (spec `function-zones-and-aliases.md` §3-§4, package J3): a route that opts a
prefix in (e.g. `qa`) is reachable at a hostname the platform never claims or provisions on its
own — `qa-myapp.acme.com` alongside `myapp.acme.com`. The load balancer's listener for `8081` needs
a forwarding rule that matches `*.<zone>` (not just the exact claimed hostnames), and its TLS
certificate must be a wildcard for the zone (or cover the specific alias-prefixed names the owner
plans to use) — this is owner IaC, not something the platform provisions. `fcdev` needs no such
setup: `*.localhost` already resolves to loopback on macOS/Linux, so `qa-hello.localhost:8091`
works with no certificate and no forwarding rule at all.

### Health checks

`function-host/Dockerfile`'s own `HEALTHCHECK` already points at `/health`
on `FC_METRICS_PORT` — no IaC change needed to wire this up. `/health` is
liveness (`function-host-process.md` §2/§3 item 3): 200 unconditionally
before start-up completes (a slow first reconcile/load must not get the
task killed mid-boot), and after start-up has completed, 503
`LISTENER_DOWN`/`RECONCILER_DOWN` if the function listener is not bound or
the reconcile loop's thread has died — either is ECS's signal to replace the
task, distinct from `/ready` (which additionally reflects control-plane
outages and draining, and is what a load balancer or orchestrator readiness
gate should point at instead, once one exists for this service).

### Service Connect alias convention: `fn-<pool>`

`FC_FN_POOL_URL`'s own default (`PoolUrlTemplate.DEFAULT`,
`http://fn-{pool}:8080`, `docs/spec/function-invocation.md` §4 R8) assumes a
Service Connect alias named `fn-<pool>` per pool of function hosts — the
platform's own `FunctionTriggerSync` resolves a webhook subscription's
target through this template at promote time. A deployment with a single
pool (or one that sets `FC_FN_POOL_URL` to a fixed host) never needs the
`{pool}` placeholder at all; a deployment with several pools (e.g. one for
warm, latency-sensitive functions and one for everything else) names each
Service Connect alias `fn-<pool-label>` to match.

### Service account: `platform:function-host` recipe

Same shape as the router's own recipe (§3 above, "Consuming the platform's
own dispatch queues"): create an application for the host, provision its
service account, then grant it the built-in `platform:function-host` role
(seeded, `Seeder`'s own role list) alongside `platform:application-service`:

```
POST /api/applications/{id}/provision-service-account
GET  /api/service-accounts/code/app:{applicationCode}          # to get the service account id
PUT  /api/service-accounts/{id}/roles
     ["platform:application-service", "platform:function-host"]
```

The resulting client id/secret become `FC_FN_CLIENT_ID`/`FC_FN_CLIENT_SECRET`
(SSM, pattern `/inhance/{env}/fc-fnhost/{client-id,client-secret}`, same as
the router's own credential) — sent only to `FC_FN_PLATFORM_URL`, never
anywhere else, same reasoning as the router's own credential note.

### Sizing note

No throughput numbers exist for this service yet (unlike §"Sizing signal"
above, which is real bench data for `fc-server`) — size it the same way
`fc-platform`/`fc-worker` started (`memoryReservation`, no hard limit,
ARM64/EC2/bridge) and revisit once a host has run under real function-load
data; `docs/spec/jvm-memory.md` §4 is the memory-fence half of that story
(heap/direct/metaspace split from one container limit).

### Image registry — ECR through GitHub OIDC (R14, `function-artifact-upload.md` §6)

`.github/workflows/fnhost-image.yml` pushes the image once three **repository variables**
(Settings → Secrets and variables → Actions → Variables) are set; until then its push steps are
skipped.

| Variable | Example | |
|---|---|---|
| `FNHOST_AWS_ROLE_ARN` | `arn:aws:iam::<account>:role/gha-fnhost-ecr-push` | assumed through GitHub OIDC — no stored keys |
| `FNHOST_AWS_REGION` | `af-south-1` | the ECR repository's region |
| `FNHOST_ECR_REPOSITORY` | `flowcatalyst/fc-fnhost` | repository name, without the registry host |

Tags pushed: `<git sha>` always; `latest` from `main`; `<tag>` on a tag build. A task definition
should pin the sha (or the tag), never `latest`.

The role's trust policy admits this repository only, and its permissions are the push set on the
one repository:

```json
{ "Effect": "Allow",
  "Principal": { "Federated": "arn:aws:iam::<account>:oidc-provider/token.actions.githubusercontent.com" },
  "Action": "sts:AssumeRoleWithWebIdentity",
  "Condition": {
    "StringEquals": { "token.actions.githubusercontent.com:aud": "sts.amazonaws.com" },
    "StringLike":   { "token.actions.githubusercontent.com:sub": "repo:flowcatalyst/flowcatalyst-javalin:*" } } }
```

`ecr:GetAuthorizationToken` on `*`; `ecr:BatchCheckLayerAvailability`, `ecr:InitiateLayerUpload`,
`ecr:UploadLayerPart`, `ecr:CompleteLayerUpload`, `ecr:PutImage` on the repository's ARN.

Function **artifacts** (the jars) do not go here: they are uploaded to the platform, which writes
them to `FC_FN_ARTIFACT_STORE` (`file:///…` or `s3://bucket/prefix`) — the platform's task role
needs `s3:GetObject`, `s3:PutObject`, `s3:DeleteObject` on the prefix and `s3:ListBucket` on the
bucket. Hosts need no storage permissions at all.
