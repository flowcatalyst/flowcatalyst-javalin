-- iam_roles.application_id was left NULL by the admin role create and by the
-- platform role seed/sync, although application_code names an application that
-- exists (Go platform-bug-fixes F18, migration 077). A role with no application id
-- is invisible to the application-scoped lookups (role sync finds an application's
-- roles by id) and is dropped from tokens narrowed to an application.
--
-- Set it from app_applications.code wherever it is NULL and application_code names
-- an application. app_applications.code is unique, so the match is unambiguous by
-- construction. Roles whose application_code matches no application, or is empty,
-- are left alone (nothing is guessed from the role name); list them with
--   SELECT id, name, application_code FROM iam_roles WHERE application_id IS NULL;
-- Idempotent, and the same statement as Go's 077.
UPDATE iam_roles r
   SET application_id = a.id
  FROM app_applications a
 WHERE r.application_id IS NULL
   AND r.application_code IS NOT NULL
   AND r.application_code <> ''
   AND a.code = r.application_code;
