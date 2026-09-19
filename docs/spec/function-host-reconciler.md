# Spec — the host's reconciler: desired state → fetch → verify → load → heartbeat (package D, slice D2)

Design: `docs/function-runner-plan.md` §4 ("Reconciles"). Workplan §2 D item 2. Builds on
`function-host-core.md` (D1: loader, registry), `function-artifacts.md` (C: store, verifier),
`function-api.md` §6 (B: the two control routes). No listener, no invoke path (D3), no
`FunctionContext` services (D4), no `main` (D5): this slice is a library with a loop, tested against
a fake control plane and once against the real platform in-process.

## 0. One change on the platform side

Desired-state entries gain **`signer: {issuer, subject}`** (absent when the version was published
with signatures off). The host re-verifies the bundle (design §3: "hosts re-verify on load, defence
in depth") and requires the identity it extracts to **equal the one the platform recorded at
publish**. The alternative — shipping the owner's whole signer policy to every host — gives hosts
more than they need and a second place for `permits` to be evaluated differently. What this defends:
an artifact or bundle swapped in the registry after publish. What it does not: a compromised platform
database, which could rewrite both; nothing on a host can.

`DesiredState`'s document, its test for byte-determinism and the parity scenario are updated; the
field is omitted, not null, when absent (`NON_ABSENT`).

## 1. Package `io.flowcatalyst.fnhost.reconcile`

### 1.1 `ControlPlane`

```java
public interface ControlPlane {
    Fetched desiredState(DnsLabel pool, String knownEtag) throws ControlPlaneException;
    void heartbeat(HeartbeatReport report) throws ControlPlaneException;
    sealed interface Fetched { record NotModified() …; record Changed(String etag, DesiredDocument document) …; }
}
```

`DesiredDocument` is the host's own record tree, parsed with `Json.MAPPER` from the wire shape of
`function-api.md` §6.1; the manifest through `Manifest.readStored` (lenient — a newer platform may
add keys). An entry the host cannot read (bad address, bad digest, unknown `role`) is **dropped with
a WARN and reported `FAILED`** if it has an address and version, and never takes the rest of the
document down with it. Unknown top-level keys are ignored.

`HttpControlPlane` (`java.net.http.HttpClient`, connect 5 s, request 30 s): bearer token from
`TokenSource` — client-credentials against `<platform>/oauth/token`, cached until 60 s before
expiry, **refreshed once on a 401** and the request retried once; a second 401 is
`ControlPlaneException(UNAUTHORIZED)`. `If-None-Match` sent when an etag is known; 304 ⇒
`NotModified`. Non-2xx/304, I/O and timeouts ⇒ `ControlPlaneException(UNAVAILABLE)`. The client
secret and the token never appear in a log field, an exception message or a `toString`.

### 1.2 `Reconciler`

State it owns: the last etag and document; `prepared` — `versionId → Prepared(Path artifact)` for
every version fetched and verified; `failures` — `(address, version) → error`. The `FunctionRegistry`
(D1) holds what is loaded.

`reconcileOnce(Instant now)`:

1. `desiredState(pool, etag)`. `ControlPlaneException` ⇒ keep serving what is loaded, log WARN, and
   **still send a heartbeat attempt** (it will likely fail too; that is fine) — a platform outage must
   never unload a function. `NotModified` ⇒ skip to step 5 with the previous document.
2. **Prepare** every entry (live and candidate) not yet in `prepared`: `ArtifactStore.fetch(ref,
   digest)`; then signatures — `Signatures.Required`: bundle present, `verify(bundle, digest)` is
   `Verified`, and its signer **equals** the entry's `signer` (§0); an entry with no bundle or no
   recorded signer is a failure under `Required`. `Signatures.Off`: fetch and digest only. Any failure
   ⇒ `failures` gets a one-line reason (`ARTIFACT:DigestMismatch`, `SIGNATURE:BAD_SIGNATURE`,
   `SIGNER_MISMATCH`, `UNSIGNED`), the entry is retried on the next cycle. Entries are prepared
   concurrently with a `StructuredTaskScope`, at most 4 at a time; one entry's failure does not cancel
   the others.
3. **Load.** For each `live` entry that is prepared: `mode: warm` ⇒ ensure that version is the one
   in the registry (`JvmFunctionLoader.load` → `Loaded` ⇒ `registry.put(…, warm=true)`, closing the
   displaced version after its in-flight calls drain; `Refused` ⇒ `failures`). `mode: lazy` ⇒ record
   the entry in `lazyRoutes` (`address → entry`); if a *different* version of that address is
   currently loaded, replace it now (a lazy function already in memory must not keep serving the old
   version until it happens to idle out); otherwise leave loading to first invocation —
   `LoadedFunction ensureLoaded(FunctionAddress)` is what D3 calls, and it loads from `prepared`
   under a per-address lock so two first invocations load once. A `candidate` is never loaded.
   `runtime: wasm` ⇒ failure `RUNTIME_UNSUPPORTED` (phase 3).
4. **Unload.** Everything in the document's `unload`, and everything loaded or in `lazyRoutes` whose
   address is no longer a `live` entry ⇒ close and remove; their `prepared` artifacts are dropped from
   the map (the store's cache is left to the OS — it is content-addressed and harmless). Lazy entries
   idle longer than `IDLE_UNLOAD` (1 h, a constant, not a knob) are closed but keep their route.
5. **Heartbeat**: for every entry of the document — loaded ⇒ `LOADED`; prepared (lazy live, or
   candidate) ⇒ `REGISTERED`; in `failures` ⇒ `FAILED` + error. Host state `ACTIVE`, or `DRAINING`
   once `drain()` was called. A failed heartbeat is a WARN, never an exception out of the loop.

The order matters and is pinned: **new before old** (step 3 before 4), so an address is never without
a version during a promote; and a version that fails to prepare or load **leaves the old one
serving** — the registry is only swapped on `Loaded`.

### 1.3 `ReconcileLoop`

One virtual thread. `start()`; `trigger()` (D3 wires the platform's `version:published` /
`alias:changed` deliveries to it) — coalescing: any number of triggers during a run cause exactly one
more run; `drain()`; `close()` stops the loop and joins (interruption is the stop signal,
CONVENTIONS §5). Interval 15 s between the end of one run and the start of the next. A run that
throws anything unexpected is logged and the loop continues; `Error` is not caught.

### 1.4 `HostEnv`

A record read through the server's `EnvReader`: `FC_FN_POOL` (default `default`, a `DnsLabel`),
`FC_FN_PLATFORM_URL`, `FC_FN_CLIENT_ID`, `FC_FN_CLIENT_SECRET` (required; masked `toString`),
`FC_FN_HOST_ID` (default `<hostname>-<6 random base32>`, validated against the heartbeat's host-id
rule), `FC_FN_SIGNATURES` + `FLOWCATALYST_DEV_MODE` (the same `Signatures.resolve` as the platform —
one rule, one place), `FC_FN_MAX_LOADED` (default 200), `FC_FN_CACHE_DIR` (default
`${java.io.tmpdir}/fc-fn-cache`). Missing required values fail with one message naming all of them.

## 2. Build change (authorised for this slice)

`server/pom.xml` gains a `maven-jar-plugin` `test-jar` execution, and `function-host` depends on it
with `<type>test-jar</type>`, `test` scope — the host's tests need `TestSigstore`, `TestPg` and
`TestHttp`. Nothing else in any pom.

## 3. Tests

Against a scripted `FakeControlPlane`, `FileArtifactStore`, D1's `FixtureJars` and C's `TestSigstore`.
Time is a parameter; nothing sleeps except the loop tests, which use latches.

| # | Behaviour | Mutant |
|---|---|---|
| R1 | warm live ⇒ loaded and `LOADED`; lazy live ⇒ **not** loaded, `REGISTERED`, and `ensureLoaded` loads it once under two concurrent callers; candidate ⇒ never loaded, `REGISTERED` | load lazies eagerly; no per-address lock; load candidates |
| R2 | promote (live v1→v2, both prepared): v2 is serving before v1 is closed — a function call started on v1 before the swap completes on v1, a call after gets v2, and at no instant does `registry.get(address)` return nothing | unload before load |
| R3 | v2 fails to prepare (each of: digest mismatch, bad signature, signer ≠ recorded, no bundle under `Required`) or is `Refused` ⇒ v1 keeps serving, v2 is `FAILED` with the reason, and the next cycle retries (fix the fixture ⇒ it loads) | swap before checking the outcome; cache the failure for ever |
| R4 | signer equality is exact and is checked: a bundle validly signed by **another** identity in the same trust root is `SIGNER_MISMATCH` | skip the comparison; compare issuer only |
| R5 | `Signatures.Off` loads an unsigned entry; `Required` does not | — |
| R6 | control plane down ⇒ nothing unloaded, loop survives, next success resumes; `NotModified` ⇒ no refetch, no reload, heartbeat still sent | treat an exception as an empty document |
| R7 | unload list and "no longer live" both close and remove; a lazy function already loaded at v1 is replaced when live becomes v2; idle lazy entries close after 1 h and reload on demand | each clause |
| R8 | one unreadable entry is dropped and reported; the rest of the document is applied | fail the whole parse |
| R9 | token: cached across calls; refreshed once on 401 then retried; second 401 surfaces; the secret and token appear in no log line (capture the logger) and no exception message | refresh on every call; log the token at debug |
| R10 | loop: N triggers during a run ⇒ exactly one more run; `close()` interrupts a run blocked in the control plane and joins within 5 s; an exception in a run does not end the loop | run once per trigger; swallow the interrupt |
| R11 | wasm entry ⇒ `RUNTIME_UNSUPPORTED`, others unaffected | — |
| R12 | **in-process end to end** (platform `Server` on `TestPg`, signatures `Required` over a `TestSigstore` root on both sides, a `function-host` service principal): create → policy → publish (signed) → host reconciles ⇒ heartbeat ⇒ version `READY` ⇒ promote ⇒ next reconcile ⇒ `LOADED` (warm) and `GET …/status` shows this host with `LOADED`; retire-after-promote-v2 ⇒ v1 unloaded within one reconcile | — (integration pin) |
| R13 | platform: desired state carries `signer` when recorded and omits it when not; bytes still deterministic | — |

One mutant per condition; assert absence as well as presence.
