# Database credentials from AWS Secrets Manager, with rotation

Extracted 2026-09-05 against Go HEAD (`9f7be62`) from
`internal/server/dbsecret.go` (`ResolveDBSecretURL`, `DBSecretRefresher`),
`cmd/fc-server/main.go`, and the tests `TestBuildDBSecretDSN`,
`TestRegionFromARN`, `TestResolveDBSecretURLNotApplicable`,
`TestNewDBSecretRefresherNotApplicable`. Java: `Main.java:38` TODO(port).
`[C]` contract.

## 1. When it applies [C]

Only when **no** `FC_DATABASE_URL` / `DATABASE_URL` is set **and** both
`DB_SECRET_ARN` and `DB_HOST` are; otherwise "not applicable" (the URL
path is unchanged). `DB_SECRET_PROVIDER` (default `aws`) — any other value
is a startup error `DB_SECRET_PROVIDER "<v>" not supported (only "aws")`.
Startup errors here are fatal (`Main` exits 1, as Go does), before any
subsystem starts.

## 2. Resolving the URL [C]

- AWS client: default credential chain; the region parsed from the ARN's
  fourth colon-separated field when the ARN starts with `arn` (else the
  chain's default region). `GetSecretValue(SecretId = arn)`.
- Secret JSON `{username, password, port?}`; missing string value → error
  "secret <arn> has no string value"; unparseable → "parse secret <arn>
  JSON"; blank username or password → "secret <arn> is missing
  username/password".
- DSN `postgresql://<username>:<url-encoded password>@<host[:port]>/<DB_NAME, default flowcatalyst>`
  where the port is the secret's `port` when > 0, else `DB_PORT`, else
  `5432`; a `DB_HOST` that already contains `:` is used as-is
  (`TestBuildDBSecretDSN`).
- Logged as "resolved database URL from AWS Secrets Manager" (never the
  DSN).

## 3. Rotation — `DB_SECRET_REFRESH_INTERVAL_MS` (default 300000; ≤ 0 disables) [C]

When applicable and enabled: fetch the secret once at start (a failure is
fatal), then every interval re-fetch and swap the cached `username` /
`password` under a lock; a refresh failure is logged and the old
credentials stay. **New connections use the current credentials**;
existing connections are untouched (Go: pgx `BeforeConnect`). Java: the
pool is HikariCP — apply the rotated credentials through
`HikariDataSource.getHikariConfigMXBean().setUsername/setPassword`, which
affects connections created after the call, exactly Go's semantics. The
refresher stops with the server.

## 4. Tests

`DbSecretDsnTest` (the port precedence table; host with port; password
URL-encoding of `@`, `:`, `/`, `%`), `RegionFromArnTest` (`arn:aws:secretsmanager:eu-west-1:…` → `eu-west-1`;
non-ARN → empty), `DbSecretModeTest` (not applicable when a URL is set or
either of ARN/host is missing; provider other than `aws` fails; a stub
`SecretsManagerClient` returning each malformed secret yields the exact
message), `DbSecretRefresherTest` (after a rotation on the stub, a newly
opened connection's credentials are the new pair while an existing one is
unchanged — observed on the embedded Postgres by creating a second role
and rotating to it; a failed refresh keeps the old pair). Mutation: drop
the swap → the rotation test fails.
