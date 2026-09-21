# Spec — function artifacts uploaded through the platform (package G)

Owner ruling **R14, 2026-09-21**: a developer publishes a function by **uploading the artifact to
the platform with their service account**; the platform writes it to whatever store is configured,
and hosts download it from the platform with the credentials they already hold. Nobody but the
platform holds storage credentials. Publish-by-reference (`oci://`, `file://`) stays for a team that
wants its own registry.

Builds on `function-artifacts.md` (store, digest rules, cache), `function-api.md` (publish, control
routes), `function-host-reconciler.md` §1.2 (prepare), `function-developer-surface.md` (`fcdev fn`).

What this is **not**: the function-*host* container image. ECS pulls that from a container
registry; see §6.

## 1. The reference: `platform://`

An uploaded artifact is referred to as **`platform://<functionId>/<hex>`** — `<hex>` the 64-char
sha256 of the bytes (`function-artifacts.md` §1), `<functionId>` the function's id.

- The ref names *what*, never *where*. The backend (§2) is deployment configuration and can be
  changed or migrated without rewriting a single `fn_versions` row.
- It is scoped by function, not global: a content-addressed global namespace would let a tenant
  publish a digest another tenant uploaded, and turn the upload route into an existence oracle.
  The same jar uploaded for two functions is stored twice; that is the price, and it is small.
- `PublishVersion.validateArtifactRef` admits `platform://`. A `platform://` ref must (a) carry
  **this** function's id and the command's own digest — anything else is `422 ARTIFACT_REF_MISMATCH`;
  (b) **exist in the store** — otherwise `422 ARTIFACT_NOT_UPLOADED`. Existence is checked inside
  the operation, before the version row is written. `s3://` stays unsupported as a *reference*
  scheme: S3 is a backend here, not something a developer points at.

## 2. `ArtifactBlobStore` — the platform's write side

Package `io.flowcatalyst.platform.function.artifact`.

```java
public interface ArtifactBlobStore {
    /// Stores `file` (already hashed to `digest` by the caller) under (functionId, digest).
    /// Idempotent: an existing blob is left as is.
    void put(String functionId, Digest digest, Path file) throws ArtifactException;
    boolean exists(String functionId, Digest digest) throws ArtifactException;
    /// The blob's bytes; the caller closes. `NotFound` when absent.
    InputStream open(String functionId, Digest digest) throws ArtifactException;
    long size(String functionId, Digest digest) throws ArtifactException;
    /// Every blob of a function. Best-effort callers only.
    void deleteAll(String functionId) throws ArtifactException;
}
```

Two implementations, chosen by **`FC_FN_ARTIFACT_STORE`**:

| value | store | layout |
|---|---|---|
| `file:///abs/dir` | `FileArtifactBlobStore` | `<dir>/<functionId>/<hex>`; temp file in the same directory then `ATOMIC_MOVE`, so a reader never sees a partial blob |
| `s3://bucket[/prefix]` | `S3ArtifactBlobStore` | key `<prefix>/<functionId>/<hex>`; AWS SDK v2 sync client on the default credential chain (the task role), same `apache5-client` and `netty-nio-client` exclusion as the `sqs` dependency; `If-None-Match: *` is not relied on — `exists` then `put` is fine, the content is identical by construction |
| unset | none | the upload route answers `503 ARTIFACT_STORE_NOT_CONFIGURED`; `platform://` publishes answer the same. Nothing else changes |
| anything else | — | startup fails, naming the variable and the two accepted shapes |

`fcdev start` sets it to `file://<state>/fn-artifacts` when unset, so the dev loop needs nothing.

`functionId` and `hex` are validated (`^[A-Za-z0-9_]+$`, `^[0-9a-f]{64}$`) **in the store**, not
only by callers: they become path segments and object keys.

An OCI/ECR *write* backend is not built: a registry push is an upload-session protocol plus token
auth, for no benefit over S3 when the platform is the only reader. The interface is where it would
go.

Dependency: `software.amazon.awssdk:s3` in `server/pom.xml` (BOM-managed). No other pom change.

## 3. Upload — `PUT /api/functions/{address}/artifacts/{digest}`

`{digest}` is `sha256:<hex>`. Body: the raw bytes (`application/octet-stream`). Group **`API_READ`**, not `API_WRITE`: a write group pins a database connection for the whole request, and this request spends minutes reading a body and one statement looking the function up (`RouteGroupTest` refuses the other declaration, rightly).
Authorised exactly as publish is (`Access` + the publish permission) — if you may publish a version
of this function, you may upload its artifact; nothing more, nothing less.

