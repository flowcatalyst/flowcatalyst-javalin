# Go hand-off — honour the deployed environment (2026-09-11)

For the Go agent. The Java port is making the same changes, so both sides
behave the same until switchover (owner ruling 2026-09-11). Evidence and
reasoning: `docs/deployments.md` (the ECS task definitions, from
`../inhance/iac`) and `docs/spec/deployed-dispatch.md`.

Each item: the variable is set in the IaC, and Go does not read it today.

## 1. `DISPATCH_SCHEDULER_PROCESSING_ENDPOINT` — alias of `FC_DISPATCH_PROCESSING_ENDPOINT`

`internal/server/envcfg.go:249` reads only `FC_DISPATCH_PROCESSING_ENDPOINT`,
defaulting to `http://localhost:<port>/api/dispatch/process`. Both the worker
and the platform set `DISPATCH_SCHEDULER_PROCESSING_ENDPOINT=http://fc-platform:8080/api/dispatch/process`.

This is the internal callback, not the delivery target. The scheduler stamps
it as each message's `mediation_target`, the router POSTs `{messageId}` to it,
and the platform delivers to the subscription's real target. On ECS the
router is a separate task, so the localhost default would make the router
POST to itself. Change: `envFirst("FC_DISPATCH_PROCESSING_ENDPOINT", "DISPATCH_SCHEDULER_PROCESSING_ENDPOINT", …)`,
keeping the localhost default.

## 2. OIDC token lifetimes

The platform sets `OIDC_SESSION_TTL=28800` (8 h), `OIDC_ACCESS_TOKEN_TTL=3600`
and `OIDC_REFRESH_TOKEN_TTL=2592000` (30 d), all in seconds. Go ignores all
three: the session cookie is fixed at 24 h, and the access/refresh TTLs come
from `FC_JWT_ACCESS_TOKEN_TTL_SECS` and defaults. Change: read each, as an
alias of the corresponding `FC_*` setting where one exists, with today's
values as defaults when unset. **This shortens prod sessions from 24 h to 8 h
at deploy** — the owner's decision, to be announced.

## 3. Deployed dispatch — settled, but **Java builds it first**

Outside `FC_DEFAULT_BROKER=postgres`, `schedulerPublisher`
(`internal/server/subsystems.go:99`) returns `NoopPublisher`, so on ECS every
dispatch job is claimed and never delivered.

The design is settled (`docs/spec/deployed-dispatch.md` §3, owner
2026-09-12): the platform serves its own router config document, the router
merges it with Integral's through one comma-separated
`FLOWCATALYST_CONFIG_URL`, dispatch jobs go to lazily-created
`FC-{env}-{client}-DEFAULT|HIGH_PRIORITY.fifo` queues (priority from
`msg_subscriptions.queue`), platform-wide jobs to `…-platform-…`, pool keys
`{tenant}-{pool}`, and dev mode uses the same path instead of its fixed
single-queue branch.

**Do not start yet.** Java implements it first and the spec records what was
actually built (including two assumptions the owner has not confirmed:
`platform` as the tenant segment for client-less jobs, and `DEFAULT` for a
job with no subscription). Mirror it afterwards from the updated spec, the
way the portal-apps and secret-scheme work was mirrored.

## 4. Remove from the IaC (both sides ignore them)

`FLOWCATALYST_JWT_PUBLIC_KEY`, `FC_WEBAUTHN_RP_NAME`, `FC_STATIC_DIR`. These
are an IaC change in `../inhance/iac/compute/index.ts`, not a Go change;
listed here so one document covers the deployment.
