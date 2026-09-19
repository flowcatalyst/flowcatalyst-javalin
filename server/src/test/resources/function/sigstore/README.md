# Golden Sigstore fixture

Source: [`sigstore/sigstore-conformance`](https://github.com/sigstore/sigstore-conformance),
commit [`fcfbfacf07996007de555dbb2af7f8abb77d533e`](https://github.com/sigstore/sigstore-conformance/commit/fcfbfacf07996007de555dbb2af7f8abb77d533e)
(fetched 2026-09-19).

- `happy-path-v0.3.sigstore.json` — copied unmodified from
  `test/assets/bundle-verify/happy-path-v0.3-new-mediaType/bundle.sigstore.json`.
  Media type `application/vnd.dev.sigstore.bundle.v0.3+json` (the "new" media
  type spelling; `happy-path-v0.3` uses the older
  `application/vnd.dev.sigstore.bundle+json;version=0.3` spelling and was not
  used). One `hashedrekord` v0.0.1 `tlogEntries[]` entry carrying both an
  `inclusionPromise` and an `inclusionProof` with a checkpoint, against the
  Sigstore public-good instance (Fulcio `sigstore-intermediate`, Rekor
  `rekor.sigstore.dev`).
- `artifact.txt` — copied unmodified from `test/assets/bundle-verify/a.txt`,
  the artifact the bundle signs. sha256
  `a0cfc71271d6e278e57cd332ff957c3f7043fdda354c4cbb190a30d56efa01bf`.

Signer identity asserted by this bundle (leaf certificate, GitHub Actions
OIDC via Fulcio):

- issuer (SAN OID `1.3.6.1.4.1.57264.1.8`):
  `https://token.actions.githubusercontent.com`
- subject (the single SAN, a URI):
  `https://github.com/sigstore-conformance/extremely-dangerous-public-oidc-beacon/.github/workflows/extremely-dangerous-oidc-beacon.yml@refs/heads/main`

The leaf certificate is short-lived (`2024-03-19T17:26:26Z`–
`2024-03-19T17:36:26Z`) and long expired at read time; verification is
pinned to `integratedTime` (`1710869186` = `2024-03-19T17:26:26Z`), not to
"now" (spec §3.2 step 6).

The matching `trusted_root.json` (the CA generation and tlog key active at
`integratedTime`, from `sigstore/root-signing`) is committed at
`server/src/main/resources/function/sigstore-trusted-root.json` — see its
own `README.md`.
