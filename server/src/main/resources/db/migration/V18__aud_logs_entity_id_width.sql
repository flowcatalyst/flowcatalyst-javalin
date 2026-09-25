-- aud_logs.entity_id was varchar(17) — a TSID's width. But a sync rollup event's
-- subject is `platform.<aggregate>.{applicationCode}` (EventTypesSynced,
-- PrincipalsSynced, SubscriptionsSynced, …), so its audit row's entity id is the
-- application code: any application whose code is longer than 17 characters
-- answered 500 AUDIT_WRITE on every SDK sync ("value too long for type character
-- varying(17)" — the parity harness's long-standing swallowed sync failure).
-- Widened to the width entity_type and principal_id already have. Widening a
-- varchar is a catalogue-only change in PostgreSQL (no table rewrite), and a
-- writer that only ever writes 17 characters (Go) is unaffected.
ALTER TABLE public.aud_logs ALTER COLUMN entity_id TYPE character varying(100);
