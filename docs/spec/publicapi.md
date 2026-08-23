# Public API + branding — behavioural spec

The contract for `io.flowcatalyst.platform.publicapi`: the pre-login,
unauthenticated read surface (`/api/public/*`) and the branding resolver it
shares with transactional email (platform name, login/email theme). Derived
from the Go `internal/platform/publicapi` + `internal/platform/branding`
handlers and from what the SPA (`frontend/src/stores/{loginTheme,appTheme,
platformConfig}.ts`) fetches before sign-in. These routes are **not in the
OpenAPI lockfile** (chi-mounted outside huma) — `LockfileCoverageTest` lists
`/api/public/` under `OUTSIDE_LOCKFILE_PREFIXES`. The Java is written *from*
this; tests assert it. Questions marked **load-bearing or accident?** need an
owner ruling — until ruled on, the behaviour is kept.

## 1. Purpose and boundaries

- Two documents, served on three `GET` routes, that the SPA calls **before
  login** (login, logout, forgot / reset-password and portal-login pages,
  the app sidebar once signed in): the feature flags + brand name (on
  `/api/public/platform` and the legacy `/api/config/platform`), and the
  login-page theme.
- Mounted **outside the bearer/cookie authenticator**: an anonymous request
  (no `Authorization`, no cookie, no test headers) gets `200`. A stale
  session must never turn the login page's theme fetch into a 401.
  `Platform.isPublicPath` already lists `/api/public/`.
- **Read-only**: no aggregate, no operations, no events, no `Persist`, no
  permissions. Both routes read **one `GLOBAL` platform-config coordinate
  each** through `PlatformConfigRepository.findByCoordinate` (spec
  `platformconfig.md` §10); nothing is written here.
- **Always `200` + a JSON object.** A missing row, a blank value, malformed
  stored JSON or a failed lookup all degrade to the defaults — the SPA
  layers the payload over its own defaults (`{...DEFAULT, ...data}`) and a
  non-2xx would only make it log a warning and use them anyway.

## 2. HTTP surface

| Method / path | Inputs | Success | Body |
|---|---|---|---|
| `GET /api/public/platform` | — | 200 | `PlatformResponse` `{features: {messagingEnabled}, platformName}` |
| `GET /api/config/platform` | — | 200 | the same `PlatformResponse`, same handler — the legacy path the SPA's `platformConfig` store fetches pre-login (§9 Q1, resolved) |
| `GET /api/public/login-theme` | `clientId` query — **accepted and ignored** (§5) | 200 | `LoginThemeResponse` — every field optional, `{}` when nothing is configured |

No error responses are produced by these handlers. Unknown `/api/public/…`
paths fall through to the platform's 404 envelope like any other `/api/**`
path.

`GET /api/config/platform` is what the SPA's `platformConfig` store actually
fetches (`router/guards.ts` → `loadConfig()` on first navigation, i.e. also
on the login route); it is registered by `PublicApi` next to
`/api/public/platform`, listed as public in `Platform.isPublicPath`, and
excluded from lockfile drift in `LockfileCoverageTest` (§9 Q1).

## 3. Wire shapes

`PlatformResponse` (field order as written):

| Field | Type | Value |
|---|---|---|
| `features.messagingEnabled` | boolean | always `true` — static today ("future expansion adds env-driven flags") |
| `platformName` | string | the resolved platform name (§4); never absent, never blank |

`LoginThemeResponse` — the stored theme document **echoed verbatim**: the
wire shape *is* the stored JSON shape (§5). Absent (`null`) fields are
omitted; an explicitly stored `""` is kept (Jackson `NON_ABSENT`, Go
pointer + `omitempty` — both emit `"brandName":""` for a stored empty string).

