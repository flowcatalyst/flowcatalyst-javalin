-- Alias-prefixed hostnames on a public route (docs/spec/function-zones-and-aliases.md
-- §3, package J slice J3). Java-only, like the rest of fn_routes (V13):
-- there is no Go for the function service at all (spec §0), so this column
-- carries no goose counterpart and is never read or written by Go.
-- SchemaFingerprintTest/GoAdoptionTest already hide the whole fn_routes
-- table from the Go fingerprint comparison (V13's JAVA_ONLY_TABLES), so
-- this additive column needs no further named allowance.
--
-- alias_prefixes: opt-in DNS-label prefixes copied verbatim from the
-- function's published manifest's `public[].aliasPrefixes` — absent/empty
-- on the manifest means exact-hostname match only, the array's default.
ALTER TABLE fn_routes
    ADD COLUMN IF NOT EXISTS alias_prefixes TEXT[] NOT NULL DEFAULT '{}';
