# 01 — Prerequisites and Setup

## Verify your environment

```bash
java -version          # expect 21.x
mvn -version           # expect 3.9+, running on Java 21
psql --version         # expect 16.x
pg_lsclusters          # the cluster must say "online"
```

If `java -version` reports 17, point `JAVA_HOME` at a 21 JDK:

```bash
ls /usr/lib/jvm/
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
```

Optional, and genuinely optional — the application starts and serves every
synchronous endpoint without either:

```bash
docker --version && docker compose version
```

---

## Database provisioning

### What the script does

`scripts/provision-db.sh` creates exactly three things and nothing else:

| Object | Properties |
|---|---|
| Role `finsight_app` | `LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION` |
| Database `finsight_db` | owner `finsight_app`, UTF-8, `template0` |
| Database `finsight_test_db` | owner `finsight_app`, used only by `mvn verify -Pintegration` |

On each database it then runs:

```sql
REVOKE ALL ON DATABASE <db> FROM PUBLIC;
GRANT CONNECT, TEMPORARY ON DATABASE <db> TO finsight_app;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
ALTER SCHEMA public OWNER TO finsight_app;
```

So no other role can connect, and `PUBLIC` cannot create objects. `finsight_app`
owns the schema because Flyway needs DDL rights on it — but it is not a
superuser, cannot create further databases or roles, and cannot read any other
database on the cluster.

### What it will not do

- It will not drop or recreate an existing role or database.
- If `finsight_app` already exists it stops and asks before touching the password.
- If `finsight_db` already exists it reports the owner and leaves it alone.
- It refuses to run without root (`sudo`), because `CREATE ROLE` requires the
  `postgres` superuser.

### Running it

```bash
sudo bash scripts/provision-db.sh
```

Expected output:

```
FinSight :: PostgreSQL provisioning
-----------------------------------
  ✓ Connected to PostgreSQL as superuser 'postgres'
  Server version: 16.15
  Generated a new 32-character random password
  ✓ Created least-privilege role 'finsight_app'
  ✓ Created database 'finsight_db' owned by 'finsight_app'
  ✓ Hardened privileges on 'finsight_db' (PUBLIC revoked, schema owned by finsight_app)
  ✓ Created database 'finsight_test_db' owned by 'finsight_app'
  ✓ Hardened privileges on 'finsight_test_db' (...)
  ✓ Wrote /home/you/Projects/FinSight/.env (mode 0600, owner you)
  ✓ Verified: 'finsight_app' can log in to 'finsight_db' over TCP with password auth
  ✓ Confirmed 'finsight_app' is NOT a superuser
```

### How the password stays out of the wrong places

| Risk | Mitigation |
|---|---|
| Visible in `ps` | SQL is piped to `psql` on **stdin**; nothing sensitive is in `argv` |
| Shell history | Generated inside the script; you never type it |
| Committed to git | `.env` is in `.gitignore`; only `.env.example` with placeholders is tracked |
| Readable by other users | `chmod 600`, owned by the invoking user, not root |
| Logged by the app | Spring never logs `spring.datasource.password`; no code reads it |

### Doing it by hand instead

If you prefer not to run a script as root:

```bash
sudo -u postgres psql
```

```sql
-- Choose your own password; do not use this one.
CREATE ROLE finsight_app WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE
  NOREPLICATION INHERIT PASSWORD 'replace-me';

CREATE DATABASE finsight_db      OWNER finsight_app ENCODING 'UTF8' TEMPLATE template0;
CREATE DATABASE finsight_test_db OWNER finsight_app ENCODING 'UTF8' TEMPLATE template0;

\c finsight_db
REVOKE ALL ON DATABASE finsight_db FROM PUBLIC;
GRANT CONNECT, TEMPORARY ON DATABASE finsight_db TO finsight_app;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
ALTER SCHEMA public OWNER TO finsight_app;

\c finsight_test_db
REVOKE ALL ON DATABASE finsight_test_db FROM PUBLIC;
GRANT CONNECT, TEMPORARY ON DATABASE finsight_test_db TO finsight_app;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
ALTER SCHEMA public OWNER TO finsight_app;
```

Then:

```bash
cp .env.example .env
chmod 600 .env
# edit .env: set FINSIGHT_DB_PASSWORD, and generate a JWT key:
head -c 64 /dev/urandom | base64 | tr -d '\n'
```

---

## Configuration

### How `.env` reaches Spring

`application.yml` contains:

```yaml
spring:
  config:
    import: "optional:file:./.env[.properties]"
```

A `.env` file is `KEY=VALUE` lines, which is exactly `.properties` syntax, so
Spring's own config import reads it — no `dotenv` dependency, no wrapper script
exporting variables. `optional:` means CI, which supplies real environment
variables, is unaffected by the file's absence.

Precedence (highest first): command-line arguments → OS environment variables →
`.env` → defaults in `application.yml`.

### The variables

| Variable | Required | Default | Notes |
|---|---|---|---|
| `FINSIGHT_DB_URL` | no | `jdbc:postgresql://127.0.0.1:5432/finsight_db` | |
| `FINSIGHT_DB_USERNAME` | no | `finsight_app` | |
| `FINSIGHT_DB_PASSWORD` | **yes** | — | startup fails without it |
| `FINSIGHT_JWT_SECRET` | **yes** | — | base64; must decode to ≥ 32 bytes |
| `FINSIGHT_JWT_ACCESS_TTL` | no | `PT30M` | ISO-8601 duration |
| `FINSIGHT_JWT_REFRESH_TTL` | no | `P7D` | |
| `FINSIGHT_REDIS_HOST` / `_PORT` | no | `localhost` / `6379` | absent Redis is tolerated |
| `FINSIGHT_KAFKA_BOOTSTRAP_SERVERS` | no | `localhost:9092` | absent Kafka is tolerated |
| `SERVER_PORT` | no | `8080` | |

