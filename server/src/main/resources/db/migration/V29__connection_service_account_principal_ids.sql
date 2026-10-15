-- Code-first connection sync used to store the application's service_account_id
-- as the connection's service_account_id. The application column holds the
-- service account's PRINCIPAL id (prn_..., iam_principals.id), while a
-- connection's column is the service account's OWN id (sac_...,
-- iam_service_accounts.id) -- the id the delivery signer and the connection
-- API report (Go platform-bug-fixes F17, migration 075).
--
-- Re-point those rows at the service account behind the principal. A principal
-- id never equals a service account id, so rows already correct are untouched;
-- a principal with no service account behind it is left as it is (the next
-- sync refuses it loudly, APPLICATION_SERVICE_ACCOUNT_REQUIRED). Idempotent,
-- and the same statement as Go's 075, so running both on one database is a
-- no-op the second time. Flyway has no Down.
UPDATE msg_connections c
   SET service_account_id = p.service_account_id,
       updated_at = NOW()
  FROM iam_principals p
 WHERE c.service_account_id = p.id
   AND p.service_account_id IS NOT NULL;
