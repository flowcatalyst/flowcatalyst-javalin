-- Adopted from flowcatalyst-go internal/migrate/sql/055_platform_event_schema_version.sql
-- The platform seeder attached its event-type schemas as version 'v1',
-- while everything else that writes a schema version — the create
-- operation's initial schema, the SDKs' default — uses '1.0'. 'v1' also has
-- no parseable major segment for the finalise auto-deprecate sibling lookup
-- (SpecVersion#major: "1.0" → "1", "v1" → "v1"), so a later '2.0' could never
-- pair up with it.
--
-- Scoped to the seeded catalogue (every seeded code is under `platform:`);
-- a 'v1' someone attached to their own event type is their data. The
-- NOT EXISTS guard keeps uq_msg_spec_versions_event_type_version safe when a
-- '1.0' is already there.
--
-- Both platforms share one database: the statement is idempotent, so
-- whichever deploys first does the work and the other's run matches no rows.
UPDATE msg_event_type_spec_versions sv
   SET version = '1.0', updated_at = NOW()
  FROM msg_event_types et
 WHERE et.id = sv.event_type_id
   AND et.code LIKE 'platform:%'
   AND sv.version = 'v1'
   AND NOT EXISTS (
       SELECT 1 FROM msg_event_type_spec_versions other
        WHERE other.event_type_id = sv.event_type_id
          AND other.version = '1.0');