A short or missing `FINSIGHT_JWT_SECRET` fails at **startup**, by design:

```
finsight.jwt.secret must provide at least 32 bytes (256 bits) of key material; got 12.
Generate one with:  head -c 64 /dev/urandom | base64 | tr -d '\n'
```

A weak signing key must break the build, not the first login in production.

---

## First run

```bash
./scripts/run-local.sh
```

The script checks PostgreSQL is reachable, reports whether Redis and Kafka are
available, compiles if needed, and starts the application. Expected startup lines:

```
o.f.c.i.database.base.BaseDatabaseType   : Database: jdbc:postgresql://127.0.0.1:5432/finsight_db (PostgreSQL 16.15)
o.f.core.internal.command.DbMigrate      : Migrating schema "public" to version "1 - create users"
...
o.f.core.internal.command.DbMigrate      : Successfully applied 7 migrations
o.s.b.w.embedded.tomcat.TomcatWebServer  : Tomcat started on port 8080 (http)
c.finsight.FinSightApplication           : Started FinSightApplication in 4.2 seconds
```

Confirm the schema:

```bash
set -a; source .env; set +a
PGPASSWORD="$FINSIGHT_DB_PASSWORD" psql -h 127.0.0.1 -U finsight_app -d finsight_db -c '\dt'
PGPASSWORD="$FINSIGHT_DB_PASSWORD" psql -h 127.0.0.1 -U finsight_app -d finsight_db \
  -c 'SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;'
```

Nine tables plus `flyway_schema_history`, and seven successful migrations.

> `source .env` puts the password into this shell's environment. Do not do it in
> a shell whose history is shared, and close the shell when finished.

---

## Optional services

### Redis

```bash
sudo docker compose up -d redis
redis-cli ping            # PONG
```

Without it, report endpoints still work — they read PostgreSQL every time. The
application logs a warning per cache operation and continues; see
[09-REDIS-AND-CACHING](09-REDIS-AND-CACHING.md).

### Kafka

```bash
sudo docker compose up -d kafka
sudo docker compose logs -f kafka          # wait for "Kafka Server started"
sudo docker exec finsight-kafka kafka-topics --bootstrap-server localhost:9092 --list
```

Without it, imports **below** `async-threshold-rows` (50 by default) still work
normally — they are processed inline. Larger ones return `202 ACCEPTED` and stay
`ACCEPTED`; their outbox rows accumulate and are published once a broker appears.
`finsight.outbox.pending` reports the backlog.

### Both, plus a Kafka UI

```bash
sudo docker compose --profile tools up -d
# Kafka UI on http://localhost:8081
```

---

## Running FinSight itself in Docker

```bash
set -a; source .env; set +a
sudo -E docker compose --profile app up -d --build
```

Note `sudo -E`, which preserves the exported variables.

The important subtlety: **`localhost` inside a container is the container**. The
compose file therefore sets

```yaml
FINSIGHT_DB_URL: jdbc:postgresql://host.docker.internal:5432/finsight_db
extra_hosts:
  - "host.docker.internal:host-gateway"
```

so the containerised application reaches the host's PostgreSQL. You may also need
PostgreSQL to accept connections from the Docker bridge — see
[11-DOCKER-AND-DEPLOYMENT](11-DOCKER-AND-DEPLOYMENT.md).

---

## Opening the project in IntelliJ IDEA

1. **File → Open**, select the `FinSight` directory (the one with `pom.xml`).
   Choose *Open as Project*. IntelliJ imports the Maven model itself — do not
   run `mvn idea:idea`.
2. **File → Project Structure → Project** → SDK 21, language level 21.
3. **Settings → Build → Build Tools → Maven → Runner** → tick
   *Delegate IDE build/run actions to Maven* so IDE and CLI builds agree.
4. Wait for indexing, then **Build → Rebuild Project**.
5. Run configuration: IntelliJ detects `FinSightApplication`. It inherits the
   working directory, so `.env` is picked up automatically. Verify
   *Working directory* is `$MODULE_WORKING_DIR$` or the project root.
6. Optional: install the *Database* tool window connection —
   host `127.0.0.1`, port `5432`, database `finsight_db`, user `finsight_app`,
   password from `.env`.

To debug, set a breakpoint in `TransactionController.create` and run the curl
command from the README. More in
[10-TESTING-AND-DEBUGGING](10-TESTING-AND-DEBUGGING.md).

---

## Resetting

```bash
./scripts/reset-db.sh
```

Destructive. It drops and recreates the `public` schema of `finsight_db` and
replays every migration. It requires you to type `RESET finsight_db` exactly,
then confirm again, and it refuses to run against any other database name. There
is no `--force` flag: a reset that can be triggered by one pasted command is a
reset that will eventually happen by accident.

---

## If something fails

See [TROUBLESHOOTING](TROUBLESHOOTING.md). The three most common:

| Symptom | Cause |
|---|---|
| `FATAL: password authentication failed for user "finsight_app"` | `.env` out of step with the role's password — re-run the provisioning script |
| `Could not resolve placeholder 'FINSIGHT_JWT_SECRET'` | no `.env`, or the app started from a different working directory |
| `Validation failed: wrong column type encountered` | entity mapping and migrations disagree — read the message, it names the column |
