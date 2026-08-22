# Spec — `PasswordHash` (`io.flowcatalyst.platform.shared.auth`)

The one password-hashing primitive. Callers: principal create / password
reset / seeder (`hash`) and login (`verify` + `needsRehash`,
`equalizeTiming`). OAuth client secrets are **not** hashed with this — they
are reversibly encrypted elsewhere. Written from the contract
(`PasswordHashTest`, incl. the Go-minted vector), not from the Go source.

Items tagged **[owner?]** are "load-bearing or accident?" questions.

## 1. What `hash` produces

PHC string, argon2id, version 19 (0x13):

```
$argon2id$v=19$m=65536,t=3,p=4$<salt b64>$<key b64>
```

| Parameter | Value |
|---|---|
| variant | argon2id |
| version | 19 |
| `m` memory | 65536 KiB (64 MiB) |
| `t` iterations | 3 |
| `p` parallelism | 4 |
| key length | 32 bytes → 43 unpadded base64 chars |
| salt | 16 bytes from `SecureRandom`, fresh per call → 22 unpadded base64 chars |
| base64 | standard alphabet (`+` `/`), **no padding** |
| plaintext encoding | UTF-8 bytes, no normalisation, no truncation, no length cap |

Two hashes of the same plaintext differ (fresh salt). The parameters are
`PasswordHash.DEFAULT_PARAMS`; `hashWithParams(plaintext, Params)` exists
for tests / slow machines and emits the same envelope with those params
(`Params` rejects non-positive values). Known-answer vector: the hash
`$argon2id$v=19$m=65536,t=3,p=4$l1LPNz+QzTLQDFDU4ly8RQ$meSaqZSVPnlnJyP8Yi3dwi+pYXrtaE8zVQnijzaD3aM`
verifies `DevPassword123!`.

**[owner?]** 64 MiB × 4 lanes per verify on a virtual-thread login path —
load-bearing parameter choice (matches existing rows, must not change) or a
default nobody tuned? Changing it is safe for existing rows (the envelope is
self-describing; `needsRehash` upgrades on next login) but costs a re-hash
for every user.

## 2. What `verify(plaintext, encoded)` accepts — outcomes

Returns the enum `Verification`: `OK`, `MISMATCH`, `INVALID_HASH`.
`matches(plaintext, encoded)` = `verify(...) == OK`. `plaintext` must be
non-null (NPE); `encoded == null` → `INVALID_HASH`.

| Stored value | Parsed how | Outcome |
|---|---|---|
| `$argon2id$v=19$m=…,t=…,p=…$<salt>$<hash>` | native; params, salt, key length all taken **from the envelope**, never from the defaults | `OK` / `MISMATCH` (constant-time compare) |
| `$argon2i$v=19$…` (Laravel default argon) | same envelope, argon2i function | `OK` / `MISMATCH` |
| `$2a$…`, `$2b$…`, `$2y$…` (bcrypt; Laravel/PHP `password_hash`) | bcrypt with the cost and salt in the string; **plaintext truncated to its first 72 UTF-8 bytes** before comparing, as PHP's `password_verify` does | `OK` / `MISMATCH` |
| bcrypt-prefixed but malformed | — | `INVALID_HASH` |
| anything else: empty string, `argon2d`, version ≠ 19, missing/extra `$` segments (exactly 6 parts with an empty first), params not matching `m=\d+,t=\d+,p=\d+`, bad base64, empty salt or hash, non-positive `m`/`t`/`p`, `p > 255`, numbers that overflow `int` | — | `INVALID_HASH` |

`INVALID_HASH` is deliberately distinct from `MISMATCH`: a stored hash that
cannot be parsed is a data problem, not a wrong password. Callers decide
what to surface (login treats both as a failed login).

**[owner?]** Accepting legacy argon2i and bcrypt is a migration affordance
for users imported from an upstream Laravel system. Still needed, or can the
legacy branches be retired once every row is argon2id? (Both `$2a$` and
`$2b$` are accepted although PHP only emits `$2y$` — intended breadth or
copy-paste?)

**[owner?]** The `p > 255` ceiling on decode is stricter than argon2's
2^24−1 lanes and is not mirrored in `Params` (which accepts any positive
`p`). Load-bearing (a sanity bound against DoS via absurd parameters) or
accident? If it is a DoS guard, `m` and `t` have no ceiling at all.

## 3. `needsRehash(encoded)`

True when the stored value should be re-encoded with `hash` after a
successful verify:

| Stored value | `needsRehash` |
|---|---|
| argon2id with `m`, `t`, `p` and key length equal to the defaults | `false` |
| argon2id with any of those four different (e.g. `m=8192`, 16-byte key) | `true` |
| argon2i | `true` |
| bcrypt (any prefix) | `true` |
| unparseable / `null` | `true` |

Salt length is **not** compared. **[owner?]** Intended (salt length does not
affect strength meaningfully) or an oversight? The Go vector has the default
16-byte salt, so either way it is `false` for existing rows.

The login flow is expected to pair: `verify == OK && needsRehash` → store
`hash(plaintext)`. `PasswordHash` itself never writes anywhere.

## 4. `equalizeTiming(plaintext)`

Performs one argon2id verify of `plaintext` against a fixed, lazily
computed dummy hash (minted once with the default params) and discards the
result. Purpose: the login handler calls it on the "principal not found" /
"no password set" branch so a rejected login costs roughly the same CPU
whether or not the account exists — otherwise the tens-of-milliseconds
argon2id cost only paid when the account exists is a timing oracle for
email enumeration. The plaintext is fed through so the work is
input-dependent and cannot be optimised away. Never throws, returns
nothing. The first call also pays the one-off dummy-hash cost.

## 5. Non-goals / invariants

- No pepper, no per-deployment secret; the hash is fully self-describing.
- Thread-safe, stateless (one shared `SecureRandom`).
- Never logs plaintexts or hashes.
- The exact string format is a storage contract shared with existing rows
  and with the Go binary (rollback must stay possible): do not change the
  variant label, segment order, base64 alphabet or padding.
