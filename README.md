# FinSight

A financial transaction management and cash-flow analytics backend, built as a
modular Spring Boot monolith.

Users register, record income and expenses, import bank-style CSV statements,
organise spending into categories, set monthly budgets, and read cash-flow
reports. All data is **synthetic** — the application connects to no real
financial institution and asks for no real credentials.

[![CI](https://github.com/bhaveshbokde/finsight-financial-backend/actions/workflows/ci.yml/badge.svg)](https://github.com/bhaveshbokde/finsight-financial-backend/actions/workflows/ci.yml)

---

## Why this project exists

It is a portfolio project, written to be read. The interesting parts are not the
CRUD endpoints — they are the decisions around them:

| Problem | How it is solved here |
|---|---|
| Publishing a Kafka event and committing a database row is a dual write | A **transactional outbox**: the event row is INSERTed in the same local transaction, and a poller publishes it. Gives at-least-once delivery, *not* exactly-once — [docs/08](docs/08-KAFKA-AND-ASYNCHRONOUS-PROCESSING.md) |
| A redelivered message must not import the same transactions twice | Two independent mechanisms: a `processed_events` ledger claimed with `INSERT … ON CONFLICT DO NOTHING`, and a partial unique index on `(user_id, external_ref)` where the ref is `sha256(fileChecksum + ':' + rowNumber)` |
| A cached report must never be served to the wrong user | Every cache key begins with the user id (`finsight::report-summary::7:summary:…`), which also makes per-user eviction a single prefix scan — [docs/09](docs/09-REDIS-AND-CACHING.md) |
| One bad row should not reject a 2 000-row statement | Partial-failure imports: valid rows are inserted, invalid rows are recorded individually with a line number and a reason |
| A user must never read another user's data | Ownership is part of the `WHERE` clause, never a check applied afterwards. Cross-user access returns **404, not 403**, so the API is not an existence oracle — [docs/07](docs/07-SECURITY-AND-AUTHENTICATION.md) |
| Money must be exact | `BigDecimal` end to end, `NUMERIC(19,4)` in PostgreSQL, `amount` always positive with direction carried by `type` |

---

## Stack

| Component | Version | Why |
|---|---|---|
| Java | 21 | Records, pattern matching, text blocks |
| Spring Boot | 3.5.16 | Last 3.x line; pins compatible Spring 6 / Security 6 / Hibernate 6 / Flyway 11 |
| PostgreSQL | 16 | Partial indexes, `FOR UPDATE SKIP LOCKED`, `jsonb` |
| Flyway | 11.x (Boot-managed) | Versioned migrations are the authoritative schema |
| Spring Security + JJWT | 6.5.x / 0.12.6 | Stateless HS256 bearer tokens |
| Redis | 7.4 | Report caching, fail-open |
| Apache Kafka | 3.9 (KRaft) | Asynchronous import processing |
| OpenCSV | 5.9 | CSV tokenising |
| springdoc-openapi | 2.8.13 | OpenAPI 3 + Swagger UI |
| JUnit 5 / Mockito / AssertJ | Boot-managed | Testing |
| JaCoCo | 0.8.13 | Coverage |

Full rationale: [docs/12-DESIGN-DECISIONS-AND-TRADEOFFS.md](docs/12-DESIGN-DECISIONS-AND-TRADEOFFS.md)

---

## Quick start

### 1. Prerequisites

```bash
java -version      # 21
psql --version     # 16
pg_lsclusters      # the server must be online
```

Maven is not required: `./mvnw` is bundled and fetches Maven 3.9.11 on first use.

### 2. Provision the database

PostgreSQL must already be installed and running. This script creates **only**
the role `finsight_app` and the databases `finsight_db` and `finsight_test_db`.
It never touches anything else, and it refuses to run without `sudo` because
creating a role needs the `postgres` superuser.

```bash
sudo bash scripts/provision-db.sh
```

It generates a random 32-character password, writes it to a `chmod 600` `.env`
owned by you, and verifies the login works. The password is never passed as a
command-line argument and never enters your shell history.

### 3. Run

```bash
./scripts/run-local.sh
```

Flyway applies the seven migrations on first start.

| | |
|---|---|
| Swagger UI | <http://localhost:8080/swagger-ui.html> |
| OpenAPI JSON | <http://localhost:8080/v3/api-docs> |
| Health | <http://localhost:8080/actuator/health> |
| Metrics | <http://localhost:8080/actuator/prometheus> |

### 4. Try it

```bash
# Register
curl -s -X POST http://localhost:8080/api/v1/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"email":"asha@example.com","password":"Str0ngPassphrase","fullName":"Asha Iyer"}' | jq

TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"asha@example.com","password":"Str0ngPassphrase"}' | jq -r .accessToken)

# Record an expense
curl -s -X POST http://localhost:8080/api/v1/transactions \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"type":"EXPENSE","amount":1249.50,"txnDate":"2026-03-14",
       "description":"Weekly groceries","merchant":"Big Bazaar"}' | jq

# Import a statement
curl -s -X POST http://localhost:8080/api/v1/imports \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@samples/transactions-sample.csv" | jq

# Upload it again — imports nothing, reports every row as a duplicate
curl -s -X POST http://localhost:8080/api/v1/imports \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@samples/transactions-sample.csv" | jq '.importedRows, .duplicateRows'

# Read the month
curl -s "http://localhost:8080/api/v1/reports/summary?from=2026-03-01&to=2026-03-31" \
  -H "Authorization: Bearer $TOKEN" | jq
```

Or run the whole thing at once:

```bash
./scripts/smoke-test.sh
```

### 5. Redis and Kafka

Both are optional — without Redis, reports read PostgreSQL every time; without
Kafka, large imports stay `ACCEPTED` until a broker appears.

On this machine they run **in user space**, with no Docker and no sudo:

```bash
./scripts/local-services.sh install    # once: downloads Kafka, builds Redis
./scripts/local-services.sh start
./scripts/local-services.sh status
```

Everything lands in `~/.local/opt`; remove it with
`./scripts/local-services.sh uninstall`.

If you would rather use Docker:

```bash
sudo docker compose up -d redis kafka     # the socket here is root-owned
```

---

## Testing

```bash
./mvnw test                      # 179 unit tests. No infrastructure. Always runnable.
./mvnw verify -Pintegration      # + 157 integration tests against real services
./mvnw verify -Pcontainers       # Testcontainers variants (needs JVM access to Docker)
```

Currently **179 unit + 157 integration tests pass, zero failures, zero skips** —
against real PostgreSQL, Redis and Kafka. Coverage is **77.0% instruction /
80.9% line**; the report lands in `target/site/jacoco/index.html`.

Tests requiring Redis are tagged `redis` and **skip themselves** with a message
when it is unreachable — a skip is reported honestly rather than passing without
checking anything.

See [docs/10-TESTING-AND-DEBUGGING.md](docs/10-TESTING-AND-DEBUGGING.md).

---

## Project layout

```
src/main/java/com/finsight/
├── auth/          registration, login, JWT issuing and the security filter
├── budget/        monthly caps and budget-versus-actual
├── category/      income/expense categories, system and user-owned
├── common/        error model, shared DTOs, money and hashing utilities, cache evictor
├── config/        Spring configuration and @ConfigurationProperties records
├── imports/       CSV parsing, validation, import jobs, row errors
├── messaging/     Kafka topics, event schema, consumer, idempotency ledger
├── outbox/        transactional outbox and its publisher
├── report/        aggregate queries and report assembly
├── transaction/   the core domain: transactions, filtering, specifications
└── user/          the user entity and repository

src/main/resources/db/migration/   V1–V7, the authoritative schema
```

Each feature package is internally layered `api → service → repository → domain`,
with `dto` alongside. Dependencies point inward and across, never upward.

---

## Documentation

| | |
|---|---|
| [00-START-HERE](docs/00-START-HERE.md) | Reading order |
| [01-PREREQUISITES-AND-SETUP](docs/01-PREREQUISITES-AND-SETUP.md) | Environment, provisioning, first run |
| [02-ARCHITECTURE](docs/02-ARCHITECTURE.md) | Layers, modules, diagrams |
| [03-DATABASE-DESIGN](docs/03-DATABASE-DESIGN.md) | Every table, index and constraint |
| [04-API-REFERENCE](docs/04-API-REFERENCE.md) | Every endpoint with runnable curl |
| [05-COMPLETE-EXECUTION-FLOW](docs/05-COMPLETE-EXECUTION-FLOW.md) | A request traced end to end |
| [06-CLASS-BY-CLASS-GUIDE](docs/06-CLASS-BY-CLASS-GUIDE.md) | What each class is for |
| [07-SECURITY-AND-AUTHENTICATION](docs/07-SECURITY-AND-AUTHENTICATION.md) | JWT, RBAC, data isolation |
| [08-KAFKA-AND-ASYNCHRONOUS-PROCESSING](docs/08-KAFKA-AND-ASYNCHRONOUS-PROCESSING.md) | Outbox, retries, DLT, idempotency |
| [09-REDIS-AND-CACHING](docs/09-REDIS-AND-CACHING.md) | Keys, TTLs, invalidation, fail-open |
| [10-TESTING-AND-DEBUGGING](docs/10-TESTING-AND-DEBUGGING.md) | Test strategy, IntelliJ debugging |
| [11-DOCKER-AND-DEPLOYMENT](docs/11-DOCKER-AND-DEPLOYMENT.md) | Images, Compose, CI |
| [12-DESIGN-DECISIONS-AND-TRADEOFFS](docs/12-DESIGN-DECISIONS-AND-TRADEOFFS.md) | Why, and what was given up |
| [TROUBLESHOOTING](docs/TROUBLESHOOTING.md) | Failures and fixes |

---

## Known limitations

Stated plainly, because a portfolio project that overclaims is worse than one
that does less honestly.

- **Refresh tokens cannot be revoked.** They are stateless and not stored, so a
  leaked refresh token stays valid until it expires. Rotation is not implemented.
- **A role or disablement change takes up to one access-token TTL** (30 minutes)
  to take effect, because the filter trusts verified claims rather than re-reading
  the user on every request.
- **Delivery is at-least-once, not exactly-once.** The outbox narrows the window;
  it does not close it. Duplicate *effects* are prevented downstream.
- **Uploaded CSVs are stored in the database.** Acceptable at the 5 MiB cap;
  object storage would be correct at real scale.
- **Kafka retry and dead-letter routing are not covered by an executed test.**
  They are implemented and configured, but nothing forces a consumer failure, so
  no claim is made that they have been observed working.
- **The Docker image has never been built here** (the daemon is not reachable
  from this user). CI builds it on every push. Redis and Kafka run in user space
  instead — see [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md).
- **Single-node assumptions in the local Compose file** (replication factor 1).
  Not production settings, and labelled as such.
- A source-level security review is not a penetration test.

---

## License

MIT — see [LICENSE](LICENSE).
