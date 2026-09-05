# Brief — the service account's OAuth client (retire the `unavailable:auth-not-ported` stub)

Orchestrator: Fable. Coder: Sonnet, medium effort, own worktree. When the
service-account unit was ported there was no OAuth-client aggregate, so
`ServiceAccountApi#create` answers the literal `unavailable:auth-not-ported`
in both `oauth` fields (`docs/spec/serviceaccount.md` §11, "The OAuth
client secret is not backed by a real OAuth client"). The aggregate exists
now, and the parity harness (S1-B, `parity/scenarios/service-accounts/`)
shows Go minting a real `CONFIDENTIAL` client at create. Close the gap.

## Authority

`docs/spec/serviceaccount.md` §4.1 (what create answers), §8 (the mint),
§11 (the deviation you are removing — rewrite that paragraph when done).
Go: `internal/platform/serviceaccount/operations/create_credentials.go` —
the service account, its `SERVICE` principal and a confidential OAuth
client land atomically. Java template: **`application.operations.ProvisionServiceAccount`**
(a `TxOperation` that already does exactly this for an application's
service account — same client shape, same secret helper, same commit
order). Read it before writing a line.

## Files you own

```
server/src/main/java/io/flowcatalyst/platform/serviceaccount/operations/CreateServiceAccountWithCredentials.java
server/src/main/java/io/flowcatalyst/platform/serviceaccount/api/ServiceAccountApi.java
server/src/test/java/io/flowcatalyst/platform/serviceaccount/**
docs/spec/serviceaccount.md (§11 paragraph, §4.1 note)
```

`Platform.java`: only the `ServiceAccountApi.State` construction gains the
OAuth-client repository and `Optional<Encryption>` (construct them inline;
see how `ApplicationApi.State` does it a few lines earlier).

## What changes

1. `CreateServiceAccountWithCredentials` writes, in the same transaction
   after the principal: a `CONFIDENTIAL` OAuth client — `clientId` a fresh
   `EntityType.OAUTH_CLIENT` id, name `<service account name> Client`
   (check Go's exact naming in `create_credentials.go` and use that),
   `principalId` = the SERVICE principal, grant types `client_credentials`
   + `refresh_token`, scopes `openid`, `applicationIds` = the account's
   application when it has one, secret from `oauthclient.operations.Secrets`
   (no app key → the internal `SECRET` error rolls everything back).
   Event `OAuthClientCreated`. `Result` gains the client row id, the
   `client_id` and the plaintext secret.
2. `ServiceAccountApi#create` answers `oauth: {clientId, clientSecret}`
   from the result — plaintext once — and deletes `OAUTH_UNAVAILABLE`.
3. **`principalId` on the wire, as Go**: absent on the create response
   and on `GET /api/service-accounts/by-code/{code}`; present on
   `GET /api/service-accounts/{id}` (S1-B: Go's create and by-code omit it,
   Java emitted it). Check the lockfile schema's `required` for each
   response before you decide how to omit (a `@JsonInclude(NON_NULL)` on
   the member, and `null` at those two sites, if the member is optional).
4. `deleteServiceAccount` / `deactivate`: does Go cascade to the client?
   Read Go's `delete.go` / `deactivate.go`; mirror exactly and say what
   you found. If Go leaves the client, so do you (and note it in §11 as
   Go's behaviour).

## Tests you owe

- Create through the API: the OAuth client row exists with
  `principal_id` = the SERVICE principal and the stored secret ref
  decrypts to the returned plaintext; `POST /oauth/token`
  `client_credentials` with the returned pair mints a token whose `sub` is
  that principal (drive `OAuthProviderTest`'s way of wiring the provider,
  or assert the decryption only and say so).
- Atomicity: with no app key the create fails and no service account,
  principal or client row exists.
- `principalId` absent on create and by-code, present on get-by-id.
- Mutants (run, confirm, revert): (1) drop `withPrincipalId` on the
  client; (2) commit the client outside the transaction; (3) return the
  secret ref instead of the plaintext. Name the killing test for each.
- The parity corpus: `PARITY_GO_SRC=/Users/andrewgraaff/Developer/flowcatalyst-go PARITY_ONLY='service-accounts/*' mvn -q -pl parity -am test -Dtest=ParityRunTest -Dsurefire.failIfNoSpecifiedTests=false`
  — the `create` / `create-for-duplicate` / `get-by-code` diffs on
  `oauth.*` and `principalId` must be gone; report what remains verbatim.

## Build

`export JAVA_HOME=$(mise where java)`; `mvn -q -pl server test -Dtest=…
-Dsurefire.timeout=600`; never `mvn install`; `-Werror`; Jackson 3. Full
`mvn -q -pl server clean test` green before you report. Commit on your
branch; do not merge.
