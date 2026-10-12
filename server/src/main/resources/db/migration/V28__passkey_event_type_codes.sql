-- The passkey events move to the platform:admin:passkey:* codes Go and Rust
-- emit (owner ruling 2026-10-12); Java emitted platform:iam:passkey:* with the
-- same data. The seed now catalogues the new codes; this renames the
-- catalogue rows an older seed wrote, so each type keeps its id, status and
-- schema versions (subdomain iam -> admin, aggregate passkey), and re-points
-- the subscription bindings that name them, by row or by code: it is the same
-- event under its new code, so a subscription to "Passkey Registered" keeps
-- receiving it. Go migration 073's statements, for these three codes.
--
-- When the new code is already an event type (a Go or Rust seeder on the
-- same database inserted it first), the bindings move to that row and the old
-- row, a duplicate nothing emits any more, is deleted with its schema
-- versions; no unique-constraint failure. A binding the move would duplicate
-- (its subscription already names the new code) is dropped. Wildcard bindings
-- (platform:iam:passkey:*, platform:iam:*) are patterns, not names of these
-- types, and are left alone. Stored events and dispatch jobs keep the code
-- they were written with. Idempotent: once renamed, no old row or binding
-- matches. Flyway has no Down.
DELETE FROM msg_subscription_event_types b
 USING (VALUES ('platform:iam:passkey:registered',    'platform:admin:passkey:registered'),
               ('platform:iam:passkey:authenticated', 'platform:admin:passkey:authenticated'),
               ('platform:iam:passkey:revoked',       'platform:admin:passkey:revoked')) AS r(old_code, new_code)
 WHERE (b.event_type_code = r.old_code
        OR b.event_type_id IN (SELECT o.id FROM msg_event_types o WHERE o.code = r.old_code))
   AND EXISTS (SELECT 1 FROM msg_subscription_event_types k
                WHERE k.subscription_id = b.subscription_id AND k.event_type_code = r.new_code);

UPDATE msg_subscription_event_types b
   SET event_type_code = r.new_code,
       event_type_id = COALESCE((SELECT n.id FROM msg_event_types n WHERE n.code = r.new_code), b.event_type_id)
  FROM (VALUES ('platform:iam:passkey:registered',    'platform:admin:passkey:registered'),
               ('platform:iam:passkey:authenticated', 'platform:admin:passkey:authenticated'),
               ('platform:iam:passkey:revoked',       'platform:admin:passkey:revoked')) AS r(old_code, new_code)
 WHERE b.event_type_code = r.old_code
    OR b.event_type_id IN (SELECT o.id FROM msg_event_types o WHERE o.code = r.old_code);

DELETE FROM msg_event_type_spec_versions sv
 USING msg_event_types o,
       (VALUES ('platform:iam:passkey:registered',    'platform:admin:passkey:registered'),
               ('platform:iam:passkey:authenticated', 'platform:admin:passkey:authenticated'),
               ('platform:iam:passkey:revoked',       'platform:admin:passkey:revoked')) AS r(old_code, new_code)
 WHERE sv.event_type_id = o.id
   AND o.code = r.old_code
   AND EXISTS (SELECT 1 FROM msg_event_types n WHERE n.code = r.new_code);

DELETE FROM msg_event_types o
 USING (VALUES ('platform:iam:passkey:registered',    'platform:admin:passkey:registered'),
               ('platform:iam:passkey:authenticated', 'platform:admin:passkey:authenticated'),
               ('platform:iam:passkey:revoked',       'platform:admin:passkey:revoked')) AS r(old_code, new_code)
 WHERE o.code = r.old_code
   AND EXISTS (SELECT 1 FROM msg_event_types n WHERE n.code = r.new_code);

UPDATE msg_event_types et
   SET code = r.new_code, subdomain = r.subdomain, aggregate = r.aggregate, updated_at = NOW()
  FROM (VALUES ('platform:iam:passkey:registered',    'platform:admin:passkey:registered',    'admin', 'passkey'),
               ('platform:iam:passkey:authenticated', 'platform:admin:passkey:authenticated', 'admin', 'passkey'),
               ('platform:iam:passkey:revoked',       'platform:admin:passkey:revoked',       'admin', 'passkey'))
       AS r(old_code, new_code, subdomain, aggregate)
 WHERE et.code = r.old_code
   AND NOT EXISTS (SELECT 1 FROM msg_event_types n WHERE n.code = r.new_code);
