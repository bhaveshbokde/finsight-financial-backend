#!/usr/bin/env bash
#
# Starts FinSight against the local PostgreSQL instance.
#
#   ./scripts/run-local.sh          # foreground
#   ./scripts/run-local.sh --bg     # background, logs to logs/finsight.log
#
# Reads configuration from .env, which scripts/provision-db.sh generates.
# Spring Boot imports that file itself (see spring.config.import in
# application.yml), so nothing is exported into the environment here — the
# password never appears in `ps` output or in the shell's history.
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
cd "$PROJECT_DIR"

ok()   { printf '  \033[32m✓\033[0m %s\n' "$*"; }
warn() { printf '  \033[33m!\033[0m %s\n' "$*"; }
die()  { printf '  \033[31m✗ %s\033[0m\n' "$*" >&2; exit 1; }

echo
echo "FinSight :: local start"
echo "-----------------------"

[[ -f .env ]] || die ".env not found. Run:  sudo bash scripts/provision-db.sh"
ok "Found .env"

# --- PostgreSQL reachable? ---------------------------------------------------
DB_HOST_PORT="$(sed -n 's#^FINSIGHT_DB_URL=jdbc:postgresql://\([^/]*\)/.*#\1#p' .env)"
DB_HOST="${DB_HOST_PORT%%:*}"
DB_PORT="${DB_HOST_PORT##*:}"

if timeout 3 bash -c ">/dev/tcp/${DB_HOST}/${DB_PORT}" 2>/dev/null; then
  ok "PostgreSQL reachable at ${DB_HOST}:${DB_PORT}"
else
  die "PostgreSQL is not reachable at ${DB_HOST}:${DB_PORT}. Check: pg_lsclusters"
fi

# --- Optional services -------------------------------------------------------
# Neither is required to start. Redis absence means reports read PostgreSQL
# every time; Kafka absence means large imports stay ACCEPTED until a broker
# appears, because their outbox rows cannot be published.
if timeout 2 bash -c ">/dev/tcp/127.0.0.1/6379" 2>/dev/null; then
  ok "Redis reachable on 6379 — report caching active"
else
  warn "Redis not reachable on 6379 — reports will read PostgreSQL every time (fail-open)"
  warn "  start it with: sudo docker compose up -d redis"
fi

if timeout 2 bash -c ">/dev/tcp/127.0.0.1/9092" 2>/dev/null; then
  ok "Kafka reachable on 9092 — asynchronous imports will be processed"
else
  warn "Kafka not reachable on 9092 — large imports will stay ACCEPTED until a broker is available"
  warn "  start it with: sudo docker compose up -d kafka"
fi

# --- Build if needed ---------------------------------------------------------
if [[ ! -d target/classes ]]; then
  echo "  Building..."
  mvn -B -q compile
fi
ok "Compiled"

echo
if [[ "${1:-}" == "--bg" ]]; then
  mkdir -p logs
  nohup mvn -B spring-boot:run > logs/finsight.log 2>&1 &
  echo $! > app.pid
  ok "Started in the background (pid $(cat app.pid))"
  echo "  logs : tail -f logs/finsight.log"
  echo "  stop : kill \$(cat app.pid)"
else
  echo "  Starting in the foreground. Ctrl-C to stop."
  echo "  Swagger UI : http://localhost:8080/swagger-ui.html"
  echo "  Health     : http://localhost:8080/actuator/health"
  echo
  exec mvn -B spring-boot:run
fi
