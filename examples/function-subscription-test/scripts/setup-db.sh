#!/usr/bin/env bash
# One-time setup for the subscription test against fcdev's embedded Postgres:
# a database and a role of its own, so the function never holds the superuser.
#
#   fcdev start                       (in another terminal — the embedded Postgres must be up)
#   examples/function-subscription-test/scripts/setup-db.sh
#
# Then set the function's secret and deploy — see the README beside this script.
# Re-runnable: everything is IF NOT EXISTS / idempotent. `fcdev fresh` wipes it all.
set -euo pipefail
PORT="${FC_EMBEDDED_DB_PORT:-15432}"
DB="${SUBSCRIPTION_TEST_DB:-subscriptiontest}"
ROLE="${SUBSCRIPTION_TEST_USER:-subscriptiontest}"
PASSWORD="${SUBSCRIPTION_TEST_PASSWORD:-subscriptiontest}"
PSQL=(psql -v ON_ERROR_STOP=1 -h 127.0.0.1 -p "$PORT" -U postgres -d postgres -qtA)

if ! command -v psql >/dev/null; then
	echo "psql is not on PATH (brew install libpq && brew link --force libpq)" >&2; exit 2
fi
if ! "${PSQL[@]}" -c 'select 1' >/dev/null 2>&1; then
	echo "no Postgres at 127.0.0.1:$PORT — is fcdev running?" >&2; exit 2
fi

"${PSQL[@]}" -c "DO \$\$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '$ROLE') THEN
    CREATE ROLE $ROLE LOGIN PASSWORD '$PASSWORD';
  END IF;
END \$\$;"
if [ "$("${PSQL[@]}" -c "SELECT 1 FROM pg_database WHERE datname = '$DB'")" != "1" ]; then
	"${PSQL[@]}" -c "CREATE DATABASE $DB OWNER $ROLE"
fi

DSN="postgres://$ROLE:$PASSWORD@127.0.0.1:$PORT/$DB"
echo "database ready: $DB (owner $ROLE) on 127.0.0.1:$PORT"
echo
echo "The function's DSN (set it as the secret EVENTS_DSN; the encrypt: prefix stores the plaintext encrypted):"
echo "  printf '%s' 'encrypt:$DSN' | fcdev fn secret set platform.test.subscription-test EVENTS_DSN"
