# Spec — field encryption and keyed hashing (`io.flowcatalyst.platform.shared.encryption`)

Two verify-or-recover primitives, one package. **Reversible** AES-256-GCM
encryption (§2, §4–§7) for a value the platform must later *use* in the
clear — OIDC client secrets, TOTP secrets, webhook signing keys and bearer
tokens, outbound basic-auth passwords: the platform sends or signs with
these, so it must be able to recover the plaintext. **Keyed hashing** (§3.1)
for a value the platform only ever *compares* — a verify-only secret,
structurally a password check, currently OAuth client secrets and
self-service developer client secrets. Owner ruling 2026-09-08: a
verify-only secret is stored as a keyed hash, never reversibly encrypted —
a leaked `FLOWCATALYST_APP_KEY` must not recover every client secret in
plaintext.

Written from the contract of `internal/platform/shared/encryption/`
(`encryption.go`, `secretref.go`, their tests), the `internal/secrets`
reference grammar, and the fcdev / operations call sites — not from the Go
source line by line. Password hashing is **not** this
([`password-hash.md`](password-hash.md)).

Items tagged **[owner?]** are "load-bearing or accident?" questions.
**[C]** = contract shared with existing rows / the Go binary / the TS SDK;
**[I]** = internal mechanics, free to differ.

## 1. Keys **[C]**

