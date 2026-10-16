-- A role assignment could be stored with whitespace in the role name (a service
-- account's token carried "example: dispatcher"; the service-account assignment
-- path never checked the name -- Go platform-bug-fixes F18, migration 076). Such a
-- row names no role, so it grants nothing, but it is echoed into the token's
-- `roles` claim.
--
-- Repair ONLY the unambiguous case: stripping the whitespace around the colons and
-- at either end gives the name of a role that exists. The assignment is re-written
-- under that name (keeping its source and time; if the principal already holds it,
-- the malformed row is a duplicate of that grant) and the malformed row is removed.
-- Any other malformed assignment is left untouched: nothing is deleted on a guess.
-- List what remains with
--   SELECT * FROM iam_principal_roles WHERE role_name ~ '\s';
-- Idempotent, and the same statement as Go's 076.
WITH bad AS (
    SELECT principal_id, role_name, assignment_source, assigned_at,
           regexp_replace(btrim(role_name), '\s*:\s*', ':', 'g') AS norm
      FROM iam_principal_roles
     WHERE role_name ~ '\s'
), fixable AS (
    SELECT b.* FROM bad b
     WHERE b.norm <> b.role_name
       AND b.norm !~ '\s'
       AND EXISTS (SELECT 1 FROM iam_roles r WHERE r.name = b.norm)
), ins AS (
    INSERT INTO iam_principal_roles (principal_id, role_name, assignment_source, assigned_at)
    SELECT principal_id, norm, assignment_source, assigned_at FROM fixable
    ON CONFLICT (principal_id, role_name) DO NOTHING
)
DELETE FROM iam_principal_roles pr
 USING fixable f
 WHERE pr.principal_id = f.principal_id
   AND pr.role_name = f.role_name;
