# Spec — function artifacts: the store and the signature (work package C)

Design: `docs/function-runner-plan.md` §2, §3, §10.1, §10.5. Build order:
`docs/function-runner-workplan.md` §2 C. Registry types it builds on: `docs/spec/function-registry.md`
(`Digest`, `SignerIdentity`, `ClientPolicy.permits`, `Runtime`).

Owner rulings 2026-09-19: **verification uses the JDK only** — no `sigstore-java` (measured: 39
jars, 35 MB — a shaded gRPC-Netty, BouncyCastle, Guava, protobuf, Google's HTTP and OAuth clients);
**signer matching is exact** (`ClientPolicy.permits`, already so). This package adds **no
dependency** to any pom.

Package `io.flowcatalyst.platform.function.artifact` (module `server`; the host module will import
it). Nothing here touches the database, `Platform.java` or an HTTP route.

## 1. What "the digest" is

A function version's `Digest` is the **sha256 of the artifact file's bytes** — the jar, or the wasm
module. It is therefore also the OCI *blob* digest of the layer `oras push` uploads, which is why
the store can fetch by it directly (§2.2). It is **not** the OCI manifest digest. The pipeline signs
the same bytes: `cosign sign-blob --new-bundle-format --bundle fn.sigstore.json fn.jar`.

## 2. `ArtifactStore`

```java
public interface ArtifactStore {
    Fetched fetch(String artifactRef, Digest expected) throws ArtifactException;
    record Fetched(Path file, long bytes) {}
}
```

- The result is a regular file under the store's cache directory, named by the digest
  (`<cache>/sha256/<hex>`); a second fetch of a digest already cached **re-hashes the cached file**
  and returns it without touching the source — and re-downloads if the hash no longer matches. A
  cache is a convenience; it is never trusted.
- **The digest is always recomputed from the bytes received** (streamed through a
  `DigestInputStream` into a temp file in the cache directory, then `ATOMIC_MOVE`d into place). A
  mismatch deletes the temp file and throws — nothing with the wrong bytes ever exists under a
  digest's name, even briefly.
- `maxBytes` (constructor argument, default 256 MiB): the transfer is abandoned as soon as the
  count passes it. A `Content-Length` over the cap is refused before reading.
- `ArtifactStores.forRef(ref)` routes by scheme; an unknown scheme is `UNSUPPORTED_SCHEME`.

`ArtifactException` is a checked exception with a sealed `Reason`: `NotFound | DigestMismatch(expected,
actual) | TooLarge(limit) | UnsupportedScheme(scheme) | Unauthorized | Transport(cause) | BadRef(why)`.
These are infrastructure outcomes the caller maps (publish → a validation or 502-class error; host →
a load error in the heartbeat), so they are one exception with a reason, not a family.

### 2.1 `file://`

`file:///abs/path/fn.jar`. Relative paths, a host component, and a path that is not a regular
readable file are `BadRef` / `NotFound`. Copies into the cache like any other store (the source may
change under us; the cached copy is what was hashed).

### 2.2 `oci://`

`oci://<registry>/<repository>` — e.g. `oci://ghcr.io/acme/functions/billing-invoices-create`. A
tag or `@digest` suffix is `BadRef`: the blob is addressed by the version's `Digest`, nothing else.

`GET https://<registry>/v2/<repository>/blobs/<digest>` with `java.net.http.HttpClient` (HTTP/1.1 or
2, redirects followed — registries redirect blobs to object storage; **the `Authorization` header is
not forwarded across a redirect to another host**), connect timeout 10 s, request timeout 120 s.

Auth, in order: anonymous; on `401` with `WWW-Authenticate: Bearer realm=…,service=…,scope=…` do the
token dance (`GET realm?service=…&scope=repository:<repo>:pull`, with HTTP Basic when the store has
credentials for that registry) and retry once with the bearer token; on `401` with a `Basic`
challenge retry once with Basic. A second `401`/`403` is `Unauthorized`. `404` is `NotFound`. Any
other non-2xx, an I/O error or a timeout is `Transport`.

`RegistryCredentials` is an interface (`Optional<BasicAuth> forRegistry(String host)`; `BasicAuth`
masks `toString`). This package ships `RegistryCredentials.none()` and `.fixed(Map)`. ECR needs
`ecr:GetAuthorizationToken`, which needs the `ecr` SDK module — that is a pom change and lands with
the host (package D), behind this interface. `http://` is used only when the registry host is
`localhost`/`127.0.0.1` (tests, fcdev).

### 2.3 `s3://`

Not in this package: there is no S3 SDK module on the classpath and the design calls it a fallback.
`s3://` is `UnsupportedScheme` until someone needs it; the interface is what makes that cheap later.

## 3. `SignatureVerifier`

```java
public final class SignatureVerifier {
    public SignatureVerifier(TrustRoot trustRoot) { … }
    public Verification verify(String bundleJson, Digest digest);
}
public sealed interface Verification {
    record Verified(SignerIdentity signer, Instant signedAt) implements Verification {}
    record Rejected(Reason reason, String detail) implements Verification {}
}
```

A sealed outcome, not an exception (CONVENTIONS §8, "outcomes at verification boundaries"): a bad
signature is routine. `verify` never throws for any input string, including `null` and non-JSON.
The verifier establishes **who signed these bytes and when**; whether that signer may publish is
`ClientPolicy.permits(signer, runtime)`, the caller's next line. It needs the digest only — the
platform verifies at publish without ever holding the artifact.

`Reason`: `MALFORMED_BUNDLE`, `UNSUPPORTED_BUNDLE`, `DIGEST_MISMATCH`, `BAD_SIGNATURE`,
`UNTRUSTED_CERTIFICATE`, `CERTIFICATE_NOT_VALID_AT_SIGNING`, `NOT_A_CODE_SIGNING_CERTIFICATE`,
`IDENTITY_MISSING`, `TLOG_MISSING`, `TLOG_UNKNOWN_LOG`, `TLOG_ENTRY_MISMATCH`, `TLOG_PROMISE_INVALID`,
`TLOG_INCLUSION_INVALID`, `TLOG_CHECKPOINT_INVALID`. Every check below names the reason it fails
with. **There is no configuration that skips a check** — fail closed (workplan C). "Signatures off"
in fcdev means the caller does not call the verifier, not that the verifier has a lenient mode.

### 3.1 Accepted input

A Sigstore bundle, media type `application/vnd.dev.sigstore.bundle.v0.3+json`, with
`verificationMaterial.certificate.rawBytes` (one leaf, base64 DER), a `messageSignature`
(`messageDigest.algorithm = SHA2_256`), and exactly one `tlogEntries[]` entry of kind `hashedrekord`
version `0.0.1` carrying **both** an `inclusionPromise` and an `inclusionProof` with a checkpoint.
Any other media type, a DSSE envelope, a certificate chain in place of the leaf, a public-key hint,
zero or several tlog entries, or another entry kind/version ⇒ `UNSUPPORTED_BUNDLE`. Missing or
wrongly typed fields, bad base64 ⇒ `MALFORMED_BUNDLE`. Parsed as a Jackson tree from `Json.MAPPER`.

### 3.2 The checks, in order

1. **Digest.** `messageDigest.digest` equals the expected digest's 32 bytes ⇒ else `DIGEST_MISMATCH`.
2. **Log entry belongs to a pinned log.** `logId.keyId` equals the sha256 of the DER
   SubjectPublicKeyInfo of a `TrustRoot` transparency-log key whose validity window contains
   `integratedTime` ⇒ else `TLOG_UNKNOWN_LOG`.
3. **The entry is about this signature.** `canonicalizedBody` (base64 JSON) has
   `spec.data.hash.algorithm = sha256`, `spec.data.hash.value` = the digest hex,
   `spec.signature.content` = the bundle's signature, `spec.signature.publicKey.content` = base64 of
   the PEM of the bundle's leaf certificate (compare the decoded DER, not PEM text) ⇒ else
   `TLOG_ENTRY_MISMATCH`. Without this a valid log entry for someone else's artifact would do.
4. **Signed entry timestamp.** The log key's signature (`SHA256withECDSA`) over the RFC 8785
   canonical JSON `{"body":"<canonicalizedBody b64>","integratedTime":<n>,"logID":"<keyId hex>",
   "logIndex":<n>}` ⇒ else `TLOG_PROMISE_INVALID`. (Keys already sorted, values are base64/hex/ints,
   so the canonical form is built by hand; the field is `logIndex` of the entry, not of the proof.)
   **This is what authenticates `integratedTime`**, which step 6 depends on.
5. **Inclusion.** Leaf hash `SHA256(0x00 ‖ body bytes)`; RFC 6962 audit path with
   `inclusionProof.logIndex`, `treeSize`, `hashes` must produce `rootHash` ⇒ else
   `TLOG_INCLUSION_INVALID`. The checkpoint envelope is a signed note: body = everything up to and
   including the first blank line's preceding newline (`origin\nsize\nbase64(root)\n[…]`), then
   signature lines `— <name> <base64(4-byte key hint ‖ signature)>`; one signature must verify under
   the same log key, and the note's size and root must equal the proof's ⇒ else
   `TLOG_CHECKPOINT_INVALID`.
6. **Certificate.** PKIX (`CertPathValidator`, revocation off — Fulcio certificates live ten minutes
   and are never revoked) from the leaf to a `TrustRoot` certificate authority whose window contains
   `integratedTime`, **validated as of `integratedTime`**, not now ⇒ else `UNTRUSTED_CERTIFICATE`, or
   `CERTIFICATE_NOT_VALID_AT_SIGNING` when the chain is fine but the time is outside the leaf's
   validity. Extended key usage must include code signing (`1.3.6.1.5.5.7.3.3`) ⇒ else
   `NOT_A_CODE_SIGNING_CERTIFICATE`.
7. **Signature.** `NONEwithECDSA` over the 32 digest bytes with the leaf's key (P-256; any other key
   type ⇒ `UNSUPPORTED_BUNDLE`) ⇒ else `BAD_SIGNATURE`.