| Item | Value | Evidence |
|---|---|---|
| Algorithm | AES-256-GCM, 128-bit tag, **no AAD** | `encryption.go:184-200`, `Seal(nil, nonce, pt, nil)` |
| Key | exactly 32 bytes; supplied as **standard-alphabet, padded base64** (44 chars) | `makeAEAD` rejects ≠ 32 bytes (`encryption_test.go:46` pins 16 bytes rejected) |
| Current key | env `FLOWCATALYST_APP_KEY` | `FromEnv` |
| Previous key | env `FLOWCATALYST_APP_KEY_PREVIOUS`, optional, **at most one** | `FromEnv` wraps it as a one-element list; `WithPreviousKeys` accepts N but nothing calls it with N > 1 |
| Unset / empty current key | encryption **disabled** — no service; callers refuse to *write* plaintext secrets and fail closed on reads | `FromEnv` → `nil, nil`; `token.go:281` `verifyClientSecret` → false; `oidc.go:196` error; `mfa` TOTP disabled |
| Malformed current or previous key | boot error | `wire_services.go:92` returns the error (fatal); `wire_routes.go:214` and `cmd/fcdev` discard it **[owner?]** accident — Java treats a malformed configured key as fatal everywhere (a key that cannot decrypt is worse than no key) |
| Whitespace | Go trims the previous key, not the current one (Go's base64 decoder silently skips `\n`, so a trailing newline still works, a trailing space does not) | `FromEnv`, `decrypt-check/main.go:33` warns about it |
| `GenerateKey()` | 32 random bytes → padded standard base64 | `encryption.go:176` |

**Ruling needed / [owner?]:** Java strips whitespace from **both** variables
before decoding (superset of Go; nobody relies on a space-padded key failing).

The server reads both variables once, through `io.flowcatalyst.server.Env`
(`appKey` / `appKeyPrevious`, no defaults), and the composition root builds
the service with `Encryption.fromKeys(current, previous)`; `fromEnv(EnvReader)`
is the same reading for a caller holding a raw environment. Reading
`System.getenv()` directly would silently disable encryption under fcdev,
whose environment (and generated app key) is a map handed to `Env.load`.

### fcdev `app-key` file **[C]** (must agree with `DevBootstrap.ensureAppKeyFile`)

| Item | Value |
|---|---|
| Location | `<state dir>/app-key` next to `jwt-signing-key.pem` (`DevPaths`, `docs/fcdev.md`) |
| Content | one base64 key as `GenerateKey` emits it: 32 bytes, standard alphabet, padded, 44 chars; no trailing newline is written (a trailing newline on read is tolerated — the file is `strip()`ped) |
| Permissions | file 0600 (parent dir 0755 in Go, 0700 in Java `OwnerOnlyFile`) |
| Read rule | an existing file whose stripped content is non-empty wins verbatim; missing or empty → generate + write |
| Use | only when `FLOWCATALYST_APP_KEY` is unset/empty; the variable is set to the file *content* (not the path); non-fatal on failure (warn) |

`DevBootstrap.ensureAppKeyFile` (fcdev) already generates `SecureRandom` 32
bytes → `Base64.getEncoder()` (padded) — identical bytes to
`Encryption.generateKey()`; the two agree. (It could call
`Encryption.generateKey()` once fcdev depends on this package — not this
unit's file.)

## 2. Envelope byte layouts **[C]**

All envelopes are base64 (standard alphabet, padded) of a byte string. Two
layouts exist in the wild:

**v1 (current; what Go `Encrypt` writes)**

| Offset | Length | Content |
|---|---|---|
| 0 | 1 | version byte `0x01` |
| 1 | 12 | GCM nonce, random per encryption |
| 13 | n | ciphertext |
| 13+n | 16 | GCM tag |

Minimum length 29 bytes (n = 0 is legal: the empty string encrypts).

**v0 (legacy; TS SDK `packages/crypto/src/encryption.ts`, the Quarkus
`EncryptedSecretProvider`)**

| Offset | Length | Content |
|---|---|---|
| 0 | 12 | GCM nonce |
| 12 | n | ciphertext |
| 12+n | 16 | GCM tag |

Minimum length 28 bytes.

Decoders choose the layout by the **first byte**: `0x01` → v1, anything
else → v0 (`encryption.go:118-129`). **[owner?]** A v0 envelope whose random
nonce starts with `0x01` (1 in 256) is misread as v1 by Go and fails to
decrypt. GCM authentication makes a v0 re-try safe (no false positive is
possible), so Java **falls back to the v0 reading when the v1 reading fails
with every key** — a strict superset; keep, or mirror Go exactly?

Go's own minimum-length checks are `≥ 14` (v1) / `≥ 13` (v0) bytes, with GCM
then rejecting anything shorter than a tag; Java reports the real minimum
(29 / 28) as *malformed* rather than *no key matches*. Both are failures;
only the reason differs.

### Golden vector (minted by the Go code; see `EncryptionTest`)

| | value |
|---|---|
| key (base64) | `AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA=` (bytes 0x01..0x20) |
| nonce (hex) | `101112131415161718191a1b` |
| plaintext | `flowcatalyst-golden` |
| ciphertext+tag (hex) | `d487d56fe81a68c42729ecef4badb906e9ef779531b311a169eccf333551362d1e967b` |
| v1 envelope | `ARAREhMUFRYXGBkaG9SH1W/oGmjEJyns70utuQbp73eVMbMRoWnszzM1UTYtHpZ7` |
| v0 envelope | `EBESExQVFhcYGRob1IfVb+gaaMQnKezvS625Bunvd5UxsxGhaezPMzVRNi0elns=` |
| a random Go `Encrypt("hello from go")` | `AQ6UiieEVidUUcho/ijOXwiot0z01NHb3UM5BEPCD1rQ1H706biKn1qN` |

Cross-checked once the other way: envelopes from Java `encrypt` /
`encryptSecretRef` under the same key decrypt with Go `Service.Decrypt`
(`hello from java`, `ref from java`).

## 3. Stored-string grammar (`SecretRef`) **[C]**

A secret column holds one of these shapes (`secrets/provider.go:31-38`,
`secretref.go:15`, TS `SecretRefInput`). Parsing is by **prefix claim**, after
stripping surrounding whitespace:

| Stored string | Kind | Meaning |
|---|---|---|
| `""` (blank) | `None` | no secret (public OIDC client; "clear the secret" on update) |
| `encrypted:<base64 envelope>` | `Encrypted` | inline ciphertext — the canonical at-rest form; what the TS SDK and `EncryptSecretRef` write |
| `hashed:v1:<base64 MAC>` | `Hashed` | keyed-hash MAC (§3.1) — the at-rest form of a **verify-only** secret (OAuth client secrets, self-service developer client secrets); never decryptable, only verified |
| `<base64 envelope>` (no prefix) | *no claim* → `Plain`, **but `decrypt` tries it as an envelope** | rows written by Go `Encrypt` directly (`mfa`, service-account credentials, `fcdev init`, MCP bootstrap) and TS-era `client_secret_ref` rows; developer-credential rows were this shape too until the §3.1 migration started writing `hashed:v1:` |
| `aws-sm://…`, `aws-ps://…`, `gcp-sm://…`, `vault://…`, `env://…` | `External(scheme, ref)` | lives in a secret manager; stored verbatim, resolved at read time by a provider (none is in-process in Java yet) |
| `literal:<value>` | `Literal(value)` | dev bypass: the value *is* the plaintext; stored verbatim |
| `encrypt:<plaintext>` | `Plain(plaintext)` | the SPA's "encrypt on save" directive; the directive is stripped, never stored |
| anything else | `Plain(value)` | plaintext |

Rules:
- The external scheme list is **closed**: an unknown `foo://x` is `Plain`
  and gets encrypted. **[owner?]** intended (fail safe) or should any
  `scheme://` be treated as external?
- `encrypted:` whose payload is not base64 is not a `SecretRef` at all —
  `parse` throws `IllegalArgumentException`; `decrypt` reports it
  *malformed*. Go's `EncryptSecretRef` would store such a value untouched
  **[owner?]** — Java rejects it at the boundary instead.
- `encrypt:` with an empty payload is `Plain("")`: Go encrypts the empty
  string; `""` alone clears. **[owner?]** accident (harmless); preserved.
- Base64 is decoded strictly as Go does: standard alphabet, length a
  multiple of 4, padding required (Java's lenient decoder is not used).
- `hashed:v1:` joins this list as a **closed** prefix claim (2026-09-08
  ruling), exactly like `encrypted:`: its payload must be strict base64 of
  exactly 32 bytes (the `HmacSHA256` output length) or `parse` throws
  `IllegalArgumentException`, same as a malformed `encrypted:` payload.
  **A reader without this change — an older Java binary, or the Go binary
  before its own port lands — has no `Hashed` case: `SecretRef.parse` falls
  through to the last rule and reads `hashed:v1:…` as `Plain`, and the
  comparison then fails** (the "plaintext" is the whole prefixed hash
  string, which never equals the real secret). This is *closed*, not open —
  deliberately: a downlevel reader must fail the comparison, not silently
  treat the hash's bytes as if they might be a legacy bare envelope or an
  unknown scheme. It is why the Go port lands the same grammar change in
  lockstep rather than as a follow-up.

## 3.1. Keyed hashing — verify-only secrets **[C]**

A **verify-only** secret is one the platform only ever *compares* against a
caller-supplied value — never decrypts to send, sign, or display again
after the moment it was minted. `OAuthClient.acceptsSecret` and the
self-service developer client-credentials check are exactly this: a
password check in all but name. Storing them reversibly (AES-GCM under the
app key, like every other secret in this package) means a leaked app key
recovers every one of them in plaintext — the owner ruling above replaces
that with a keyed hash.

**Classification** (every secret written through `Encryption` /
`SecretRef`, verify-only vs. must-decrypt, and why):

| Secret | Kind | Why | File |
|---|---|---|---|
| OAuth client secret (`client_secret_ref`, `previous_secret_ref`) | **verify-only** | `ClientAuthentication.acceptClientSecret` only ever compares it to a caller-supplied `client_secret` | `platform/oauthclient/OAuthClient.java`, `platform/auth/oauth/ClientAuthentication.java` |
| Self-service developer client secret (`dev_client_secret_ref`) | **verify-only** | `OAuthTokenApi.developerCredential` only ever compares it to a caller-supplied `client_secret` | `platform/auth/oauth/OAuthTokenApi.java`, `platform/principal/operations/SetDeveloperCredential.java` |
| OIDC identity-provider client secret (`oidc_client_secret_ref`) | must-decrypt | `OidcClients` decrypts it to authenticate the platform *to* the external IdP during token exchange | `platform/auth/oidc/OidcClients.java` |
| Auth-admin OIDC client secret (`oidc_client_secret_ref`) | must-decrypt | same shape, the admin-configured OIDC provider | `platform/authadmin/ClientAuthConfig.java` |
| TOTP secret (`secret_encrypted`) | must-decrypt | the raw secret is the HMAC *key* the platform uses to compute the expected TOTP code, not a value compared directly | `platform/auth/mfa/Mfa.java` |
| Webhook bearer token (`wh_auth_token_ref`) | must-decrypt | sent as the outbound `Authorization: Bearer …` header when the platform delivers a webhook | `platform/serviceaccount/ServiceAccountRepository.java`, `platform/scheduler/jobs/JobDispatcher.java`, `platform/dispatchjob/processing/SubscriberDelivery.java` |
| Webhook HMAC signing secret (`wh_signing_secret_ref`) | must-decrypt | used as the HMAC key to sign the outbound `X-FlowCatalyst-Signature` header | same as above |

A secret returned to the caller once, at creation (a "show-once" response —
`CreateOAuthClientResponse.clientSecret`, `RegenerateAuthTokenResponse`,
`SetDeveloperCredentialResponse`), is **not** by itself must-decrypt: it is
returned from the plaintext already in hand at mint time, never re-read
from storage, so what matters is only whether the *stored* ref is ever
decrypted again afterward.

**Format** — a new prefix-claimed shape, §3's table:

    hashed:v1:<Base64 standard, padded, of the 32-byte MAC>

    MAC = HMAC-SHA256(key = the same key bytes Encryption uses for AES-GCM
                            (the *current* key), message = UTF-8 bytes of the plaintext secret)

**Golden vector** (pinned so the Go port's identical vector can be checked
byte for byte):

| | value |
|---|---|
| key (base64) | `AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=` (bytes `0x00..0x1f`) |
| plaintext | `client-secret-golden` |
| stored string | `hashed:v1:HhInGB9kwvg6VsfBL0oHER0eslXRAg6GBwoTsRa2D4E=` |

**Verification** (`Encryption.verifySecret(stored, providedPlaintext)`):
tries the current key first, then each previous key `Encryption` holds —
the same order `decrypt` tries — and reports whether the row should be
rewritten to the hashed form (`rehash = true` for any match against a
non-hashed shape, or a hash matched only under a previous key). Comparison
is constant-time (`MessageDigest.isEqual`) throughout, including across the
current/previous key loop. With encryption disabled (no key configured)
hashing behaves exactly as encryption does today: writing a verify-only
secret refuses (`ENCRYPTION_NOT_CONFIGURED` / internal `SECRET`, matching
the existing write-refusal codes), and verifying reports no match — fail
closed, never an unkeyed fallback hash.

**Transparent migration** (no client-visible change, no forced rotation, no
downtime step, no env flag): a new or rotated verify-only secret is always
stored `hashed:v1:…`. The read path accepts both shapes — a `hashed:` ref
verifies by MAC, any other shape keeps the decrypt-and-compare path. On a
successful verify against a **non-hashed** ref (the legacy `encrypted:` /
bare-envelope shape, or a hash still sealed under a previous key), the
matched row's ref — current or previous, whichever matched — is rewritten
to the hashed form in the same request, through the repository, not raw
SQL (`OAuthClientRepository.rewriteSecretRef` /
`#rewritePreviousSecretRef`, `PrincipalRepository.rewriteDevClientSecretRef`);
the secret's value and its rotation grace are untouched, only the stored
ref's shape changes. The rewrite is best-effort: its failure is logged and
never fails the authentication that already succeeded.

**Rotation**: unlike the AES-GCM batch re-encryption job (§6), a hashed ref
never appears in `needsReEncryption` / `reEncrypt` — `false` / empty for
every `hashed:v1:` value, because there is nothing that job knows how to
re-seal (a MAC is not decryptable, and the job's contract is
decrypt-then-encrypt). A hashed ref migrates off an old key exclusively
through the lazy rewrite above, the next time its secret is presented.

## 4. `decrypt(stored)` — outcomes **[C] semantics, [I] shape**

Returns the sealed `Decryption`, never throws for bad data:

| Input (after parse) | Outcome |
|---|---|
| `Encrypted` v1/v0 envelope, current key opens it | `Plaintext(value)` |
| … only the previous key opens it | `Plaintext(value)` (and `needsReEncryption` is `true`) |
| … neither key opens it | `Failed(NO_MATCHING_KEY)` |
| … envelope shorter than 29 (v1) / 28 (v0) bytes, or `encrypted:` + non-base64 | `Failed(MALFORMED)` |
| `Plain` that is strict base64 of ≥ 28 bytes | treated as a bare envelope → as above |
| `Plain` that is not base64 / too short | `Failed(NOT_ENCRYPTED)` |
| `None` | `Failed(EMPTY)` (Go: "empty ciphertext") |
| `External` | `External(ref)` — the caller resolves it elsewhere (Go `Decrypt` errors "invalid base64"; the intent is plainly "not inline") |
| `Literal` | `Plaintext(value)` **[owner?]** Go's `Decrypt` rejects it (only `secrets.Service.Resolve` honours `literal:`); Java honours it because that is what the shape means. Keep, or make it `Failed(NOT_ENCRYPTED)`? |
| `Hashed` | `Failed(HASHED)` — one-way by design; there is no plaintext to recover. Verify it with `verifySecret` (§3.1) instead |

Key order: current first, then previous. Plaintext is UTF-8. Whitespace
around the stored value is stripped (Go: a leading space fails, a trailing
`\n` works — accident of Go's base64 decoder).

## 5. `encrypt(plaintext)` and `encryptSecretRef(incoming)` **[C]**

- `encrypt(plaintext)` → a **bare** v1 envelope (no prefix), fresh random
  nonce; two calls on the same input differ. It encrypts *whatever string it
  is given*: `encrypt(encrypt(x))` is a double-wrapped blob (decrypts once to
  the inner envelope). Callers storing raw envelopes (TOTP) use this.
- `encryptSecretRef(incoming)` is the at-rest form of a value arriving from
  the API / SPA (Go `EncryptSecretRef` with a service):

| Incoming | Result |
|---|---|
| blank | returned unchanged (clears) |
| `encrypted:…` | unchanged (idempotent; canonical re-encoding of the same bytes) |
| external ref | unchanged |
| `literal:…` | unchanged |
| `encrypt:<pt>` / bare `<pt>` | `encrypted:` + `encrypt(pt)` |

So `encryptSecretRef(encryptSecretRef(x)) == encryptSecretRef(x)`, but a
**bare** envelope fed back in is re-encrypted (it is indistinguishable from a
plaintext that happens to be base64 — and a user-supplied base64-looking
secret must never be stored unencrypted). Only the `encrypted:` prefix is
an "already encrypted" claim.

- With **no service** (key unset) a caller has only `SecretRef.parse`: every
  kind but `Plain` passes through as `stored()`; `Plain` must be rejected as
  `ENCRYPTION_NOT_CONFIGURED` (validation error) — never stored raw
  (`secretref.go:46`, `secretref_test.go:81`).

## 6. Key rotation **[C]**

| Operation | Behaviour |
|---|---|
| `encrypt` | always the current key, always v1 |
| `decrypt` | current, then previous |
| `needsReEncryption(stored)` | `true` iff the value is an inline envelope (prefixed or bare) that is **not** (v1 ∧ current key opens it): v0, previous-key, or unreadable envelopes; `false` for blank / external / literal / non-base64 plain (nothing inline to migrate). The prefix is the claim: `encrypted:` + a payload shorter than 28 bytes is `true` (claims to be an envelope, cannot be read — `reEncrypt` then leaves it), `encrypted:` + non-base64 is not a secret ref at all and is `false`; a bare base64 string shorter than 28 bytes is plaintext, `false`. Go also says `true` for short base64 junk and `false` for anything undecodable **[owner?]** — unreadable envelopes are flagged in both |
| `reEncrypt(stored)` | decrypt with any key, encrypt with current; `Optional.empty()` when the value is not a decryptable inline envelope (blank, external, literal, no key matches, malformed) — the migration job leaves those rows alone. The output keeps the input's shape: `encrypted:`-prefixed in → prefixed out, bare in → bare out **[owner?]** Go emits bare always (`ReEncrypt` = `Decrypt` ∘ `Encrypt`), silently dropping the prefix; Java preserves the column's convention |

Rotation procedure: set `FLOWCATALYST_APP_KEY` = new, `…_PREVIOUS` = old,
run the re-encryption job (`needsReEncryption` → `reEncrypt`), unset
`…_PREVIOUS`.

**`hashed:v1:` refs never enter this table** — `needsReEncryption` is
`false` and `reEncrypt` is empty for every hashed value, always (§3.1): the
batch job only knows how to decrypt-then-encrypt, and a MAC is not
decryptable. A hashed ref sealed under the previous key migrates lazily,
the next time its secret is presented and verified — see §3.1's
"Transparent migration".

## 7. Error cases (summary)

| Condition | Java |
|---|---|
| key not 32 bytes / not base64 | `IllegalArgumentException` at construction (fatal at boot) |
| `FLOWCATALYST_APP_KEY` unset | `fromEnv` → `Optional.empty()` — disabled, not an error |
| stored value undecryptable | `Decryption.Failed(reason)` — outcome, not exception |
| `encrypted:` + non-base64 | `SecretRef.parse` → `IllegalArgumentException`; `decrypt` → `Failed(MALFORMED)` |
| `null` input | `NullPointerException` (callers map `NULL`/omitted fields before calling) |

## 8. Non-goals / invariants

- No AAD, no key id in the envelope; key selection is by trial (GCM tag).
- Thread-safe, stateless apart from one `SecureRandom`; `Cipher` per call.
- Never logs keys, plaintexts or envelopes; `toString` of every carrier that
  holds one (`Decryption.Plaintext`, `SecretRef.Plain` / `Literal` /
  `Encrypted` / `Hashed`, `KeyRotation.Single` / `Rotating`) is masked.
- Random 96-bit nonces: the usual GCM bound (≈ 2^32 encryptions per key
  before nonce collision becomes a concern) applies; rotate keys long before.
- The envelope layouts, the base64 alphabet/padding and the `encrypted:` /
  `hashed:v1:` / external-scheme prefixes are a storage contract shared with
  existing rows, the Go binary (rollback) and the TS SDK — do not change them.
- A `hashed:v1:` MAC is deliberately one-way: no code path recovers the
  plaintext from it, ever. This is the entire point of §3.1 — do not add one.
