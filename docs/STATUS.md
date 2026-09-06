# Port status — living document

Updated whenever a unit lands. A fresh session (human or agent) should be
able to resume from this file + `CONVENTIONS.md` + `docs/backlog.md` +
`docs/process/agent-prompts.md` without re-deriving anything.

## HTTP/2 and HTTP/3 transport (2026-09-06)

`docs/spec/http-transport.md` landed: h2c (cleartext HTTP/2) is now always
on for the API listener (`FC_API_PORT`), and TLS+ALPN (h2, http/1.1) turns
on when TLS material (`FC_TLS_KEYSTORE_PATH`+`FC_TLS_KEYSTORE_PASSWORD` or
`FC_TLS_CERT_PATH`+`FC_TLS_KEY_PATH`) is configured on `FC_TLS_PORT`
(default 8443). HTTP/3 (QUIC, `FC_HTTP3_PORT`) turns on additionally with
`FC_HTTP3_ENABLED=true`, guarded by a native-library probe
(`Http3#quicheLoadFailure`) so a missing quiche binary never takes the
plain/TLS listeners down with it. All Java-only — Go's inbound server is
HTTP/1.1 only, so this is not a parity item.

**With the ALB in front, the production win is h2c to targets** (target
group protocol version `HTTP2`, a terraform/console change) — end-to-end
HTTP/3 to the browser is the ALB's feature, not this process's; the
server's own h3 listener is for topologies where the process terminates
TLS itself (fcdev, a bare host, a future NLB). **Nobody should expect h3
through the ALB from this change.**

Surprise found along the way: Jetty 12.1 auto-installs its own bare
`Alt-Svc: h3=":<port>"` (no `ma` parameter) on every HTTP/2-capable
connector — TLS *and* the plain h2c one — the moment a QUIC connector joins
the same `Server`, which beats an `HttpConfiguration.Customizer` added at
connector-build time (Jetty's own injection runs later, at the HTTP/3
connector's startup). Fixed with a `Handler.Wrapper` installed as the
outermost `Server` handler instead — it runs after Jetty's entire
`customize()` phase finishes, so it reliably has the last word: `put`s the
full value (with `ma=86400`) on secure requests, strips whatever Jetty put
there on plain ones. See `Http3#altSvcHandler`'s javadoc.

quiche (the QUIC/HTTP-3 engine) actually **does** load via the FFM binding
on this dev machine (macOS arm64) — `jetty-quic-quiche-foreign` pulled in
via Maven Central resolved and initialized without error. The end-to-end
h3 client fetch in `Http3Test` still could not be gotten working within the
brief's one-hour HTTP/3 budget (an `SSLHandshakeException` inside Jetty's
own HTTP/3 test client, isolated with `Assumptions.abort` so it reads as
"unverified", not "broken" — every server-side assertion, connector
construction included, passes). Native-image support for the quiche
FFM binding was not investigated in this unit (out of scope per the brief);
treat HTTP/3 as jar/jlink-only until someone checks `-Pnative`.

## Overnight run 2026-09-05 — Phases 0, 1 and 2 done; Phase 3 gated on rulings (handover)

Owner asleep; orchestrator ran `docs/port-plan.md` Phases 0, 1 and 2 to
completion (eleven Sonnet ports merged, every Phase 2 spec written first),
wrote the auth rulings batches that gate Phase 3, and stopped there — no
auth Java without Batch A. Every unit below was reviewed by the orchestrator (code
read, not the report), mutation-checked on its security-bearing assertion,
and merged only after the unit's tests plus the lockfile check were green
in its worktree; main's full suite is re-run after each merge.

**Landed on `main`, in order:**

