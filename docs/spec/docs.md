# Documentation surface — behavioural spec

The contract for `io.flowcatalyst.platform.docs`. Derived from the lockfile
(`/api/docs*` — three read operations, tag `docs`) plus the
`POST /api/applications/{appCode}/docs/sync` operation (tag `sdk-sync`, whose
*route* belongs to the sdksync unit and is **not** wired here — only the sync
operation and its store are). Questions marked **load-bearing or accident?**
need an owner ruling — until ruled on, the behaviour is kept.

## 1. Purpose & boundaries

Administrators read documentation in *Platform → Documentation*. Two sources
feed the surface, and the read API presents them side by side:

| Source | Where it lives | Who writes it | Served as |
|---|---|---|---|
| **Platform pages** | `server/src/main/resources/docs/published/*.md` — compiled into the jar so the platform's own docs always match the running build. Deliberately *not* the whole `docs/` tree: plans, specs and handovers stay repo-only. | the repo (verbatim copies of the Go `docs/published/*.md`) | raw Markdown |
| **Application pages** | table `app_docs` | each application, through the SDK sync surface (declarative full replace) | raw Markdown |

Rendering (Markdown, Mermaid fences) is the SPA's job; the API never
transforms page content.

## 2. Platform (published) pages — the embedded index

| Rule | Behaviour |
|---|---|
| Corpus | every classpath resource `docs/published/<name>.md` (directories and non-`.md` entries ignored) |
| Order | **filename ascending**; the `NN-` prefix of each filename exists to fix the reading order (`10-platform-overview.md` first) |
| Slug | the filename without `.md` and without a leading `\d+-` prefix (`10-platform-overview.md` → `platform-overview`). Prefixed forms (`10-platform-overview`), paths (`published/…`) and traversal (`../x`) are **not** slugs — they are simply absent → 404 |
| Title | the page's first line that, trimmed, starts with `# ` — the heading text trimmed; else the slug |
| Content | the file bytes verbatim (UTF-8) |
| Loading | once per process, lazily, memoised; an unreadable corpus yields an empty platform list rather than an error |

Current corpus (titles are read from the files, not hard-coded):

| File | Slug | Title |
|---|---|---|
| `10-platform-overview.md` | `platform-overview` | Platform Overview |
| `20-messaging-and-delivery.md` | `messaging-and-delivery` | Messaging & Delivery |
| `30-identity-and-access.md` | `identity-and-access` | Identity & Access |
| `40-portal-users.md` | `portal-users` | Portal Users Architecture |
| `50-applications-and-integration.md` | `applications-and-integration` | Applications & Integration |

**Load-bearing or accident?** (1) Two files with the same slug after prefix
stripping (`10-x.md`, `20-x.md`) — the Go index keeps the *last* one by name
for lookup but lists both. Kept as "last wins for get, both listed"; an owner
may prefer a startup failure. (2) Title is the *first* `# ` line anywhere in
the file, not necessarily line 1 — kept.

## 3. Application pages — `app_docs`

| Column | Type | Java | Notes |
|---|---|---|---|
| `id` | `VARCHAR(17)` PK | `id` | `doc_` + 13-char TSID (`EntityType.APP_DOC`) |
| `application_id` | `VARCHAR(17)` NOT NULL | `applicationId` | no FK; orphaned rows (application deleted) are skipped by the index |
| `slug` | `VARCHAR(120)` NOT NULL | `slug` | unique per application (`UNIQUE (application_id, slug)`); kebab-case (§5) |
| `title` | `VARCHAR(200)` NOT NULL | `title` | explicit, else derived (§5) |
| `content` | `TEXT` NOT NULL | `content` | raw Markdown |
| `position` | `INT` NOT NULL DEFAULT 0 | `position` | the page's index in the last sync payload — the list order |
| `created_at` | `TIMESTAMPTZ` NOT NULL | `createdAt` | insert-only: an updated page keeps it |
| `updated_at` | `TIMESTAMPTZ` NOT NULL | `updatedAt` | re-stamped on every sync that lists the slug |

Index `idx_app_docs_application (application_id, position)`. The schema is
already in `V1__baseline.sql` (Go migration 044) — no new migration.

Reads:

| Read | Order | Notes |
|---|---|---|
| summaries for one application (`slug`, `title`) | `position ASC, slug ASC` | the per-application group in the index |
| distinct application ids with at least one page | unspecified | the index's spine; groups are sorted afterwards (§4) |
| one page by (application id, slug) | — | absent → empty |

Write — **one declarative replace per application, inside the caller's
transaction** (§5): the stored set becomes exactly the payload.

| Payload slug … | Effect | Counted as |
|---|---|---|
| not stored yet | insert: new `doc_` id, `position` = payload index, `created_at = updated_at = now` | `created` |
| already stored | update in place: `title`, `content`, `position`, `updated_at`; `id` and `created_at` kept | `updated` |
| stored but not in the payload | deleted | `deleted` |

The result also carries the payload's slugs in payload order (`syncedCodes`
on the wire). An empty payload deletes every page of the application.

## 4. HTTP surface (lockfile, tag `docs`)

Every route is `GET`, gated in the handler by `Permission.DOCS_VIEW`
(`platform:admin:docs:view`; anchors always pass). Unauthenticated → 403
`UNAUTHENTICATED`; missing permission → 403 `PERMISSION_REQUIRED`
(`permission required: platform:admin:docs:view`). Errors are the
`ErrorModel` envelope.