| Field | JSON type | SPA default when absent (`loginTheme.ts`) |
|---|---|---|
| `brandName` | string | `"FlowCatalyst"` |
| `brandSubtitle` | string | `"Platform Administration"` |
| `logoUrl` | string | — |
| `logoSvg` | string | — |
| `logoHeight` | integer | `40` |
| `primaryColor` | string | `"#102a43"` |
| `accentColor` | string | `"#0967d2"` |
| `backgroundColor` | string | `"#0a1929"` |
| `backgroundGradient` | string | `linear-gradient(135deg, #102a43 0%, #0a1929 100%)` |
| `footerText` | string | `"Secure access to your FlowCatalyst platform"` |
| `customCss` | string | — |

The SPA defaults are the SPA's; the server never fills them in (the login
page must stay branded out of the box without any config row).

## 4. Platform name (`Branding.platformName`)

Coordinate: `GLOBAL platform / branding / platform-name`
(`ConfigCoordinate.global("platform", "branding", "platform-name")`).
Default: `"Flowcatalyst"` (`Branding.DEFAULT_PLATFORM_NAME`). The same
resolver feeds the SPA (`platformName`), the email theme's default brand
name, and — later — the TOTP issuer and passkey prompts, which is why it is
one class and not a constant.

| Stored state | Result |
|---|---|
| no row | default |
| row, value blank (empty or whitespace) | default |
| row, value `"  Acme  "` | `"Acme"` — **trimmed** |
| lookup throws (DB unavailable) | default, `WARN` logged (open question 3) |

## 5. Login theme (`Branding.loginTheme` → `LoginTheme`)

Coordinate: `GLOBAL platform / login / theme`
(`ConfigCoordinate.global("platform", "login", "theme")`) — the row the
admin "Login theme" settings page writes through `PUT
/api/config/platform/login/theme` with `value = JSON.stringify(theme)`.
The value is a JSON **object** with the eleven optional keys of §3; unknown
keys are ignored. `LoginTheme.parse(storedValue)` is the one reader:

| Stored value | Parse result |
|---|---|
| no row | empty theme (`{}` on the wire) |
| `""` / whitespace | empty theme |
| `null` (JSON literal) | empty theme (Java logs it like a malformed document; Go is silent) |
| not JSON (`{not json`) | empty theme, `WARN` logged |
| JSON but not an object (`[1]`, `"x"`, `42`) | empty theme, `WARN` logged |
| `{}` | empty theme |
| `{"brandName":"Acme","logoHeight":48,"unknown":1}` | `brandName=Acme`, `logoHeight=48`, the rest absent |
| `{"brandName":""}` | `brandName=""` — kept, echoed as `""` |
| `{"logoHeight":"48"}` | Go: whole document rejected (type mismatch → empty theme). Java (Jackson scalar coercion): `logoHeight=48`. Open question 4 |

The `clientId` query parameter the SPA sends
(`/api/public/login-theme?clientId=…`) is **ignored**: the theme is global,
there is no per-client theme row and no `CLIENT`-scoped lookup. Open
question 2.

The public endpoint echoes the parsed document **without** any
normalisation: no trimming, no colour validation (that is the email
theme's job, §6, because the SPA applies the values as CSS custom
properties it owns, whereas email HTML is assembled server-side).

## 6. Email theme (`Branding.emailTheme` → `EmailTheme`)

What the branding package *adds* over the public endpoint: a resolved,
never-absent theme for server-rendered transactional email (password reset,
invitations, 2FA), layered stored-config-over-defaults so every caller stays
unconditional. Not reachable over HTTP; consumed by the email senders when
they land.

Defaults: `brandName` = §4 platform name; `primaryColor` `#102a43`;
`accentColor` `#0967d2`; no logo URL, no logo SVG, no footer text.

Layering from the stored login theme (§5) — each rule independent:

| Stored key | Rule |
|---|---|
| `brandName` | trimmed; non-blank → replaces the platform name; absent / blank → platform name |
| `primaryColor`, `accentColor` | present → `safeColor(value, default)`: trimmed, kept only if it matches the colour pattern, else the default **for that field**; absent → default |
| `logoUrl`, `logoSvg`, `footerText` | trimmed; blank → absent (`null`) |