| Commit | Unit | Notes |
|---|---|---|
| `931d5a7` | Backoff ruling `3b64775` (`lastSuccessAt` bounded to 400 d) + PoolTest flake fix | orchestrator |
| `c6d7e36` | fcdev downloads its Postgres archive on first run — jar 181 → 47 MB | Sonnet; orchestrator added HTTP timeouts. Found zonky's own POM drags four archives in transitively |
| `dd1874b` | Flyway V2–V7 = Go 046–052 (secret grace, dispatch-mode default, **login-attempts partitioned**, X-06 CHECK constraints); Go schema re-captured at HEAD | Sonnet; two Go HEAD defects found → `backlog.md` (seeder writes `'JSON'` against its own CHECK; the login-attempt partition note was later corrected — Go's purger maintains them) |
| `22bbffc` | **X-06 strict stored-enum reads across twelve modules** | Sonnet sweep + orchestrator merge fix-up (CHECK constraints landed underneath it). `AttemptOutcome` no longer reads a corrupt row as a login SUCCESS; `ScopeType` no longer defaults to ANCHOR. Shared `CorruptRowException` → 500 `CORRUPT_ROW` |
| `9a4e6fc` | **authadmin**: anchor domains, client auth configs, IdP role mappings (11 ops) | spec `auth-admin-config.md` by orchestrator; Sonnet port; coverage **203/245** |
| `(merge)` | **serviceaccount** (14 ops), RS256 mint under the platform key, CAS plaintext upgrade, OAuth marker | Sonnet port; orchestrator replaced an HS256-under-app-key minter and a fake OAuth pair in review; coverage **206/245** |
| `(merge)` | **SDK ingest** (5 routes: events, dispatch jobs, audit logs) | Sonnet port; found Go's cross-request event dedup never fires (index includes `created_at`); coverage **208/245** |
| `(merge)` | **principal 404 oracle (PR-3/PR-4), X-02 sync containment, X-08 per-app rollup groups, five residual X-06 readers** | Sonnet; orchestrator mutation-checked the 404 (seven assertions) |
| `(merge)` | **stream processor** (Phase 2 unit 1): fan-out, projections, partition manager, health | spec `stream.md` by orchestrator; Sonnet port; orchestrator mutation-checked the claim transaction |
| `(merge)` | **outbox processor** (Phase 2 unit 2) | spec `outbox.md` by orchestrator; Sonnet port; orchestrator replaced a racy exclusivity test with a held-transaction one that kills the no-lock mutant |
| `7391dbe` | **scheduled-job scheduler + purger** (Phase 2 unit 3): poller, HMAC-signed dispatcher with credential cache, login-attempt partition maintenance | spec `scheduled-job-scheduler.md` by orchestrator; Sonnet port (five mutants killed); orchestrator killed a sixth (signing with the token instead of the secret) |
| `5b88313` | **BFF + `/api/me`** (Phase 1 last unit): dashboard, filter options, developer, event types, roles, scheduled jobs, `/bff/*` aggregate mounts, `/api/me*` | spec `bff.md` by orchestrator; Sonnet port (eight mutants); orchestrator admin-gated the dashboard (spec draft was wrong, agent flagged it) and killed a ninth mutant |
| `(merge)` | **MCP server** (Phase 2 unit 4): 12 tools, 9 resources, streamable HTTP on its own listener, client-credentials token manager + interim static bearer | spec `mcp.md` by orchestrator; Sonnet port (the agent stalled twice waiting on its own suite and never wrote a report — the orchestrator read the code, added the bearer assertion, killed two mutants); coverage unchanged |
| `c55d6dd` | **AWS Secrets Manager DB mode + rotation** (Phase 2 unit 5, last) | spec `db-secret.md` by orchestrator; Sonnet port — the cleanest of the night: ten mutants incl. the disabled timer, a clear report; orchestrator killed an eleventh (credentials clobbered on a failed refresh) |
| `(merge)` | **CORS filter** (Phase 4): allowlist-driven headers, preflight answered ahead of the authenticator, 30 s cache + invalidation | contract `cors.md` §9 by orchestrator; Sonnet port (five mutants); orchestrator added the preflight headers its worktree's spec lacked (specs must be committed before worktrees are cut) and a scope test, two more mutants |
| `(merge)` | **JFR events** for the five Phase 2 loops (Phase 4) | spec `jfr-events.md` by orchestrator; Sonnet (seven mutants, a strong report — it found the spec in the main checkout when its worktree lacked it); orchestrator added the outbox `persisted=false` case and its mutant |
| `(merge)` | **fcdev `mcp`, `outbox` + `create-table`, `upgrade`** (Phase 4) | spec `fcdev-commands.md` §2–§4 by orchestrator; Sonnet (five mutants; found that worktree agents' `mvn install` into the shared `~/.m2` clobber each other — now in `CLAUDE.md`); orchestrator added two tests + mutants |
| `(merge)` | **fcdev `init`** (Phase 4, last stub) | spec `fcdev-commands.md` §1; Sonnet (four mutants; two judgement calls reported, both sound); orchestrator resolved the stub-file conflict, wrote the §4 docs, killed the skipped-admin mutant |
| `a9e583f` | **Phase 3 A1 — token issuance core + `DbClaimsResolver`** | orchestrator; six mutants |
| `181630a` | **Phase 3 A2 — session surface** (`/auth/check-domain` ×2, `/auth/login`, `/auth/logout`, `/auth/me`, `/auth/login-history`; backoff as a real lock, fail-closed store) | orchestrator; five mutants; Go lock-anchor deviation recorded |
| `6b26f29` | **Phase 3 A3 — OAuth-client aggregate + admin API** (12 routes; `acceptsSecret` both-compares; empty grant list ⇒ none, C-Q20) | Sonnet in a worktree, strong; six mutants (five agent, one orchestrator on the null-vs-empty junction split); coverage 229/245 |
| `4643de0` | **Phase 3 A4a — grant store, refresh rotation, rate-limit store + governor** | orchestrator; mutants; JSONB/precision test pitfalls documented in the tests |
| `0d98358` | **Phase 3 A4b — the OAuth / OIDC provider** (`/oauth/authorize`, `/oauth/token` ×3 grants + developer branch, `/oauth/introspect`, `/oauth/revoke`, `/oauth/userinfo`, discovery, JWKS, `/auth/refresh`; per-IP and per-client throttles; A-22 previous-secret signal) | orchestrator; 42 HTTP tests over embedded Postgres, six mutants killed (state ≤116, code↔client binding, previous-secret stamp, apiAccess ceiling narrowing, refresh client binding, introspection `client_id` = azp); wired in `Platform` |
| `fa3a8a9` | **Phase 3 A5 core — the second factor** (TOTP RFC 6238 + QR, e-mail PIN, recovery codes, trusted devices, the HS256 mfa token, the §6.2 login gate, domain policy with the I-Q11 internal term) | orchestrator; 50 tests; seven mutants incl. the two-halved TOTP replay guard; Sonnet unit for the 14 routes + change-password briefed (`docs/process/briefs/2026-09-05-a5-mfa-routes.md`) |
| `15a8bfb` | **Purger sweeps + readiness + alarm** — the ten-step purger (Go's order: payloads, OIDC states, portal flows, rate-limit events at MaxWindow+10 min, lapsed OAuth previous secrets, MFA PINs/devices, reset tokens, approvals marked `EXPIRED`, partitions); `/health` readiness check for the login-attempt partitions (C-Q23) with 503 `DOWN`; `fc_auth_backoff_store_errors_total` alarm counter | orchestrator; five mutants; expired auth rows keep a 24 h grace before removal (I-Q17 "expiry + grace") |
| `2e4fbfd` | **Phase 3 B1 — the OIDC bridge** (`/auth/oidc/login`, `/auth/oidc/callback`, `/auth/oidc/session/end`; discovery + JWKS via nimbus, PKCE, single-use state, nonce/domain/tenant binding, JIT via `CreateUser`/`CreatePortalUser`, best-effort IdP role sync through `SyncIdpRoles`, the per-IP bucket on `/auth/oidc/*` + `/portal/*`; rulings Q1 cache invalidation hook, Q3 fixed `OIDC_VERIFY`, Q5 envelope, Q22 `system` actor) | orchestrator; `OidcBridgeTest` drives a fake IdP served by a second Javalin (real discovery, JWKS, exchange, signed id_tokens); seven mutants (nonce, domain binding, expired state, issuer pattern, allow-list, post-logout URI, protocol-relative return_url). Portal sink (§5.6) is a seam until the portal unit; Platform wiring follows the A5-routes merge |
| `1b093a7` | **Phase 3 C1 — mail transport + notification catalogue** (`platform.mail`: RFC 5322 MIME with `Date`, `Message-ID` and RFC 2047 subjects per I-Q15, a minimal SMTP client — implicit TLS or STARTTLS, `AUTH PLAIN`, dot-stuffing — `FC_SMTP_*` over `SMTP_*`, logging transport when unset; `platform.notify.Notifications`: the §10 catalogue with the `FlowCatalyst` fallback per I-Q16, best-effort, HTML-escaped labels) | orchestrator; the SMTP test scripts an in-process server and pins the dialogue; four mutants (Date header, dot-stuffing, the fallback spelling, label escaping). Wiring (Platform, the MFA `MailSender` seam, `principal.Notifier`) lands with the A5-routes merge |
| `6770386` | **Phase 3 C2 — password reset core** (`platform.passwordreset`: hashed 15-min reset / 72-h invite tokens, one live token per subject, the §8.1 link mailer for both planes through the branding theme, `/auth/password-reset/{request,validate,confirm}` with the five-attempt TOTP gate, `ResetPassword` as the `system` actor, post-reset revocation + notices + enrolment demand, the portal confirm behind a `PortalPasswords` seam, the approval queue behind a seam idle per I-Q19; I-Q14: admin links never ask for the factor, `reset2fa` clears it) | orchestrator; five mutants (factor gate, expired burn, one-live-token, refresh revocation, reset_2fa). Wiring + the principal admin routes' emailer follow the merges |
| `25c5a1f` | **Phase 3 A5 routes — the 14 `/auth/2fa/*` routes + change-password** | Sonnet in a worktree, strong (28 tests, six mutants, honest flag on the SSO gate); orchestrator review added three fixes + three tests: the verify backoff 503 counts the alarm, LAST_FACTOR only for the one confirmed factor being removed (404 otherwise), SSO_MANAGED = the login route's domain rule ∪ federated; full suite 3356 green in the worktree |
| `3175698` | **Platform wiring of the deferred auth units** — SMTP/logging mail transport, the notification catalogue behind `principal.Notifier` + `TwoFactorNotifier`, `ResetLinks` as the principal admin routes' emailers, `/auth/password-reset/*`, the OIDC bridge + its per-IP bucket, the identity-provider change hook invalidating the bridge's client cache (Q1); MFA PINs now go through the real transport | orchestrator; one edit site after the A5 merge; ServerTest/LockfileCoverageTest/PrincipalApiTest/IdentityProviderApiTest green |
| `fa3bd57` | **Phase 3 B2 — the portal plane** (portal identities + `/api/portal-users`, `/portal/authorize`, `/portal/auth/{check-domain,login,password-reset}`) | Sonnet in a worktree, strong (34 tests, six mutants; a random 32-byte flow id kept over a TSID; caught its own event-shadowing bug); orchestrator review: shared grant store, the SSO reset guard's mutant survived until the test gave the SSO address an identity; coverage 234/245; full suite 3404 green in the worktree |
| `6c3a004` | **Phase 3 C3 — passkeys** (`platform.passkey`: Go-shaped `passkey_data`, legacy rows skipped, ceremonies in `oauth_oidc_payloads`, the relying party over yubico `webauthn-server-core` 2.9.0, the six lockfile routes: session-gated registration and list/revoke, public authentication with the decoy challenge, the shared backoff budget in the platform 429 envelope (I-Q24), the sign counter persisted and a non-increasing one refused (I-Q13), events under `platform:iam` (I-Q25)) | orchestrator; `PasskeyApiTest` drives real ceremonies with a software authenticator (ES256 + CBOR); five mutants + one equivalent (the library refuses the counter first); `GrantStore.deleteExpired` now sweeps every expired payload row |
| `77790e0` | **Portal SSO + the last portal seams** — `GET /portal/auth/oidc/login` (flow consumed at start, Q10) and the bridge's portal sink (JIT identity as `system`, `access_denied` for a suspended one, the `ptu_` code, never an `fc_session`); `/oauth/token` redeems `ptu_` codes (identity token, roleless id_token, no refresh) via a `PortalSubjects` seam; the reset confirm's `PortalPasswords` seam over the identity repository | orchestrator; `PortalSsoTest` drives the whole chain through a fake IdP; three mutants (suspended identity signed in, token branch ignoring status, flow left live) |
| `a674149` | **Branding fallback `FlowCatalyst`** (I-Q16 applied to `Branding.DEFAULT_PLATFORM_NAME` too — owner to confirm the public endpoint's spelling, `docs/backlog.md`) | orchestrator |
| `1fd26e7` | **Admin 2FA reset audit row** (`MfaService.resetAllByAdmin`; the last `TODO(port)` in `PrincipalApi`) | orchestrator; pinned in `MfaServiceTest` |
| `c4b4a14` | **Client selection `/auth/client/{accessible,switch,current}`** — the last Go route group not in Java (spec `auth-core.md` §6.5 written from Go): reachable tenants per scope, a switch that mints the full-authority API token after the access and active checks, the current client | orchestrator; two HTTP tests; one mutant (the non-anchor access check) |
| `37a6288` | **Request schema validation in huma's shape** (`shared/openapi/{SchemaValidation,SchemaValidator,ValidationMessages,GoNumbers}`): a before-filter after the authenticator validates every lockfile operation's body and query/path parameters against the lockfile schemas and answers `VALIDATION` with per-field `details.errors` the SPA renders; the startup keyword gate refuses an unimplemented schema keyword; 17 `*ApiTest`s moved from absent-field to blank-field for their domain codes. Three spec corrections proven against huma's source (one alphabetical pass, the query-parameter message, `additionalProperties` as the lockfile has it). **The parity corpus has no VALIDATION diff left and the seven message texts match Go** | Sonnet (strong: read huma rather than trusting my spec); orchestrator mutant on parameter validation killed |
| `916549d` | **CI workflow** (`.github/workflows/ci.yml`, the `port-plan.md` CI item): reactor tests + `tools/jooq-verify.sh` on Temurin 25; the Dockerfile's jlink image built and started router-only until its HEALTHCHECK passes; `-Pnative` on `ubuntu-latest`, `ubuntu-24.04-arm` and `macos-latest` with a `/health` probe on each binary; the parity corpus against `flowcatalyst/flowcatalyst@main` (report artifact; fails on any DIFF/ERROR); the Playwright suite on Java with the Go column behind `E2E_GO_SIDE`. The parity module keeps zonky's linux-amd64 binary (it had excluded it — no amd64 runner could have run the corpus). Unproven on GitHub: no remote yet | orchestrator; router-only start of the jar and the Docker image both probed locally |
| `58187c8` | **HTTP/2 and HTTP/3 on the API listener** (owner requirement 2026-09-06; `docs/spec/http-transport.md`): h2c always on the plain `FC_API_PORT` (the ALB's h2-to-target path), TLS+ALPN → h2/http1.1 on `FC_TLS_PORT` from a PKCS#12 or a PEM pair (`FC_TLS_*`), QUIC → h3 on `FC_HTTP3_PORT` with `FC_HTTP3_ENABLED=true` (quiche FFM binding, probed before install; `Alt-Svc` only with the connector). **Proven with an independent client**: Homebrew curl answers `3 200` over `--http3-only`, `2 200` over ALPN and by h2c prior knowledge. Known limitation (backlog): after an h3 exchange the graceful stop waits the whole grace period. Launch commands carry `--enable-native-access=ALL-UNNAMED` | Sonnet (strong on h2/TLS and the `Alt-Svc` handler finding — Jetty auto-injects its own customizer; stalled twice on quiet long builds); orchestrator added the QUIC stream preset, the curl proof, tied `Alt-Svc` to the connector, pinned the `jetty-quic-quiche-server` dependency, and ran the worktree suite (3533 + transport) green after merging main |
| `6128603` | **Native fcdev** (`fcdev/pom.xml` `-Pnative`, owner ruling #20 — before cutover): picocli's reflection generated by `picocli-codegen` at compile time, `NativeReflectConfig` reused over fcdev + server + usecase classes, zonky/mysql reachability captured into `fcdev/native-config/`; a 116 MB arm64 binary (41 MB gzipped) that inits and starts a fresh embedded database and answers `/health` in ~4 s; CI `native` job builds and probes it on all three platforms. Known gap: the published docs pages are not served from the image (backlog) | Sonnet (strong: found and fixed the incremental-compile trap that silently skipped the annotation processor, and scoped it off test sources under `-Werror`); orchestrator re-ran start/health/SPA/stop with the binary; fcdev tests green in the worktree after merging main |
| `c048ade` | **Re-sync with Go `b3c75cd` + the first Java work of the rulings** — the owner's Go agent had landed five fixes overnight (seeder literal, dispatch permission, `oidcMultiTenant` optional, `clientScoped` on `/api` create/update, `hasLoginClient`, event dedup, 400 `unauthorized_client`, `/api/me` name, `NO_MFA`, trusted-device cookie); the lockfile is re-vendored at `3c22690` (246 operations), the embedded SPA refreshed to `642f5da`, eleven stale allow-list entries deleted. Java: rulings #7 (`clientScoped` on create/update/response, `/api` and BFF), #11 (400 `unauthorized_client` + the attempt row, Go's texts), #16 (`app:` namespace, `RESERVED_CODE`), #19 (strict wire enums: `INVALID_TYPE`, `INVALID_VALUE_TYPE`, `INVALID_STATUS`), #6 (BFF scheduled-job list confines non-anchors to their clients, `total`/`totalPages` agree). Then, as the owner's Go agent kept landing rulings (`ece54fe` #10a/#10b, `b422466` #16/#18), Java followed the same night: events batch ingest is partial success with honest per-item `BAD_REQUEST` slots, audit items without `principalId` are refused per slot (never defaulted), the harness refuses an empty capture instead of masking the run with it. **Corpus against Go `b422466`: 1,149 steps, 0 DIFF, 0 ERROR, 246/246 + 102/102** (revoke-previous-secret joined the contract and got its steps). **Frontend e2e ran on both sides for the first time**: Go 44/49 (the 2FA card is blank on Go — `devices: null`, fix list G8; `clientScoped` dropped by Go's BFF create, G1), Java 49/49 with no pins left | orchestrator; the reactor green; owner items G1–G8 in `docs/go-mirror/2026-09-06-go-fix-list.md` |
| `9132495` | **e2e runner: Go built against the fresh SPA in a scratch copy; byte-exact SPA gate; `E2E_TEST_TIMEOUT_MS` / `E2E_RETRIES`** — the Go tree's `frontend/dist` was two weeks stale and the hash-blanking gate passed it | orchestrator |
| `6167502` | **Frontend e2e re-pinned after the schema filter** (the 2FA fixture and a new API-side identity-provider helper send `oidcMultiTenant: false`; the IdP UI flow is a `test.fail` on the SPA/contract mismatch, backlog) — Java 49/49 (47 + 2 expected failures) | orchestrator |
| `a2aa48f` | **Frontend e2e — 2fa, passkeys, tenancy, authorization, identity** (the confinement flow verified non-vacuous; a real passkey ceremony via CDP; TOTP with computed codes) — **the whole e2e suite is 49 flows on the Java side, all green**; the Go column waits on Go's seeder fix | Sonnet (strong; six SPA behaviours documented, none skipped); orchestrator ran all groups together |
| `0453596` | **Frontend e2e — catalogue, authentication admin, platform** (29 screens, 36 flows incl. the auth group, all on reloaded state; Java 36/36; one expected failure pinning the `clientScoped` drop on both sides) | Sonnet (strong: six SPA traps documented, two product defects found); orchestrator reran the suite |
| `095291e` | **SDK on Jackson 3 only** (owner ruling): the generator emits models only, a nine-line `sdk.generated.ApiClient` serves the two helpers the models call, the Jackson 2 databind/jsr310 dependencies are gone; the SDK's tree is `tools.jackson` + the `jackson-annotations` jar Jackson 3 itself uses. Also ruled: yubico stays for WebAuthn (the server's one runtime-scope Jackson 2 jar), no hand-rolled verifier | orchestrator; `GeneratedModelsOnJackson3Test` round-trips models incl. an `OffsetDateTime`; sdk 47 tests green, fcdev compiles |
| `32bda56` | **Phase 5 — frontend e2e runner + auth group** (`e2e/`, Playwright; spec `frontend-e2e.md`): both fcdevs started on fresh embedded databases, mail-from-log, the SPA gate over Vite's per-build hashes, seven auth flows on reloaded state; Java 7/7; the Go column blocked by Go's own fresh-database seeder defect (backlog) | Sonnet (strong: six runner defects found and fixed by running it); orchestrator ran the Java side and the 17 unit tests |
| `82280ac` | **Phase 5 — BFF findings from S3 closed**: `/bff/roles/filters/applications` lists every active application (Go's rule; bff.md corrected), `/bff/roles/permissions` assembles Go's catalogue (built-ins, then role-granted codes, then the persistent catalogue); platform-scoped scheduled jobs for a client caller are an owner question. **Full corpus now: 0 ERROR, and every remaining diff belongs to the request-schema-validation unit in flight** (30 VALIDATION-envelope steps + 7 message texts) | orchestrator |
| `ddab766` | **Phase 5 — S3 corpus (the 102 routes outside the lockfile) merged and triaged**: surface 102/102. Java aligned: OAuth errors outside the token endpoint carry no cache headers (Go's rule), `/api/me/applications` `clientId` is Go's `""`, the BFF's `specVersions[].schema` renders in jsonb's own text form (`PgJsonb`, keys shorter-first) as Go echoes it. Rulings allow-listed by id (I-Q21, defect 10, PR-3 class on `/api/me/clients/{id}`, I-Q16 on `platformName`); Go defects backlogged (BFF scheduled-job list leaks across clients, `/api/me` name = email, NO_EMAIL_2FA ordering); owner question: the OpenAPI documents (serve the lockfile verbatim is the recommendation). Open: Go's role/permission facets list rows Java's do not — a diagnostic step is in the bff scenario | orchestrator; Sonnet S3 strong |
| `b871c60` | **Service-account OAuth client** — create mints a real `CONFIDENTIAL` client bound to the SERVICE principal in the same transaction; the `unavailable:auth-not-ported` stub is gone; `principalId` presence follows Go | Sonnet (strong; a live `client_credentials` exchange in the test); orchestrator mutant on the grant types killed; worktree suite 3464 green |
| `ece8cee` | **Phase 5 — S1 corpus merged and triaged** (three Sonnet agents, 28 lockfile groups, 245/245 operations hit by scenarios alone). Java defects fixed from the reports: the auth-config OIDC secret stored in plaintext (now sealed like the identity provider's), the portal admin API's invite link still coming from the logging stub in production (a real `ptu_` set-password token now), `/roles/by-source` leniency (Go's 400), the platform-scoped scheduled-job refusal, `PortalUserListItem.name`, `twoFactorMethods` omitted when empty, the service-account token's applications wildcard, passkey parse/attestation messages, assertion options carrying transports, the WebAuthn timeouts, Platform reading five subsystem knobs from the process environment instead of its Env (fcdev and the harness were silently ignored), and the OAuth-client audit `operation` names (Go's `CreateOAuthClientCommand` etc.). Rulings allow-listed by id (PR-3, cron shape, D1, D2, Q7, I-Q16, nil-slice lists, WebAuthn library defaults); Go defects backlogged (batch ingest, audit-log principal default, hasLoginClient). The one systemic gap — huma's schema validation before every handler, with the per-field envelope the SPA displays — is `docs/spec/request-schema-validation.md`, in build by Sonnet; the service-account OAuth-client stub is the other Sonnet unit in flight | orchestrator; harness gained run-wide and automatic id masking, prefix/scenario wildcards, the passkey ceremony |
| `468157a` | **Phase 5 — S2 auth scenarios + the first triage** (`parity/scenarios/auth/{session,oauth-code-flow,mfa}.json`): 30 diffs on the first run, closed as — Java defects fixed: the login surface's `code` envelope (Go's `login` package vs `httperror`; spec §5 row corrected), `typ: JWT` on every minted token, no `scope` claim on full-authority mints (Go `GenerateAccessToken`), the anchor `clients` wildcard kept through a bearer context, logout as `Max-Age=0`, `/oauth/userinfo` on the public path (Go `wire_public.go`), `/auth/client/current` always carrying `client`, bodiless responses without `Content-Type` (`/oauth/revoke`); harness rules added: 3xx body/Content-Type not compared, numeric `exp/iat` as times, stale check only on a full run; rulings allow-listed with their ids (C-Q23 `checks`, C-Q24 `/auth/me` status, trusted-device cookie cleared on password change); owner questions (backlog): introspection `client_id` = azp vs Go's tenant, `/api/me` `name` for a service principal, `client_credentials` 500 on an unbound client (both sides) | orchestrator; S2 session + OAuth = 60 steps OK/ACCEPTED, exit 0 |
| `0abc08d` | **Phase 5 — the parity harness is real** (`parity/` module; first Go-vs-Java run: Go's seeder defect worked around without touching Go, the JDK cookie jar replaced, two normalisation gaps closed, one Java defect fixed — `text/plain` on every 204 — and C-Q23's `checks` allow-listed with its ruling; S0 = 0 DIFF/ERROR) | Sonnet (strong: 44 mutation-checked tests, found and explained every diff itself); orchestrator read every class, ran the S0 twice, wrote the fixes; worktree suite 3460 green |
| `ec55164` | **Application provisioning — the last two lockfile operations** (`ProvisionServiceAccount` TxOperation over four aggregates; `provision-login-client` over `CreateOAuthClient`; `hasLoginClient` computed) — **lockfile coverage 245/245, threshold 1.0** | Sonnet (strong; four mutants; widened two helpers to public and said so); orchestrator mutant on the client→principal link killed; worktree suite 3460 green |
| `fe83fc5` | **Phase 3 C4 — reset approvals** (`platform.resetapproval`: the guarded-UPDATE decision, the real `ApprovalQueue` behind `/auth/password-reset` notifying `platform:client-admin` holders, `GET /api/reset-approvals` + approve/deny with the reviewer's note, 400 `ALREADY_DECIDED`) | Sonnet (strong: five mutants, the decision-outside-the-Plan trade-off argued and recorded); orchestrator mutant on the expiry guard killed; worktree suite 3453 green; coverage 243/245 — the two `provision-*` routes are the last gap (brief `2026-09-05-application-provisioning.md`, Sonnet) |
| `f66f7b1` | **`FC_JWT_ACCESS_TOKEN_TTL_SECS` read; `MigrationsAreAdditiveTest`** — the one server knob the env-parity check found unread now reaches `TokenIssuer.Config` (exp + `expires_in`); the guard cutover.md §1 asks for rejects DROP/RENAME/retype in any non-mirrored migration after V1 (predicate unit-tested; scan vacuous today) | orchestrator; EnvTest/OAuthProviderTest/ServerTest green; Platform wiring line unpinned until parity S2 |
| `4fdc802` | **Phase 5 — cutover + rollback rehearsal design** (`docs/spec/cutover.md`: why the shared database makes rollback "start Go again", seven gates, the nine timed steps incl. cross-side token continuity and a rollback drill every rehearsal, env parity re-derived — 152/160 Go variables read by Java, 7 SDK-only, 1 missing: the access-token TTL, backlogged) | orchestrator |
| `a6f840f` | **Phase 5 — frontend e2e design + embedded SPA refresh** (`docs/spec/frontend-e2e.md`: Playwright in `e2e/`, the same flows against Go and Java started through each side's `fcdev`, index.html equality gate, mail links read from the servers' logs, confinement flow mandatory; `tools/sync-frontend.sh` rebuilds the SPA out of tree from the Go repo and syncs it — the embedded copy dated from the bootstrap commit and is now at frontend source `89b195e`) | orchestrator; `FrontendTest` green on the refreshed copy |
| `7e63fbd` | **Phase 5 — platform parity harness design** (`docs/spec/parity-harness.md`): Go-seeded template database cloned for each side, Go as a subprocess, Java in-process through the adoption path, JSON scenarios with per-side captures, seven fixed normalisation rules (tokens decoded and compared as claim sets), a named allow-list that fails when stale, lockfile + outside-lockfile coverage as a gate; corpus in four phases S0–S3; brief for the harness + S0 smoke | orchestrator; Sonnet builds the harness from the brief |
| `69dbf9e`, `3b924ea` | Specs written: `auth-admin-config.md`, `sdk-ingest.md` | orchestrator. `sdk-ingest.md` §5 D1: Go's dispatch-job ingest checks a permission no role grants |

**Phase 4 (2026-09-05, on the owner's go-ahead): CORS filter, JFR events
for the Phase 2 loops, and all four fcdev stubs landed** — rows above.
Left in Phase 4: the pagination envelope (owner decision, wire change) and
the optional native fcdev build.

**Phase 3 (auth) started 2026-09-05 after every ruling was given.** Landed:
A1 token issuance + store-backed claims resolver (`a9e583f`), A2 the
session surface with the enforced backoff lock (`181630a`), A3 the
OAuth-client aggregate (`6b26f29`, Sonnet), A4a the grant store, refresh
rotation and rate limiting (`4643de0`), A4b the whole OAuth / OIDC provider
(row below). Found while porting A2: Go anchors the enforced lock to the
oldest failure of the ceiling set, not the last failure the spec names —
`docs/backlog.md`. A5's core is in (`fa3a8a9`); its 14 routes and
change-password are a Sonnet unit in flight. The purger's ten sweeps, the
C-Q23 readiness check and the backoff alarm counter followed (row below).
**Next:** merge the A5 routes, then Batch B (OIDC bridge, portal) and
Batch C (WebAuthn, password reset, approvals, mail; the I-Q16
`FlowCatalyst` default spelling lands with branding).
Until the portal unit lands, `/oauth/token` refuses a `ptu_` code with
`invalid_grant "Portal subjects are not supported"`.

Final whole-reactor `mvn clean test` on main at the end of the overnight run:
**usecase 30 · sdk 44 · server 2983 · fcdev 47, zero failures** (server was
2772 when the night started).

**Where to resume (2026-09-06, ~04:30):** every 2026-09-06 ruling with
Java work is in (#6, #7, #10a, #10b, #11, #13 was already so, #15, #16,
#19; #14 was already so). Two Sonnet units were cut from committed briefs
and may be unmerged when you read this — check `git worktree list` and the
branches `worktree-agent-*`: **native fcdev** (`docs/process/briefs/2026-09-06-native-fcdev.md`)
and **HTTP/2 + HTTP/3 listeners** (`docs/spec/http-transport.md`,
`docs/process/briefs/2026-09-06-http-transport.md`). Review each as the
merge discipline says (read the code, own mutant, worktree suite green
after merging main into it, then squash). The Go tree is edited
concurrently by the owner's own agent: before any corpus run check
`git -C ../flowcatalyst-go log`, re-vendor `api/openapi.lock.json` when it
changed, and re-sync the SPA (`tools/sync-frontend.sh`) when
`frontend/src` moved. The Go hand-off is
`docs/go-mirror/2026-09-06-go-fix-list.md` §G. Earlier state of the same
day follows.

**Where to resume (2026-09-06, evening):** Phase 3 complete; lockfile
245/245 at threshold 1.0. **Phase 5's verification is in place and clean.**
The parity harness (`parity/`, spec `parity-harness.md`) runs 1,149 steps
across every lockfile operation and every route outside it against a
Go-created database and reports **0 DIFF, 0 ERROR** (against Go `b422466`,
night of 2026-09-06; the Go tree moves, so a run names its Go commit); every
deliberate difference sits in `parity/expected-diffs.json` with its ruling
id, every Go defect in `docs/backlog.md`. The frontend e2e suite (`e2e/`,
Playwright, spec `frontend-e2e.md`) is 49 flows across nine groups, all
green on Java — 47 passes and two `test.fail` pins on SPA defects that
predate the port (`clientScoped` dropped on event-type create; the
identity-provider drawer omitting the lockfile-required `oidcMultiTenant`,
which the schema filter now refuses exactly as Go does); its Go column waits
on Go's one-literal seeder fix (backlog). Run them with
`PARITY_GO_SRC=/Users/andrewgraaff/Developer/flowcatalyst-go mvn -q -pl
parity -am test -Dtest=ParityRunTest -Dsurefire.failIfNoSpecifiedTests=false`
(~12 min) and `cd e2e && pnpm e2e:java`. `.github/workflows/ci.yml` runs
all of it (reactor + jOOQ drift, the jlink image, native-image on three
platforms, the parity corpus against the public Go repo, the Java e2e) —
written against GitHub's runners but unproven, since this repo has no
remote yet; the first push is the owner's. **What is left of Phase 5 is
`docs/spec/cutover.md`**: the staging rehearsal, which needs an environment
only the owner can provide.
Owner items: the twenty questions of 2026-09-06 are ruled
(`docs/rulings-2026-09-06.md`); what is still the owner's is the staging
rehearsal, the first CI run on a GitHub remote, the Go hand-off
(`docs/go-mirror/2026-09-06-go-fix-list.md` §G), and the two shared
defects in `docs/backlog.md` tail (an over-long audit `entityId` is a 500
on both sides; TypeBox and pagination wait for after cutover).

**Sonnet, honestly, across eleven ports:** reliable when the brief names
the template class, the exact routes and the mutants to run; two agents
produced the best work of the night on exactly that shape (outbox
concurrency aside, db-secret). Unreliable on unbriefed judgment — it
invented an HS256 minter under the encryption key and a fake OAuth pair
(serviceaccount), wrote a barrier-based "exclusivity" test that could not
fail (outbox), followed a wrong line in my own spec into an admin gate
that was open to every user (BFF, flagged by the agent, my error), and
one agent stalled twice waiting on its own test run and never wrote a
report (MCP). Nothing it wrote reached main unread.

**Phase 3 gate cleared (owner, 2026-09-05):** every question in
`docs/auth-rulings.md` was asked one by one and ruled; the rulings are
recorded there ("Rulings — Batch A/B/C"), in the Rust ledger, and as a Go
mirror list in `docs/backlog.md` with a verified patch for the mechanical
part in `docs/go-mirror/`. Auth (Phase 3) can start with the first cut:
JWT/RS256 issuance, PKCE, sessions, `/auth/login`, MFA.

**For the owner, collected in `docs/backlog.md`:** three Go HEAD defects
(seed `'JSON'`, unextended partitions, dispatch-ingest permission); the
wire-side leniency the X-06 sweep preserved under `parseWire` (role
`/by-source`, identity-provider create, platform-config value type,
connection update status) — Go's X-06 rejects unknown wire values too, so
this is a yes/no; and the questions in the two new specs' §8/§5.

## fc-server packaging and the default-broker gate (2026-09-04)

The owner restated the two deliverables, the same split as the Go repo:
**`fcdev`** is the developer monolith (fc-server + embedded Postgres + the
built-in Postgres broker + `start|stop|fresh|db upgrade`); **`fc-server`** is
the production server with every subsystem behind `FC_*_ENABLED` and **no
bundled broker** unless `FC_DEFAULT_BROKER=postgres`. Neither is a native
binary: both are executable jars, and JBang is optional for `fcdev` (the
owner does not want to register with JBang yet).

- `server/pom.xml` now shades an attached `flowcatalyst-server-<v>-exec.jar`
  (main class `io.flowcatalyst.server.Main`, `Implementation-Version` stamped
  so `/health` reports the build). The thin jar stays the main artifact, so
  `fcdev`'s own shade is unchanged. `Dockerfile` + `.dockerignore` mirror the
  Go image: Temurin 25 JRE on Alpine, uid 10001, ports 8080/9090, the same
  `wget /health` check. README has a "Run" section.
  **2026-09-05: the image ships a jlink runtime, not a stock JRE** — jdeps
  picks the 13 modules the jar needs, jlink adds the reflection-only ones
  (EC crypto, Unsafe users, JNDI, JMX, zipfs), stripped and compressed, on
  bare Alpine. Image **121 MB** (the `eclipse-temurin:25-jre-alpine` base
  alone is 225 MB); verified router-only in the container: health, router
  health, healthcheck, uid 10001. Still HotSpot with the full JIT.
- **Defect fixed (Java-only):** `Router.configSource` synthesised the Postgres
  broker queue whenever `FLOWCATALYST_CONFIG_URL` was blank, ignoring
  `FC_DEFAULT_BROKER`. Go (`server/run.go:346`) and spec §8.4 only do so when
  the broker is `postgres`; otherwise "no pools will start". A production
  router with neither would have built a queue from the database URL. Now
  gated; the default path also runs `PostgresQueue.initSchema` when a data
  source exists (Go does this in the router bootstrap, Java only did it in the
  scheduler publisher), and a blank database URL falls back to Go's
  `postgresql://postgres@localhost:5432/flowcatalyst`.
  `RouterConfigSourceTest` pins all five branches; removing the gate fails
  two of them (mutation-checked).
- Smoke-verified from the jar: `FC_PLATFORM_ENABLED=false FC_ROUTER_ENABLED=true`
  starts with no database, `/ready` shows only the router, `/router/health`
  is HEALTHY, log says "no pools will start".
- Still open for a router-only default-broker instance: `QueueFactory` needs
  a `DataSource`, but `Main` only opens one for database-backed subsystems,
  so `FC_DEFAULT_BROKER=postgres` on a router-only fc-server logs "needs
  postgres but no database is configured" and starts nothing. Go opens its
  own pgxpool from the URL. Owner question, not fixed here.

## GraalVM native-image trial (2026-09-04/05) — it works, 89 MB

Owner asked "just to see if it would work". It does: `server/target/fc-server`
is an **89 MB** arm64 Mach-O built in ~56 s by `mvn -DskipTests -pl server -am
-Pnative package` under `mise install java@oracle-graalvm-25.0.4.1` (the
project default JDK stays Temurin; the profile is opt-in). Verified with the
binary, not the jar: router-only with no database; platform + router +
default broker against a migrated database; and against an **empty**
database, which Flyway migrated and the seeder populated. Every surface
answered — `/health` with the stamped version, `/ready`, `/router/health`,
the `/api/*` list routes, an event-type create (201), the embedded SPA,
`/openapi.json`, `/docs`, `/metrics`. RSS: 46 MB router-only, ~105 MB full
platform; the same jar is 181 MB router-only.

**The first working image was 183 MB.** The build report (`--emit
build-report`) and analysis call tree found three causes, all self-inflicted:

1. `-H:IncludeResources=(…|io)/.*` swept **every `.class` file under `io/`**
   (netty, prometheus, nats, javalin, ours) into the image heap as bytes —
   24 MB of bytecode the image already contained as code. The pattern now
   names exactly our runtime resources (9 MB, mostly the SPA).
2. The blanket reflection config made **15,000 of our methods entry points**
   (every jOOQ generated method included), defeating dead-code elimination
   and, via `org.jooq.tools.reflect.Compile`, pulling `jdk.compiler` in.
   `io.flowcatalyst.tools.NativeReflectConfig` (test scope, uses the JDK
   class-file API) now registers only records, enums, Jackson-annotated
   classes and jOOQ record types: 906 entries instead of 1,399, and far
   narrower ones.
3. `netty-nio-client` came with the AWS SQS/ELBv2 artifacts as a runtime
   dependency though only the sync clients (apache5) are used. Excluded in
   `server/pom.xml`; the jar lost ~3 MB too. `-Os` added.

Residual: `jdk.compiler` is still reachable (3.5 MB) because jOOQ's SQL
parser (`DefaultParseContext.parseDataTypeEnum` → `Reflect.compile` →
`ToolProvider.getSystemJavaCompiler`) is reachable from jOOQ-internal paths
(`Convert$ConvertAll.from`, `TableImpl.accept`, `MetaImpl`), not from our
code. Cutting it needs a GraalVM substitution and an SDK dependency in the
production module; not worth 3.5 MB.

Four earlier build iterations each fixed one thing: Jackson could not see
record components (reflection config); jOOQ `SQLDataType.<clinit>` NPE on
unregistered array classes (agent-captured metadata in
`server/native-config/`, README there says how to re-capture); `/health`
said `dev` (`-Dfc.version` + `--initialize-at-build-time` for `Version`);
and **Flyway found no migrations** in an image ("unsupported protocol:
resource") — fixed in production code, gated to images: `IndexedMigrations`
is a Flyway `ResourceProvider` over the committed `db/migration.index`,
installed by `Migrator` only when `org.graalvm.nativeimage.imagecode` is
set. `IndexedMigrationsTest` pins index == directory listing **and** that an
indexed Flyway migrates a fresh database; an emptied index fails all three.
**Adding a migration now needs an index line** — the test tells you.

Not done, deliberately: no native tests run in the image (the JVM suite is
the authority), no Linux/Docker native stage (the Dockerfile ships the jar),
no PGO, and the agent metadata covers only the routes exercised above — an
unexercised reflective path (MCP, SQS, NATS, outbox, the auth surface once
ported) surfaces as a runtime "not registered" error, not at build time.

**Suite flake to fix:** `PoolTest.rateLimitWarnsOnceForARun` failed in two
of three full `mvn clean test` runs on 2026-09-04/05 (the DIAG line shows the
RATE_LIMIT warning *was* raised but the counter read 0) and passed alone
every time. Order- or load-dependent, not caused by today's changes; commit
`9e172f3` added the diagnostics for exactly this.

**fcdev size (owner, 2026-09-05):** the 181 MB fcdev jar is 129.5 MB of six
per-platform Postgres archives, of which any machine uses one. The Go fcdev
(fergusstrange/embedded-postgres) downloads the matching zonky archive from
Maven Central on first run and caches it. Zonky's `PgBinaryResolver` hook
makes the same change ~100 lines here and would put fcdev near 50 MB.
Not done; owner's call.

## Router completion drive (2026-09-02)

The owner asked for the full router port to be completed against the
implementation-neutral contract `../flowcatalyst-rust/docs/router-specification.md`
and its ruling ledger `../flowcatalyst-rust/docs/owner-questions.md` (the
authority order is in the spec's §0; `docs/spec/router.md` is pre-ruling).
Go absorbed every ruling in `2e2e466..7ae5acd`, so it is once again an
accurate reference for shape, never for correctness.

The gap audit and unit plan are `docs/spec/router-completion.md`; the platform
half was spec-extracted first into `docs/spec/dispatch-seam.md` (1105 lines,
11 owner questions in §14). Orchestration: Fable 5.1 planned, specced,
reviewed and merged; Sonnet 5 agents at medium effort wrote the code in
isolated git worktrees (builds must not share `target/`), one squash commit
per unit on `main`, full suite re-run on `main` after each merge.

| Unit | Commit | What landed |
|---|---|---|
| 5 observability | `4d2afce` | X-04 notifier floor env-tunable, INFO 1h TTL, `cleanup()` scheduled (A-08), R-52 group-flush list + clear, R-53 series, R-56 instance id |
| 1 delivery contract | `f2402b0` | R-57 5xx boundary (REJECTED is an explicit component, terminal on first attempt), R-12 origin+path breaker key, corpus `metric` column asserted, CIRCUIT_BREAKER/RATE_LIMIT warnings once per transition |
| 4a config lifecycle | `8a41635` | A-10 five-minute re-poll, R-30 last-known-good per source, R-33 gated real reload, R-36 consumer liveness → readiness, CONNECTION + QUEUE_HEALTH warnings |
| 2 A-01 gate | `dffe9b6` | BLOCK_ON_ERROR siblings released unless `FC_ROUTER_PLATFORM_URL` is set; settled reporter (ACK first, fire-and-forget, 1000/chunk, 5s) |
| 6a platform half | `2ea284f` | Cancel/Complete verbs (+ lockfile), one GroupHolding SQL fragment, `/api/dispatch/settled`, reaper, X-06 strict status parse, X-01 subscription default |
| 3 routing | `1dffd24` | `FC_ROUTER_STRICT_ROUTING` gate (off), R-59 synthesised-pool eviction, layer-2 dedup wired, drainer resurrection, per-consumer backpressure |
| 6c processing endpoint | `95929a3` | `/api/dispatch/process` with the §5 outcome table, delivery-time hold-back spending no budget, 5/15/30/60/120s ladder |
| 6b scheduler | `23d4ce1` | claim → mark QUEUED → commit → publish; poolCode composed at publish time; per-job HMAC bearer; stale recovery; scheduler-suffixed election; Postgres publisher |
| 4b lifecycle | `8b632da` | removed pools drain, replaced consumers linger until unreferenced, stall supervisor wired without aborting deliveries, `/monitoring/blocked-groups` (R-04) |

**Three step-3 audits** (fresh Sonnet readers, read-only) found five real
defects the coders' own mutation checks had not: `RouterServer` held the
leadership monitor across a config fetch of up to minutes (a leadership loss
mid-fetch kept the old leader polling); `Pool.submit` racing `close()`
swallowed the rejected task so an IMMEDIATE message was neither acked nor
nacked; the reaper was started and never stopped, sweeping the shared test
database; the dispatch-job GET routes still answered 403 for an out-of-scope
id while PR-3 requires a byte-identical 404; the subscriber response was read
unbounded before the 64 KiB cap. All are fixed or in the pending branches.
The audits also asked for a cross-implementation HMAC vector and pins for the
endpoint's three 500 paths.

The drive closed with the X-01 enum merge (one
`platform.shared.dispatch.DispatchMode`, `Subscription.DEFAULT_MODE` no
longer `IMMEDIATE`), the audit-fix commits, and two full-run flakes fixed at
the root (the throttle metric is one act; the test HTTP readiness probe has
a per-attempt timeout). Final uncontended run: **server 2356 tests green**,
lockfile 192/245 covered, zero drift.

**Open after this drive:** SQS/NATS dispatch publishers, signed subscriber
deliveries (needs `serviceaccount`), the owner questions in
`dispatch-seam.md` §14, the ledger's deferred R-items, and the two backlog
notes on the load-sensitive rate-limit test and leaked test threads.

## Where we are (2026-08-24, evening)

Reactor green on a clean uncontended build, 2026-08-27: **2270 tests** —
usecase 30 · sdk 44 · **server 2156** · fcdev 40, 0 failures. Coverage
**190/243 lockfile operations (78%)**, zero drift. Commits on `main`; one commit
per landed/audited unit.

**Orchestration model** — see `Claude.md` and `docs/process/agent-prompts.md`
§0: Opus 5 orchestrates (specs, scope, verification, debugging, commits),
`sonnet` at medium effort writes the code.

**DIRECTION CHANGE (owner, 2026-08-24): the message router comes next.**
Remaining platform work — the `principal` audit, `sdksync`, the remaining
CRUD aggregates and auth — is **deferred**, not cancelled. It is all still
listed under "Platform work, deferred" below and none of it is blocked.

### Router progress — data plane COMPLETE (2026-08-25)

Spec gate cleared (`docs/spec/router.md` §0, current against Go `eff2a29`).
**565 router tests**, all in the default `mvn test`, no profiles or tags.

| Package | What it holds |
|---|---|
| `router.wire` | `Message` (both Go `omitempty` semantics), `DispatchMode`, sealed `MediationType`, sealed `MediationOutcome`, response parse order, HMAC golden vector |
| `router.policy` | `RetryPolicy` (the **Q3 collapse**), `GroupFlushRegistry`, `CircuitBreaker` + registry, `RateLimiter` |
| `router.pool` | `OrderedGroups` (the **Q1 ruling**), `Pool`, `HttpMediator`, `QueuedMessage` |
| `router.queue` | `Consumer` contract + Postgres, SQS, NATS backends |
| `router.inflight` | `InFlightTracker` — new / redelivery / external-requeue |
| `router.manager` | routing, `ConsumerLoop`, reconfigure, `ConsumerSupervisor`, `RouterShutdown`, `RouterServer` |
| `router.config` | `RouterConfig`, `PoolSpec` (wire) vs `Pool.Config` (runtime), merge |
| `router.standby` | `LeaderElection` + `RedisLockStore` |
| `router.observability` / `.prometheus` / `.api` | metrics collector, warning store, exposition, §9.1 monitoring API |

**Rulings taken while building** (all recorded in `docs/spec/router.md`):

| Q | Ruling |
|---|---|
| Q3 | Two nested retry layers collapse to one flattened schedule; breaker accounting stays **per burst** |
| Q16 | Java scheduler propagates `dispatchMode` and `poolCode` — Go publishes neither, so its ordered path is dead code. Go: mode half done (`7414bc5`), pool half correctly reverted (`7ed2dba`) |
| — | Pool codes namespaced `{clientIdentifier}-{code}`, resolved at publish time; router synthesises `*-DEFAULT-POOL` on demand |
| Q17 | A malformed Postgres row **moves to `queue_messages_failed`** — one bad row used to stop the queue permanently |
| Q28 | **Every** restart attempt counts, not only successful ones — Go's version meant a consumer that could never be rebuilt never escalated |
| Q51 | Carry the real 2xx status (Java done; Go fix in `router-fixes.md`) |
| Q54 | Honour `flushGroup` from any target; revisit logged in `improvements.md` |
| — | 3xx is a **permanent** error: ACK with an error notice, never retried |
| — | Ordered-head failure split by kind: 502/503/504 and transport NACK the group back to the broker; 500 retries 3× then ACKs |

**Open, worth a ruling:** Q56 (`/monitoring/standby-status` reports the lock
key as `instance_id`, so every instance reports the same value — useless for
the one question it answers). Plus Q4–Q15, Q18–Q50 in `router.md` §13, where
"keep" is the standing default.

**Watch items recorded, not solved:** the unavailable path retries at the
*broker's* cadence (nack delay is advisory), so the breaker is what actually
protects a downed target; and ACK-on-500 assumes the target re-drives what
it rejected — true of the platform's dispatch endpoint, not of a third-party
ordered target.

### Hardening pass (2026-08-25)

Four commits after the data plane landed, prompted by an architecture
review. Three confirmed message-loss defects, all sharing a shape worth
remembering: **the loss was invisible from the broker.** The message left,
which is the normal thing to happen, so no counter, log or alert could
distinguish it from a delivery — and in two of the three, a test existed
that asserted the wrong thing and passed.

| Defect | Why it was invisible |
|---|---|
| `MediationOutcome.targetUnavailable()` was a *defaulted* boolean and only two of seven outcomes overrode it, so `CircuitOpen`/`RateLimited`/`Deferred` inherited "the message is at fault" — an ordered group whose head met an open breaker was ACK-deleted, siblings and all | An ACK is what success looks like |
| A worker interrupted mid-backoff returned without releasing in-flight ownership; the redelivery it relied on was then classified as a duplicate and dropped | No broker call at all, so nothing to observe |
| `NatsQueue` and `SqsQueue` each opened an expensive resource and then did more work that can throw before anything owned it — and `QueueFactory` turns the throw into an empty `Optional`, so the reconfigure loop re-leaked on **every config poll** | A failed queue build is logged; the strand is not |

Design consequences kept:

- **No defaulted answers on `MediationOutcome`.** `disposition()` and
  `statusCode()` are both abstract, so all seven records must answer and a
  new outcome cannot inherit a wrong one. `OrderedGroups` switches on the
  disposition rather than on a boolean.
- **`Broker.release(message)`** is a third verb beside ack and nack: give up
  ownership, say nothing to the broker. Nacking would race the broker's own
  redelivery.
- **JFR events at the choke points** (`observability.jfr`) — `MessageSettled`
  on every message that leaves, carrying *who decided* as well as what was
  done; `GroupDecision` with its blast radius; `Dispatch` as a duration
  event. Tests read them back out of a real dumped recording, because a
  `commit()` that runs proves nothing about what JFR persists.
- **`Concurrently`** moved to its own package and now bounds `Pool.handBack`,
  which fanned out with no deadline on the shutdown path.

`StructuredTaskScope` for `Pool` was assessed and **rejected**: unbounded
lifetime, work arriving from consumer poll threads, no join point, and
`fork()` must be called by the scope owner. It belongs where the fan-out is
bounded and joined, which is where it already is.

### Correctness over conformance (2026-08-25)

Owner: *"You have been helping with correctness. I don't just want blind
conformance."* Go is **evidence of what Go does, not of what is correct**.
The port is the one chance to fix what Go shipped, so a harness that freezes
Go's behaviour would make Java inherit those defects and then fail Java for
being right.

`conformance/mediation-outcomes.json` is the §6 outcome table made executable
— 28 cases, language-neutral, stated as HTTP responses so both
implementations run the same file. Where they differ the row asserts the
better behaviour: `correct` names the side, `basis` argues it from the
behaviour itself. **Enforced in code** — a divergence missing either field
fails the build, because a row that does not say which side is right has in
practice picked Go.

Standing divergences (verified against Go `819b390`, not assumed):

| Case | Correct | State |
|---|---|---|
| real 2xx status | java | open — `common.Success()` hard-codes 200 |
| 3xx as permanent | java | open — falls to Go's `default` arm, retried for ever at status 0 |
| 1xx | both | benign client-library difference; every field deciding the message's fate agrees |
| 501 | both | **agreed** — Go fixed it in `4f2d52c`, the corpus caught Java still wrong |

Two defects found while building it, both Java: 501 falling into the generic
`>= 500` branch, and `RateLimited.statusCode()` returning 0 when the outcome
is produced only from a 429.

### Build and tooling notes (2026-08-25)

- **`--enable-preview` is now genuinely enabled** — compiler args *and*
  surefire `argLine`. CONVENTIONS §8 had claimed it was when it was not.
  This **pins the runtime to the Java 25 feature release**: 25.0.1 → 25.0.2
  is fine, 25 → 26 needs a recompile.
- Docs now use `$(mise where graalvm)` instead of a hardcoded JDK path —
  `agent-prompts.md` is pasted into subagent prompts, so a point-release
  bump used to break every delegated agent.
- `timeout(1)` is **not** on macOS. Use surefire's `-Dsurefire.timeout=<s>`
  to bound a test that may hang; `timeout ... mvn` silently exits 127 and
  looks like a passing mutation check.
- **Concurrent Maven runs share `server/target/` and clobber each other.**
  Several "flaky" failures today were this, not real. An agent's completion
  notification does **not** mean its build has stopped — check for live
  Maven processes before trusting a result, and prefer `mvn clean test`
  after any interface change.

### Router work remaining (2026-08-25)

Mounting into the platform server is **done** — `Server.java` starts the
router before the listeners bind and drains it after they stop.

Ordered by what unblocks the most:

1. ~~**Java warning service.**~~ **DONE 2026-08-25**, in two halves. `HttpMediator` takes a
   `Warnings` collaborator and every permanent ACK-drop now raises one; the
   corpus asserts a `warning` column on all 28 cases. `Warnings` moved from
   `manager` to `observability` beside its implementation — the raisers are
   spread across three packages and the contract should not sit inside one
   caller's. **Still open:** the `WarningStore.AUTO_ACKNOWLEDGE_AGE` question
   (constant 45) — deliberately left, it needs an owner ruling, not a silent
   fix.
2. ~~**ELBv2 `TargetGroup`**~~ **DONE 2026-08-25.** `Elbv2TargetGroup` wires
   the three calls behind `AlbTraffic`'s already-tested policy, and `Traffic`
   is now `AutoCloseable` so the SDK client is released on shutdown. **The
   router has no `TODO(port)` left.**
3. ~~**Pool gaps found against Go.**~~ **DONE 2026-08-25.** `markRetrying`
   had no production caller, so two guards that read `attempts` were
   unreachable; `runImmediate` never read `disposition`, so nothing went back
   to the broker and no in-place retry was bounded; the live mediating view
   was missing. Bounding was then applied to the ordered path too. An
   unspecified `dispatchMode` now defaults to **`NEXT_ON_ERROR`**, matching
   Go — Java defaulted to `IMMEDIATE`, which silently gave no ordering to a
   producer that needed it.

4. ~~**Four monitoring routes.**~~ **DONE 2026-08-26** — all five, including
   the whole of `GET /monitoring/in-flight-messages/detail`, which turned out
   to be unported rather than merely missing its `MEDIATING` branch. Outcome
   and every decision in `docs/spec/monitoring-routes.md` "Outcome". The
   router's §9.1 surface now has nothing left that a live data source exists
   for.

   Two claims the plan inherited from the class doc were wrong on re-reading
   the code: the **30-minute window was already kept** (`BrokerStatsCache`
   has `HISTORY` + `windowed`), and **`totalDeferred` is not missing data** —
   Go's `Defer` verb has no production caller, so the field is structurally
   zero on both sides (`router-fixes.md` Fix 10 asks the owner what to do
   about it).

   Found and fixed on the way: `ForceAckResponse.wasMediating` was hard-coded
   `false`, telling every operator force-acking a wedged message that no
   attempt was running. `BrokerStatsCache.refresh` now samples **outside**
   its lock — with an operator-triggered refresh added, holding the monitor
   across broker I/O would let one unreachable broker hang every read of the
   cache during exactly the outage someone is looking at it for.

   Twelve mutation checks, all killed. One (`ageSeconds`' clamp) survived
   first time because it was unreachable after a successful refresh; it is
   now pinned by a backwards clock step, which is the only way it fires.

5. **Go runner Phase 1** (`conformance/go-runner.md`) — Go repo, not this one.
   Needs no Go changes and asserts six of seven fields.
6. **Go runner Phase 2** — extract Go's inline `switch outcome.Result`
   (`pool.go:901`) into a pure function so `disposition` becomes assertable.
7. **Drop-in verification** — side-by-side replay against the Go binary, then
   a cutover rehearsal.

Smaller, tracked in place: `Q41` metrics contract
(`RouterPrometheusCollector.java:54`). **`Q19` is closed** — see below.

### Go drift check, 2026-08-27

Ran against Go `426ac85`. Two live Java defects found and fixed, three auth
changes recorded for the unported side, and several places where Java was
already right.

**Fixed — `Q19`, NATS at-least-once was silently at-most-once.** The broker
id was `<streamSeq>:<consumerSeq>`, and the **consumer** sequence counts
deliveries, so it changes on every redelivery. The tracker read a changed
broker id under a known app id as "a rival copy exists — an external process
requeued work we still own" and ACK-deleted the arrival, so JetStream
destroyed its own copy on every ack-wait lapse; a later release or pool flush
then lost the message with only a "no pending message for receipt" warning.
Both identities now derive from the **stream** sequence, which every delivery
shares. `consumerSeq` was removed from `classify` entirely rather than left
unused, so the mistake is unavailable. Go ruled by fixing it the same way
(`20e9fe7`) with the loss demonstrated, which turns a parked question into a
defect. **The existing test asserted the bug** (`isNotEqualTo` on a
redelivery's broker id) and passed; it now asserts the same id.

**Fixed — TSID string order was noise below the millisecond.** Layout was
`ms | random | seq`; ids sort as strings and Crockford Base32 is
order-preserving, so the bit order **is** the sort order, and the counter that
guarantees uniqueness contributed nothing to ordering. Two ids minted in one
millisecond came out backwards about half the time — and these ids are the
keyset-pagination cursor. Now `ms | seq | random`, matching Go `17e737a`.
**The test documented the defect instead of catching it**: it decoded
`(ms, seq)` and compared that, with a comment explaining that the random bits
sat between them. It now compares the raw strings, which is what actually
sorts in an index.

**Already correct in Java, confirmed against Go's fixes:** redirects are not
followed (`Redirect.NEVER`, with the same reasoning Go reached in `2468140` —
301/302/303 downgrade POST to GET and drop the body, delivering nothing and
reporting success); `Pool.mediating` is keyed by worker `Thread`, not message
id, so `activeWorkers()` cannot under-report or let a loser's exit delete a
winner's entry; there is no `ExtendVisibility`; the Postgres quarantine is
`queue_messages_failed` keeping the latest failure. `89b195e` shows Go
adopting our `NEXT_ON_ERROR` ruling including logging an unrecognised mode —
full convergence on the router's enum.

**Recorded, not fixed:**

- `backlog.md`: **two `DispatchMode` enums with opposite defaults**. The
  router's takes `NEXT_ON_ERROR` per the ruling; `platform.subscription`'s
  still silently takes `IMMEDIATE`. Go applied the ruling at every layer.
  Needs one line from the owner because it changes how existing rows read.
- `dispatchjob.md`: Go `5762aa1` — `BLOCK_ON_ERROR` must hold a group while an
  **earlier** job is `FAILED`/`ERROR` **or backed-off**, compared
  **positionally**. A backed-off job is `PENDING` with a future
  `scheduled_for`, so nothing treated it as holding anything and its
  successors overtook it. The Java scheduler is unported: a note to
  implement, not a defect to fix.
- `auth-core.md` §0 (new): `de868dd` (`FindByServiceAccount` hydrates roles
  only, so an app-scoped SA's token carried an empty `applications` claim),
  `304338a` (the `scope` claim must be bounded by the role ceiling at every
  tier; an explicit request intersecting to nothing is now `invalid_scope`),
  `8d7ddbc` (per-client narrowing must emit canonical `{app}:{role}` names,
  not short names). Auth is unported, so these are corrections the port must
  reproduce rather than defects to fix — `de868dd` also lands on the
  `serviceaccount` unit still on the platform queue.

### Two cross-cutting changes, 2026-08-26

**Jackson 3 (`tools.jackson`)** — owner ruling, reversing the earlier "stay on
2.x": the migration cost is paid once either way, and paying it now avoids
paying it on someone else's schedule. `jackson-annotations` keeps its
`com.fasterxml.jackson.core` coordinate, which Jackson 3 never renamed.

The trap, because it will be hit again: **do not add `jackson-datatype-jsr310`
or `jackson-datatype-jdk8`.** Jackson 3 folded both into databind —
`java.time` lives in `tools.jackson.databind.ext.javatime`. The
`tools.jackson.datatype` jsr310 artifact is *retired*, which is why the BOM
looks like it points at an unpublished version. That is not a broken pointer
to work around, and pinning `3.0.0-rc2` to satisfy it puts a pre-GA
dependency in the build to get behaviour databind already has. Verified: with
no module registered, databind renders an `Instant` as
`2024-03-05T07:08:09.123456789Z` by itself. The fixed six-digit RFC 3339
layout comes from the `micro` `SimpleModule` in `platform/shared/json/Json.java`,
which must stay registered — `JsonTest` pins it, and removing `micro` fails 6
of its 10 assertions. The one remaining jsr310 dependency, in `sdk`, is real:
openapi-generator's templates hardcode Jackson 2.

**`RouterApi` split** — 1337 lines into 11 files, none over 241, `RouterApi`
itself 169. Grouped by resource, not by verb, and each group registers its own
routes so a path and its handler stay adjacent (what Go gets from
`huma.Register`). `Wire` is the `dto.go` counterpart; `Http` holds the shared
query reading and the two §9.1 error shapes. A pure move — no handler body
changed. Checked as one: 55 routes before and after, identical set, each still
bound to the **same** handler, compared as path→handler pairs rather than
paths alone.

### The `DashboardHandlerTest` "flake" was not a flake (2026-08-26)

It failed 3 of 4 in one full-suite run and passed standalone and on three
clean re-runs. First written up here as the Jetty first-connection race
`RouterApiTest.warmUp` absorbs. **That was wrong**, and the giveaway was in
the line already captured: `Failures: 3, Errors: 0` in 0.044s. A dropped
connection surfaces as an `UncheckedIOException` — an **Error**. Three
*assertion* failures, with no time spent, means the requests all succeeded
and returned the wrong body.

The reconstruction that fits every detail: `DashboardHandler` reads
`dashboard.html` from `target/classes` **once, at construction**, and
`readAllBytes` returns whatever has been copied so far without complaint. A
second Maven run over the same `target/` re-copies that resource; catch it
mid-copy and the handler renders an empty page and serves it as `200
text/html`. The three tests sharing the `@BeforeAll` instance then fail their
body assertions, while `rootMountSubstitutesEmptyPrefix` — the only one that
constructs its own handler, later, once the copy has finished — passes. Three
of four, and exactly those three.

So this is the **concurrent-Maven hazard `Claude.md` already documents**,
caught in the act, not a test to stabilise. A `warmUp` retry would not have
prevented it and would have been a fix for a failure mode that was not
occurring.

What was worth changing:

- **`DashboardHandler` now refuses an incomplete template** — it must contain
  the `__FC_API_BASE__` token *and* end in `</html>`. Both are needed: the
  token sits at byte 845 of ~91 KB, so it survives almost any truncation. The
  rules live in a package-private `validated(String)` so a test can state a
  partial document rather than contrive one on a classpath. This matters
  beyond the test — a bad build would otherwise ship a blank dashboard that
  answers 200.
- **`TestHttp.close()` now closes its `HttpClient`.** It never did: 1200
  create/close cycles left 1401 live threads, and closing it brings that to
  1203. The residual ~1 per instance is Javalin's own non-daemon helper
  (`JettyServer.kt:41`), not reclaimed by `app.stop()` and not fixable from
  the harness — recorded in `TestHttp` so nobody re-hunts it there.

Neither is the cause; the cause was running two builds over one `target/`.

### SDK drift picked up from Go (2026-08-26)

Go `7db14b7` / `c7dcb4a` / `2d10924` touched the SDKs' handling of the
application code. Ported and recorded in `docs/spec/sdksync.md` §6:

- `DefinitionSet.defineFromEnv()` + `APP_CODE_ENV` added to the Java SDK, an
  explicit factory rather than a fallback inside `define`. Blank/unset throws
  **at the call site** instead of surfacing later as
  `POST /api/applications/null/…`.
  Java's version splits out `defineFrom(String)` so both branches are
  assertable: Go's test can only exercise whichever branch the machine's
  environment happens to give it, which leaves the rejection path — the one
  that matters — untested wherever the variable is set.
- **No per-definition application override** in the Java SDK, deliberately;
  the Laravel one exists only because its definitions are found by scanning
  the filesystem. Recorded so nobody "fixes" the absence.
- `CreateDispatchJobDto` documented `IMMEDIATE` as the platform default. It
  is `NEXT_ON_ERROR` (owner ruling, below). Corrected here; Go fixed the
  Laravel and TypeScript SDKs in `2d10924` and **missed its own java-sdk** —
  `router-fixes.md` Fix 9.
- `docs/spec/router.md` §2's `dispatchMode` wire row still said "`IMMEDIATE`
  (default when absent/unknown)". Now records the ruling, the deviation from
  Go, and that an *unknown* mode is logged rather than folded into the
  default.

## Next wave

**Superseded 2026-09-05 by [`docs/port-plan.md`](port-plan.md)** — the router
half of the old plan is done (drive closed 2026-09-02); the platform half is
re-sequenced there with the Sonnet/orchestrator split. Short form: Phase 0
housekeeping (auth ruling `3b64775`, the PoolTest flake, fcdev download-on-
first-run) → Phase 1 remaining aggregates + SDK batch + BFF → Phase 2 stream,
outbox, scheduled-job scheduler, purger, MCP, Secrets Manager → Phase 3 auth
(gated on rulings, surfaced in three batches) → Phase 4 cross-cutting →
Phase 5 drop-in verification and CI.

## Owner rulings taken 2026-08-25

| Question | Ruling |
|---|---|
| `BLOCK_ON_ERROR`, untried siblings | **Keep Java's**: ACK them. Go's nack returns them on the *broker's* timer, by which point the head is gone, so the first sibling becomes the new head and is delivered past the failure — breaking the guarantee the mode is named for. Residual gap recorded: nothing marks those jobs `FAILED` for review (platform work). |
| Postgres quarantine | **`queue_messages_failed`, keep the LATEST failure.** Java already matches; Go-side change is Fix 2. |
| `AUTO_ACKNOWLEDGE_AGE` | **1 hour.** |
| Unspecified `dispatchMode` | **`NEXT_ON_ERROR`.** The failure modes are asymmetric: wanting concurrency and getting ordering is visible and cheap to fix; needing ordering and silently getting none is invisible and lands in the target's data. |

**Still needing a ruling:** the webhook's minimum severity (set to `WARNING`,
so `INFO` is dropped — a channel-noise judgement, one line to change).

## Owner rulings outstanding

See `docs/backlog.md` "Owner questions". Rule = no ruling → behaviour kept
as Go has it; a ruling becomes a spec line + conformance test (+ a
"deliberate deviation" note if behaviour changes).

## How to resume

- Build: `JAVA_HOME=$(mise where graalvm) mvn -q test`
  (first run downloads embedded PG 18).
- Per-aggregate pipeline: `docs/process/agent-prompts.md`.
- Reference Go repo: `../flowcatalyst-go` (read-only; never modified).
- Commit per landed+audited unit.
