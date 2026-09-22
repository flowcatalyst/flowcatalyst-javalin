# Spec — the caller's claims as an object a function can ask questions of (owner request 2026-09-22)

Today `Caller.Principal` in the `function-api` jar is `(id, type, clientId, permissions)` — raw
strings the function interrogates by hand, and the host throws the rest of the verified token
away (`FnHttpServer.principalFrom`). The owner wants a claims object with `hasPermission()` and its
kin, so a function's authorisation check is one line and answers **exactly** as the platform's
own would. Amends `function-api.md` §I3 and `function-invocation.md` §3/§4.

## 1. `Caller.Principal`, widened

```java
record Principal(String id, String type, String tier, List<String> clients, List<String> roles,
                 List<String> applications, boolean allApplications, Set<String> permissions)
```

Every field comes from the verified token (`TokenClaims`: `sub`, `principal_type`, `tier`,
`clients`, `roles`, `applications`, `all_applications`, `scope`) — the host maps them all now, not
four of them. `email`/`name` are **not** carried: a function has no business with them, and they
are PII the token happens to hold. The record is immutable; lists and the set are defensive copies.

Methods — each the platform's own rule, restated, never a new one:

| Method | Semantics (the server's) |
|---|---|
| `hasPermission(String required)` | `Permission.grants(held, required)`: exact, or a held code whose segments match `required`'s segment for segment with `*` as a wildcard, same segment count |
| `hasAnyPermission(String…)` / `hasAllPermissions(String…)` | over `hasPermission` |
| `hasRole(String code)` | `roles.contains(code)`; roles ARE in the token (`roles` claim), so this needs no mint change |
| `isAnchor()` | `tier == ANCHOR` (`AuthContext.isAnchor`) |
| `canAccessClient(String clientId)` | `isAnchor() \|\| clients.contains(clientId)` (`AuthContext.canAccessClient`) |
| `canAccessApplication(String applicationId)` | `allApplications \|\| applications.contains(applicationId)` (`AuthContext.canAccessApplication`) — by **id**, as the server; document that the token carries ids |
| `clientId()` | `Optional<String>`: the one client when `clients` has exactly one entry that is not `*`, else empty — the value the old `clientId` field held |

`Caller.Platform` and `Caller.Anonymous` are unchanged. `Webhook` callers are not principals.

## 2. Where the rule lives, and how the two copies are kept honest

`function-api` has **no dependencies** and compiles for Java 21 (a function author's toolchain), so
the matching logic is a private static method in the jar — ~10 lines, a copy of
`Permission.matches`. The copy is pinned, in the `server` module (which sees both): a table of
`(held, required)` cases — exact, wildcard segment, wildcard in the middle, different segment
counts, empty, null — run through **both** `Permission.grants` and `Caller.Principal.hasPermission`,
asserting the answers are identical; and the same for `canAccessClient` /
`canAccessApplication` / `isAnchor` against an `AuthContext` built from the same claims. Mutant:
change the jar's copy (drop the segment-count check) ⇒ the agreement test fails.

## 3. The host

`FnHttpServer.principalFrom(TokenClaims)` maps every field. Nothing else on the host changes: the
`auth: platform` check, reach, and the versioned form's `version:invoke` requirement are as they are.

## 4. The sample and the docs

`examples/function-hello`'s `/api/hello/{name}` endpoint answers `403` with
`{"error":"PERMISSION_REQUIRED"}` unless `hasPermission("hello:greeting:greet")` — a permission the
`hello` application defines; the sample's test exercises both outcomes through the real loader.
`docs/functions.md` §6 gains "Authorising the caller" with the table above.

## 5. Tests

| # | Behaviour | Mutant |
|---|---|---|
| P1 | agreement (§2): every case answers the same from the jar and the server, for `hasPermission`, `canAccessClient`, `canAccessApplication`, `isAnchor` | drop the segment-count check in the jar; make `canAccessClient` ignore anchor |
| P2 | the host maps every claim: a token with tier `CLIENT`, two clients, two roles, one application, `all_applications=false` reaches the function as a `Principal` with exactly those (through `FnHttpServerTest`'s bearer harness) | drop `roles` from the mapping |
| P3 | `clientId()` is the single non-`*` client, else empty (one, two, `*`) | return the first always |
| P4 | the sample: a caller with the permission gets 200; one without gets 403 and the function body was not run (the counter file) | drop the check |
| P5 | `email`/`name` never reach the function (the `Principal` has no such accessor — a compile-time fact; assert the record's components by reflection so a later addition is a deliberate act) | — |
