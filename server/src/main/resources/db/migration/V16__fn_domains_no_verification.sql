-- Domain claims need no DNS verification (docs/spec/function-domains-no-dns.md):
-- "tenants can't deploy their own functions ... with one operator claiming
-- every domain there is nobody to defend against. A claim is verified by
-- being made." fn_domains is Java-only, like the rest of the function
-- registry (V13) — no Go for the function service at all (spec §0), so this
-- is a plain drop with no Go fingerprint to reconcile.
ALTER TABLE fn_domains
    DROP COLUMN IF EXISTS verification_token,
    DROP COLUMN IF EXISTS verified_at;
