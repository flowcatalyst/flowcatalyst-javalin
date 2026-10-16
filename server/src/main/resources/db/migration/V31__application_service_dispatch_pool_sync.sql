-- The built-in platform:application-service role now carries
-- platform:messaging:dispatch-pool:sync so an application's deploy can sync its
-- own dispatch pools (Go platform-bug-fixes F19, migration 079). The seeder only
-- inserts a built-in role when it is missing (it never rewrites an existing
-- role's permissions), so existing deployments pick the grant up here, once. The
-- sync confines an application-scoped caller to its bound applications and to
-- pools that application owns (V30).
--
-- Tokens carry their permissions from mint time: an application service account
-- must mint a new token to hold the new permission. Idempotent, and the same
-- statement as Go's 079.
INSERT INTO iam_role_permissions (role_id, permission)
SELECT r.id, 'platform:messaging:dispatch-pool:sync'
  FROM iam_roles r
 WHERE r.name = 'platform:application-service'
ON CONFLICT DO NOTHING;