**The body is streamed, never buffered.** The listener today reads every body into memory capped at
1 MB (`VertxListener.MAX_BODY_BYTES`). This route needs a second dispatch mode:
`Routes.putStreaming(path, handler)` (name at the implementer's discretion) — the request is paused
on the event loop, admission and authentication run as for any route, and the handler, on its
virtual thread, receives the body as an `InputStream` (`Exchange.bodyStream()`), back-pressured:
the socket is read only as fast as the handler consumes. `body()`/`bodyAsBytes()` on a streaming
exchange throw `IllegalStateException`. **Every other route keeps the 1 MB cap, unchanged.**

**Amended in review (2026-09-21) — three things the first cut got wrong:**

1. **The deadline on a streaming exchange is a stall deadline, not a total one.** The listener's
   30 s request deadline would need ~70 Mbit/s for a 256 MiB upload and fails a 10 MB jar on a slow
   link. On a streaming request the loop-owned timer fires only when **no chunk has arrived for a
   full window**; otherwise it re-arms itself for the remainder (one timer, no per-chunk timer
   operations, no timed park, no knob). After end-of-body the handler has one last full window for
   the digest comparison and `put`. The response pump (§4) has the mirror image: no write completed
   for a full window ⇒ the response is reset, which unblocks the pump and closes the store's stream —
   without it a host that stops reading holds a thread, a store handle and an admission slot for ever.
2. **An abandoned body never closes the connection from `close()`.** On HTTP/2 that tears down every
   sibling stream; on HTTP/1 it races the response and can lose the 413/422. And tagging
   `Connection: close` is *not* a fix — measured: with unread request bytes on the socket the
   response was lost entirely. The rule: `close()` only drains and records the abandonment; the
   listener closes the connection **in the completion of the response's own write**, HTTP/1.x only.
   An HTTP/2 connection must survive an abandoned upload on it (pinned by the client's local port
   staying the same for the next request, not merely by a sibling stream completing — a graceful
   GOAWAY lets the sibling finish and would pass that weaker test).
3. **A buffered body replaces a pending streamed one** (`json`/`result` after `resultStream`, the
   exception-mapper case) and closes it; so does the listener's own 500/503 override.

Handler: stream through a `DigestInputStream` into a temp file (`java.io.tmpdir`), abandoning the
transfer as soon as the count passes **`ArtifactStoreSupport`'s `maxBytes` (256 MiB, the same
constant the fetch side uses)**; then, in order:

| condition | answer |
|---|---|
| no store configured | `503 ARTIFACT_STORE_NOT_CONFIGURED` — decided **before** reading the body |
| function not found / not visible | `404` — before reading the body |
| `Content-Length` over the cap | `413 ARTIFACT_TOO_LARGE` — before reading |
| count passes the cap mid-stream | `413 ARTIFACT_TOO_LARGE`, temp file deleted |
| empty body | `422 ARTIFACT_EMPTY` |
| bytes do not hash to `{digest}` | `422 DIGEST_MISMATCH`, temp file deleted, **nothing stored** |
| otherwise | `put`, then `200 {"artifactRef":"platform://…","digest":"sha256:…","bytes":N}` |

Uploading a digest that is already there is a `200` with the same body (the bytes are still read
and hashed — the route never answers for bytes it did not see). The temp file is deleted on every
path, success included.

No domain event and no audit row: an upload changes nothing a caller can observe until a version
is published against it, and *that* is audited. An orphan blob (uploaded, never published) is
garbage, not state; `deleteAll` on function delete collects it.

## 4. Download — `GET /control/functions/artifacts/{versionId}`

Role `platform:function-host`, like the rest of `/control/functions`. `404` for an unknown version
or one whose ref is not `platform://`; `503` when no store is configured. Otherwise `200`,
`application/octet-stream`, `Content-Length` from `size`, `Digest: sha256=<base64>` is **not** sent
— the host already knows the digest from desired state and recomputes it; a header would be a
second source of truth.

**Streamed**: memory must not scale with the artifact. If `Exchange.result(InputStream)` buffers
today, this route gets the response-side equivalent of §3's change.

Keyed by version id, not by (functionId, hex): the host asks for "the artifact of the version you
told me to run", and the platform resolves where that is.

## 5. The host and the CLI

**Host.** `PlatformArtifactStore implements ArtifactStore`, in `function-host`, registered in
`ArtifactStores` for scheme `platform`. `ArtifactStore.fetch(ref, digest)` carries no version id, so
the desired-state entry's `versionId` reaches the store through a small widening: `fetch` gains an
overload `fetch(String ref, Digest expected, String versionId)` defaulting to the two-arg form; the
reconciler calls the three-arg one. The store GETs §4 with the host's `TokenSource` bearer
(refresh-once-on-401, as `HttpControlPlane`), and runs the response through the **same**
`ArtifactStoreSupport` path as `file` and `oci` — temp file, recomputed digest, atomic move into
`<cache>/sha256/<hex>`, the cap. A digest mismatch is `ARTIFACT:DigestMismatch`, as for any store.
Signature verification (reconciler step 2) is unchanged: it never cared where bytes came from.