| Method / path | Inputs | Success | Errors |
|---|---|---|---|
| `GET /api/docs` | — | 200 `DocListResponse` | — |
| `GET /api/docs/platform/{slug}` | path `slug` | 200 `DocResponse` | 404 `Doc_NOT_FOUND` `Doc not found: <slug>` |
| `GET /api/docs/applications/{appCode}/{slug}` | path `appCode`, `slug` | 200 `DocResponse` | 404 `Application_NOT_FOUND` `Application not found: <appCode>` when the code is unknown; 404 `Doc_NOT_FOUND` `Doc not found: <slug>` when the app has no such page |

Wire shapes (field order is the lockfile's; every field required, never `null`):

| Schema | Fields | Notes |
|---|---|---|
| `DocListResponse` | `platform: DocSummary[]`, `applications: AppDocsGroup[]` | both arrays always present (`[]`, never `null`) |
| `AppDocsGroup` | `applicationCode`, `applicationName`, `docs: DocSummary[]` | one per application that has ≥ 1 page **and** still exists |
| `DocSummary` | `slug`, `title` | |
| `DocResponse` | `slug`, `title`, `content` | `content` is raw Markdown |

Index composition (`GET /api/docs`):

1. `platform` = the published index in filename order (§2).
2. `applications` = for each distinct `application_id` with pages, look the
   application up by id; **unknown ids are skipped silently** (orphaned rows
   never break the index); group = application `code`, `name`, summaries in
   `position, slug` order; groups sorted by `applicationName` ascending.

**Load-bearing or accident?** (3) Group sort is by name only; two
applications with the same name have an unspecified relative order in Go.
Java sorts by `(name, code)` — a deterministic refinement, never a
contradiction. (4) The application lookup is by *id* per group (N+1 reads);
kept — N is the number of applications that sync docs.

## 5. Sync operation — `SyncAppDocs` (`SyncAppDocsCommand`)

Ported here because its store and rules are this unit's; the route
`POST /api/applications/{appCode}/docs/sync` (200 `SyncResultResponse`
`{applicationCode, created, updated, deleted, syncedCodes[]}`, coarse gate
`platform:application-service:docs:sync` any-of, `appCode` → application
resolution) is the sdksync unit's and is **not registered** by this package.

Command: `SyncAppDocsCommand(applicationId, applicationCode, docs: SyncAppDocInput[])`,
`SyncAppDocInput(slug, title?, content)`. Phases:

| Phase | Rule | Error (400 unless noted) |
|---|---|---|
| validate | `applicationCode` non-blank | `APPLICATION_CODE_REQUIRED` |
| validate | at most **100** pages | `TOO_MANY_DOCS` "an application may sync at most 100 documentation pages" |
| validate | each slug, **trimmed**, matches `^[a-z0-9][a-z0-9-]*$` | `SLUG_INVALID` "doc slug <slug> must be kebab-case (lowercase letters, digits, hyphens)" |
| validate | no slug (after trimming) appears twice | `SLUG_DUPLICATE` "doc slug <slug> appears more than once" |
| validate | each page's content ≤ **512 KiB** (UTF-8 bytes) | `DOC_TOO_LARGE` "doc <slug> exceeds 512KB" |
| validate | running total of content bytes ≤ **4 MiB** (checked after each page, in order) | `PAYLOAD_TOO_LARGE` "documentation sync exceeds 4MB total" |
| authorize | `Checks.checkApplicationAccess(current, applicationId, applicationCode)` | 403 `FORBIDDEN` / `UNAUTHENTICATED` |
| execute | title per page = explicit title trimmed when non-blank, else the content's first `# ` heading (trimmed), else the slug; then the declarative replace (§3) in the operation's transaction | — |

Result: `ReplaceResult(created, updated, deleted, slugs)`; the sdksync
handler maps it to `SyncResultResponse` with `syncedCodes = slugs`.

Pinned slug table (`AppDocSlug.parse`):

| Rule | Accepted | Rejected |
|---|---|---|
| lowercase alnum + hyphen, starts alnum | `guide`, `getting-started`, `v2`, `2fa-setup`, `a` | `Getting-Started`, `-lead`, `has space`, `under_score`, `dots.md`, `` (empty), `   ` (blank) |
| trimmed before matching | `  guide  ` → `guide` | — |

**Load-bearing or accident?** (5) The sync writes **no domain event and no
audit row** in Go (it is a repository replace, not a use case). Kept: the
Java operation is a `TxOperation` that writes through the repository in one
transaction and emits nothing — flag if the owner wants an `AppDocsSynced`
rollup in `msg_events`/`aud_logs` like the other sync operations. (6) Sizes
are byte counts of the UTF-8 content, not character counts — kept.
(7) Validation is all-or-nothing and stops at the first failing page, in
payload order — kept.

## 6. Tests

- `PublishedDocsTest`: order starts at `platform-overview`; slugs carry no
  prefix; titles from the first heading; bad slugs absent.
- `AppDocTest`: slug accept/reject tables; title derivation table.
- `AppDocRepositoryTest`: replace creates / updates in place (id + `created_at`
  kept) / deletes unlisted; order by position; orphan-safe spine.
- `SyncAppDocsTest`: validation table, authorization, round trip.
- `DocsApiTest`: gate (anon / no-permission / granted client / anchor), the
  three envelopes, 404s, `applications: []` when nothing is synced.
