-- Platform config is gated by permissions like everything else
-- (docs/spec/config-permissions.md §A.2/§A.4): `platform:admin:config:manage`
-- replaces `platform:admin:config:update` (owner: "manage", so create and
-- delete are covered, not only update). Data only — iam_role_permissions is
-- shared with Go until cutover, but the owner is moving off Go (spec §C) so
-- no Go mirror is owed for this rename.
--
-- Guard first: if a role somehow already holds both codes (a custom role
-- that separately picked up `manage` before this ran), dropping the
-- now-redundant `update` row avoids a primary-key collision (role_id,
-- permission) on the UPDATE below.
DELETE FROM iam_role_permissions dup
WHERE dup.permission = 'platform:admin:config:update'
  AND EXISTS (
      SELECT 1 FROM iam_role_permissions kept
      WHERE kept.role_id = dup.role_id
        AND kept.permission = 'platform:admin:config:manage'
  );

UPDATE iam_role_permissions
SET permission = 'platform:admin:config:manage'
WHERE permission = 'platform:admin:config:update';