8. **Identity.** Issuer: extension `1.3.6.1.4.1.57264.1.8` (DER UTF8String), falling back to the
   deprecated `1.3.6.1.4.1.57264.1.1` (raw bytes). Subject: the single SubjectAlternativeName, a URI
   (type 6) or an rfc822 name (type 1). Either absent, or more than one SAN ⇒ `IDENTITY_MISSING`.
   No trimming, no case folding — `permits` is exact and must see what the certificate says.

`Verified(signer, signedAt = integratedTime)`.

### 3.3 `TrustRoot`

`record TrustRoot(List<CertificateAuthority> cas, List<TransparencyLog> tlogs)`, each with a validity
window (`start`, nullable `end`). `TrustRoot.parse(String trustedRootJson)` reads Sigstore's
`trusted_root.json` (`certificateAuthorities[].certChain`, `tlogs[].publicKey`); `ctlogs` and
`timestampAuthorities` are read past. `TrustRoot.sigstorePublicGood()` loads the copy committed at
`server/src/main/resources/function/sigstore-trusted-root.json`, whose header comment in the
adjacent `README.md` records the upstream commit it was taken from (`sigstore/root-signing`) and the
date. **Refreshing it is a manual, reviewed change** — that is the cost of not running a TUF client,
accepted with the JDK-only ruling. The trust root is a constructor argument so tests bring their own.

