# `sigstore-trusted-root.json`

Copied unmodified from
[`sigstore/root-signing`](https://github.com/sigstore/root-signing)
`targets/trusted_root.json`, commit
[`c9bda74ad2221f938f7d2e0295ca3aad2da710a8`](https://github.com/sigstore/root-signing/commit/c9bda74ad2221f938f7d2e0295ca3aad2da710a8)
(2025-09-22), fetched 2026-09-19.

This is the Sigstore **public-good** instance's trust root: the Fulcio
certificate-authority chains and Rekor transparency-log keys, each with a
validity window, as `docs/spec/function-artifacts.md` §3.3 describes.
`TrustRoot.sigstorePublicGood()` parses this file.

**Refreshing this file is a manual, reviewed change** (spec §3.3) — there is
no TUF client in this package. Refresh by re-fetching
`targets/trusted_root.json` from the current `sigstore/root-signing` `main`
and updating the commit hash and date above. A stale root only matters if
Sigstore adds a new Fulcio CA generation or Rekor log the platform must
trust for signatures made after this file was taken; existing generations'
validity windows do not expire out from under old signatures.