**CLI.** `fn publish` / `fn deploy` without `--artifact-ref` now **upload** (§3) and publish the
returned ref — against `fcdev start` and against a deployed platform alike. `Publisher.storeLocally`
and `DevPaths.fnArtifactsDir`'s CLI-side use are deleted: one path, no local/remote fork.
`--artifact-ref oci://…` still publishes by reference. `FnClient` gains a streaming `putFile`
(`BodyPublishers.ofFile`); a `413`/`422`/`503` surfaces the platform's code and message.

**Function delete** (R4) calls `deleteAll(functionId)` after the transaction commits, best-effort:
a failure is a WARN, never a failed delete.

## 6. The function-host image → ECR

`.github/workflows/fnhost-image.yml`'s placeholder push becomes: `aws-actions/configure-aws-credentials`
(GitHub OIDC, `role-to-assume: ${{ vars.FNHOST_AWS_ROLE_ARN }}`, `aws-region: ${{ vars.FNHOST_AWS_REGION }}`),
`aws-actions/amazon-ecr-login`, push `${{ vars.FNHOST_ECR_REPOSITORY }}:<git sha>` and `:latest` on
`main`, `:<tag>` on a release tag. The job needs `permissions: id-token: write`. The push steps run
only `if: vars.FNHOST_AWS_ROLE_ARN != ''`, so the workflow stays green until the owner sets the
three variables. Actions pinned by major tag, as the other workflows do. `docs/deployments.md`
gains the three variables and the IAM trust policy the role needs.

## 7. Tests

One mutant per condition; assert absence as well as presence. Own database, port 0.

| # | Behaviour | Mutant |
|---|---|---|
| U1 | upload → publish with the returned ref → `GET` version shows the `platform://` ref; the blob's bytes in the store equal the upload | — |
| U2 | wrong digest ⇒ `422 DIGEST_MISMATCH` **and `exists` is false afterwards** | store before hashing; skip the comparison |
| U3 | a body over the cap ⇒ `413` both ways (declared `Content-Length`; chunked, discovered mid-stream), nothing stored, no temp file left in `tmpdir` (list it before and after) | check only the header; leak the temp file |
| U4 | a **3 MB** upload succeeds while a 3 MB JSON body on an ordinary route is still `413` | raise the global cap instead of adding a streaming mode |
| U5 | caller without publish rights on the function ⇒ `403`/`404` as publish answers, **body unread** (send `Expect`-less 10 MB and assert the answer arrives before the client finishes writing, or assert via a counting stream) — if this cannot be pinned cheaply, say so | authorise after storing |
| U5b | a publisher who holds the permission but cannot **reach** the function ⇒ `404`, body unread | look the function up without the reach check |
| U6 | `platform://` ref for **another function's id**, or a hex ≠ the command digest ⇒ `422 ARTIFACT_REF_MISMATCH`; right function but never uploaded ⇒ `422 ARTIFACT_NOT_UPLOADED`; no version row in either case | drop each check |
| U7 | store unset ⇒ upload, `platform://` publish and download all `503`; an `oci://` publish is unaffected | — |
| U8 | `FC_FN_ARTIFACT_STORE=ftp://x` fails startup naming the variable | default silently |
| U9 | store rejects `functionId` `../x` and a 63-char hex | validate in the route only |
| U10 | download: host role required (`403` for a publisher token); bytes equal the upload; unknown version `404`; an `oci://` version `404` | each |
| U11 | `S3ArtifactBlobStore` against an in-process fake S3 endpoint (a JDK `HttpServer` fake, `FakeS3` — the SDK issues `HEAD`, which `Routes` has no verb for, and adding one for a test is the wrong trade; the client sets `RequestChecksumCalculation.WHEN_REQUIRED`, because the default wraps `PutObject` in `aws-chunked` framing an endpoint that does not advertise support stores verbatim — our own sha256 is the integrity check) — put/exists/open/size/deleteAll and the key layout with and without a prefix | — |
| U12 | host: `PlatformArtifactStore` fetches through the cache path — a tampered response is `DigestMismatch` and leaves nothing under the digest's name; a 401 refreshes the token once | trust the platform's bytes |
| U13 | **end to end, in-process** (extend the reconciler's R12): `fn deploy` with no `--artifact-ref` against a platform on `TestPg` with a `file://` store ⇒ host reconciles ⇒ `LOADED` ⇒ invoke answers | — |
| U14 | function delete removes its blobs; a store that throws on `deleteAll` does not fail the delete | propagate |
| U15 | `fcdev start` with the variable unset uploads and serves (the default is wired — composition root) | drop the default |