## 4. What this does not verify — written down, not hidden

- **No SCT check.** Sigstore clients also verify the certificate's embedded Signed Certificate
  Timestamp against the CT log. That needs the precertificate TBS rebuilt by DER surgery, which the
  JDK has no API for. The residual risk is a compromised Fulcio issuing an unlogged certificate;
  the signing event itself is still publicly logged in Rekor (steps 2–5). Recorded as a deviation.
- **Rekor v1 only.** Step 4 needs the signed entry timestamp. Rekor v2 drops it (and
  `integratedTime`) in favour of RFC 3161 timestamps, which are CMS `SignedData` — again no public
  JDK API. If the public-good instance or a newer cosign stops producing v1 entries, this verifier
  rejects (`UNSUPPORTED_BUNDLE`) rather than degrading, and the choice is then hand-parsing CMS or
  adding `bcpkix` (`bcprov` is already on the classpath through another dependency — unused here).
  **The pipeline must pin its cosign version and flags**; package E's workflow example owns that.
  This is the single biggest risk of the JDK-only route and the owner should hear it again when E
  is written.
- **No online lookups.** Nothing queries Rekor or Fulcio; everything is in the bundle.

## 5. Tests

`TestSigstore` (test source): a self-contained miniature of the ecosystem, JDK only — a P-256 root
and leaf built with `keytool` invoked through `java.util.spi.ToolProvider` or `ProcessBuilder` on
`$JAVA_HOME/bin/keytool` (custom-OID extensions via `-ext <oid>=<hex>`, `SAN=uri:…`,
`EKU=codeSigning`), a log key, and builders that sign a digest and produce a correct v0.3 bundle,
a `TrustRoot`, and each broken variant. If keytool cannot express an extension the spec needs, stop
and report — do not hand-roll a DER encoder without asking.

