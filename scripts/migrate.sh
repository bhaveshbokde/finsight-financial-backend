#!/usr/bin/env bash
#
# Applies the Flyway migrations outside application startup.
#
#   ./scripts/migrate.sh          # finsight_db      (the application database)
#   ./scripts/migrate.sh test     # finsight_test_db (used by *IT tests)
#   ./scripts/migrate.sh info     # show the current migration state
#
# Credentials are read from .env and exported as FLYWAY_* environment variables,
# which Flyway reads natively. They are never passed as -D arguments: anything
# on a command line is visible in `ps` to every user on the machine.
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
cd "$PROJECT_DIR"

ok()  { printf '  \033[32m✓\033[0m %s\n' "$*"; }
die() { printf '  \033[31m✗ %s\033[0m\n' "$*" >&2; exit 1; }

[[ -f .env ]] || die ".env not found. Run:  sudo bash scripts/provision-db.sh"

# Read without echoing. `set -a` exports everything sourced.
set -a
# shellcheck disable=SC1091
source .env
set +a

TARGET="${1:-app}"

case "$TARGET" in
    app)   export FLYWAY_URL="$FINSIGHT_DB_URL";      LABEL="finsight_db" ;;
    test)  export FLYWAY_URL="$FINSIGHT_TEST_DB_URL"; LABEL="finsight_test_db" ;;
    info)  export FLYWAY_URL="$FINSIGHT_DB_URL";      LABEL="finsight_db" ;;
    *)     die "Unknown target '$TARGET'. Use: app | test | info" ;;
esac

export FLYWAY_USER="$FINSIGHT_DB_USERNAME"
export FLYWAY_PASSWORD="$FINSIGHT_DB_PASSWORD"

echo
echo "FinSight :: Flyway — $LABEL"
echo "-----------------------------------"

if [[ "$TARGET" == "info" ]]; then
    ./mvnw -B flyway:info 2>&1 | sed -n '/Schema version/,/^\[INFO\] ---/p;/No migrations found/p'
    exit 0
fi

./mvnw -B flyway:migrate 2>&1 | grep -E 'Migrating|Successfully|up to date|Current version|ERROR' || true

# Verify rather than trust the exit code alone.
TABLES="$(PGPASSWORD="$FLYWAY_PASSWORD" psql \
    "$(echo "$FLYWAY_URL" | sed 's#jdbc:postgresql://#postgresql://#')" \
    -U "$FLYWAY_USER" -tAc \
    "SELECT count(*) FROM information_schema.tables
     WHERE table_schema='public' AND table_type='BASE TABLE';" 2>/dev/null || echo 0)"

APPLIED="$(PGPASSWORD="$FLYWAY_PASSWORD" psql \
    "$(echo "$FLYWAY_URL" | sed 's#jdbc:postgresql://#postgresql://#')" \
    -U "$FLYWAY_USER" -tAc \
    "SELECT count(*) FROM flyway_schema_history WHERE success = true AND version IS NOT NULL;" \
    2>/dev/null || echo 0)"

echo
ok "$LABEL: $APPLIED migration(s) applied, $TABLES table(s) present"

if [[ "$TABLES" -lt 10 ]]; then
    die "Expected at least 10 tables (9 application + flyway_schema_history); found $TABLES"
fi
