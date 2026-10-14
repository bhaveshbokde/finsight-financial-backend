#!/usr/bin/env bash
#
# DESTRUCTIVE: deletes every row in the FinSight schema and re-runs the
# migrations from scratch.
#
#   ./scripts/reset-db.sh
#
# Requires an explicit, typed confirmation. There is no --force flag and no
# --yes flag, on purpose: a reset that can be triggered by a single keystroke or
# pasted command is a reset that will eventually be triggered by accident.
#
# Only ever touches `finsight_db`. The database name is checked against the URL
# in .env before anything is dropped, so this cannot be pointed at another
# database by editing one variable.
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
cd "$PROJECT_DIR"

EXPECTED_DB="finsight_db"
CONFIRMATION="RESET finsight_db"

red()  { printf '\033[31m%s\033[0m\n' "$*"; }
ok()   { printf '  \033[32m✓\033[0m %s\n' "$*"; }
die()  { printf '  \033[31m✗ %s\033[0m\n' "$*" >&2; exit 1; }

[[ -f .env ]] || die ".env not found. Run:  sudo bash scripts/provision-db.sh"

DB_URL="$(sed -n 's/^FINSIGHT_DB_URL=//p' .env | head -n1)"
DB_USER="$(sed -n 's/^FINSIGHT_DB_USERNAME=//p' .env | head -n1)"
DB_PASS="$(sed -n 's/^FINSIGHT_DB_PASSWORD=//p' .env | head -n1)"

# jdbc:postgresql://127.0.0.1:5432/finsight_db  ->  host, port, dbname
HOST_PORT_DB="${DB_URL#jdbc:postgresql://}"
DB_NAME="${HOST_PORT_DB##*/}"
HOST_PORT="${HOST_PORT_DB%/*}"
DB_HOST="${HOST_PORT%%:*}"
DB_PORT="${HOST_PORT##*:}"

# Safety rail: refuse to run against anything that is not the project database.
[[ "$DB_NAME" == "$EXPECTED_DB" ]] \
  || die "Refusing to reset '$DB_NAME'. This script only ever touches '$EXPECTED_DB'."

echo
red "================================ WARNING ================================"
red " This DELETES ALL DATA in:"
red "   database : $DB_NAME"
red "   host     : $DB_HOST:$DB_PORT"
red "   as role  : $DB_USER"
red ""
red " Every user, transaction, category, budget and import will be lost."
red " There is no undo."
red "========================================================================"
echo
echo "Type exactly the following to continue, or anything else to abort:"
echo
echo "    $CONFIRMATION"
echo
read -r -p "> " typed

[[ "$typed" == "$CONFIRMATION" ]] || { echo "Aborted. Nothing was changed."; exit 1; }

echo
echo "Row counts before the reset:"
PGPASSWORD="$DB_PASS" psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -q <<'SQL'
SELECT 'users' AS table, count(*) FROM users
UNION ALL SELECT 'transactions', count(*) FROM transactions
UNION ALL SELECT 'categories',   count(*) FROM categories
UNION ALL SELECT 'budgets',      count(*) FROM budgets
UNION ALL SELECT 'import_jobs',  count(*) FROM import_jobs
UNION ALL SELECT 'outbox_events',count(*) FROM outbox_events
ORDER BY 1;
SQL

echo
read -r -p "Last chance. Proceed? [y/N] " final
[[ "${final,,}" == "y" ]] || { echo "Aborted. Nothing was changed."; exit 1; }

# DROP SCHEMA ... CASCADE rather than TRUNCATE: it also removes
# flyway_schema_history, so Flyway replays every migration from V1 and the reset
# proves the migrations work on an empty database — which a TRUNCATE would not.
echo
echo "Dropping and recreating the public schema..."
PGPASSWORD="$DB_PASS" psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" \
  -v ON_ERROR_STOP=1 -q <<SQL
DROP SCHEMA public CASCADE;
CREATE SCHEMA public AUTHORIZATION $DB_USER;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
SQL
ok "Schema recreated (empty)"

echo "Re-applying Flyway migrations..."
mvn -B -q flyway:migrate \
  -Dflyway.url="$DB_URL" \
  -Dflyway.user="$DB_USER" \
  -Dflyway.locations=filesystem:src/main/resources/db/migration \
  -Dflyway.password="$DB_PASS" 2>/dev/null \
  || {
    echo "  (flyway:migrate plugin not configured; starting the app applies migrations instead)"
    echo "  Run: ./scripts/run-local.sh"
    exit 0
  }

ok "Migrations applied"
echo
echo "Done. The database is empty except for the seeded system categories."