**Golden fixture — the proof that our reading of the format is the real one.** A bundle produced by
real cosign against the public-good instance, with its artifact and the matching
`trusted_root.json`: take them from the `sigstore/sigstore-conformance` repository's test assets (a
v0.3 `hashedrekord` bundle with promise and proof), pinned by commit hash, committed under
`server/src/test/resources/function/sigstore/` with a `README.md` naming the source. It must
`Verified` with the issuer and subject written in the test. If no such asset verifies, **report
which check fails and why before changing the verifier** — the fixture is the authority, but only
after the orchestrator has looked.

Load-bearing behaviours, each a named test, each mutation-checked (break it, watch that test fail,
restore; report mutant → test):

| # | Behaviour | Mutant |
|---|---|---|
| C1 | fetched bytes are hashed; a wrong-digest source leaves **no file** under the digest's name and no temp file | skip the comparison; move before comparing |
| C2 | a poisoned cache entry is detected and replaced | return the cached file without re-hashing |
| C3 | `maxBytes` stops the transfer (a source that would stream for ever terminates) | check the size only after the download |
| C4 | bearer token dance against a local `HttpServer`: 401+challenge → token → 200; credentials are **not** sent to a redirect target on another host/port | forward `Authorization` on redirect |
| C5 | each `Reason` of §3.2 is produced by exactly the broken variant built for it, and the golden and the `TestSigstore` bundle verify | remove each check in turn — eight mutants, one per step |
| C6 | the certificate is validated at `integratedTime`: a bundle whose leaf expired long ago verifies; one whose `integratedTime` is outside the leaf's window is `CERTIFICATE_NOT_VALID_AT_SIGNING` | validate at `Instant.now()` |
| C7 | step 3: a genuine log entry for a *different* digest, spliced into an otherwise valid bundle, is `TLOG_ENTRY_MISMATCH` | compare only the signature |
| C8 | `verify` never throws: `null`, `""`, `[]`, truncated JSON, a 10 MB string of `{` | let a parse exception escape |
| C9 | identity is reported verbatim (a subject with a trailing `/` stays so) | trim or normalise |

## 6. Open

- **ECR credentials** land with the host (package D) — needs the `ecr` SDK module.
- **The golden fixture is a third party's artifact.** Once package E's example workflow exists, replace
  or supplement it with a bundle from *our* pipeline identity, so the subject string the policy will
  really hold is the one under test.