Colour pattern (case-insensitive) — a configured colour is interpolated into
an inline `style="…"` attribute, so only hex and `rgb()/rgba()` forms pass;
anything else (keywords, `url(`, a `"` that would close the attribute) falls
back:

| Rule | Accepted | Rejected |
|---|---|---|
| hex `#` + 3..8 hex digits | `#abc`, `#102A43`, `#11223344` | `#ab`, `#123456789`, `#ggg`, `102a43` |
| `rgb(` / `rgba(` + digits `. , % whitespace` + `)` | `rgb(9, 103, 210)`, `rgba(0,0,0,.5)`, `RGB(100%,0%,0%)` | `rgb(9,103,210`, `rgb(a,b,c)`, `hsl(1,2%,3%)` |
| anything else | — | `red`, `` (empty), `#abc;"></td><script>`, `url(x)` |

Logo source for the banner `<img src>` (`EmailTheme.logoSrc`):

| `logoUrl` | `logoSvg` | Result |
|---|---|---|
| `https://…` / `http://…` / `data:image/…` (scheme compared case-insensitively) | any | the URL (hosted PNG/JPG render where mail clients block inline SVG) |
| other scheme (`javascript:…`, `ftp://…`) or absent | present | `data:image/svg+xml;base64,<base64 of the trimmed SVG>` |
| other / absent | absent | absent (brand name text is shown instead) |

`EmailTheme.render(EmailContent)` produces a self-contained HTML document
(table layout, inline styles): header banner on `primaryColor` holding the
logo `<img>` (when `logoSrc` is present, `height="40"`, `alt` = brand name)
or the brand name in white; an `<h1>` in `primaryColor` (when `heading`
given); an intro paragraph; a button on `accentColor` with white text plus a
plain-text "Or paste this link into your browser" fallback link (only when
both `buttonLabel` and `buttonUrl` given); any non-blank `afterButton`
paragraphs; a footer = `footer` trimmed, or `"This is an automated message
from <brand>. Please do not reply to this email."`. **Every text and the
URL are HTML-escaped** (`& < > " '`); colours are never escaped because
§6's pattern already confines them.

## 7. Authorization

| Where | What |
|---|---|
| Middleware | none — `Platform.isPublicPath` skips the authenticator for `/api/public/` |
| Handler | none — no `Auth.current()`, no `Checks.*` |
| Resource-level | none |

## 8. Persistence

None. Reads only, via `PlatformConfigRepository.findByCoordinate` on two
fixed `GLOBAL` coordinates.

## 9. Open questions for the owner

1. **`GET /api/config/platform`** — Go serves the platform payload at this
   second path and the SPA's `platformConfig` store fetches *that* one
   (pre-login too, from the router guard). Registering it in Java needs
   (a) `routes.get("/api/config/platform", …)` in `PublicApi.register`,
   (b) `|| p.equals("/api/config/platform")` in `Platform.isPublicPath`
   (else the authenticator 401s it), and (c) `"/api/config/platform"` in
   `LockfileCoverageTest.OUTSIDE_LOCKFILE_PREFIXES` (else drift fails).
   **Resolved (lead, 2026-08-23): all three added** — same handler, public
   path, outside the lockfile (Go parity; the SPA's pre-login
   `platformConfig` store depends on it). `PublicApiTest` pins the body and
   the anonymous `200` on both paths.
2. `?clientId=` on `/api/public/login-theme` is ignored — per-client login
   themes were never implemented server-side. Accident to keep ignoring, or
   a planned `CLIENT`-scoped lookup with GLOBAL fallback?
3. A failed lookup (DB down) returns defaults with a `WARN` instead of a
   500. Load-bearing (login page must render during an outage) or accident?
   Kept.
4. `logoHeight` typing: Go rejects a whole theme document whose
   `logoHeight` is a JSON string / negative / fractional; Java accepts
   `"48"` → 48 and negative integers. Accident in Go (the admin page writes a
   number). Kept lenient.
5. `messagingEnabled` is a hard-coded `true`. Keep static until a flag
   source exists?

