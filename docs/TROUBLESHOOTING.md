# Troubleshooting

Symptoms, causes and fixes. Ordered by how often they happen.

---

## Startup

### `Could not resolve placeholder 'FINSIGHT_DB_PASSWORD'`

```
java.lang.IllegalArgumentException: Could not resolve placeholder
'FINSIGHT_DB_PASSWORD' in value "${FINSIGHT_DB_PASSWORD}"
```

**Cause.** No `.env`, or the application was started from a different working
directory. `spring.config.import` resolves `./.env` relative to the **working
directory**, not the jar.

```bash
pwd                      # must be the project root
ls -la .env              # must exist
```

**Fix.** Run `sudo bash scripts/provision-db.sh`, or in IntelliJ set the run
configuration's *Working directory* to the project root.

---

### `finsight.jwt.secret must provide at least 32 bytes`

```
java.lang.IllegalStateException: finsight.jwt.secret must provide at least 32 bytes
(256 bits) of key material; got 12.
```

**Cause.** The secret is too short. HS256 requires ≥ 256 bits per RFC 7518 §3.2,
and `JwtService` checks at construction so a weak key breaks startup rather than
the first login in production.

**Fix.**

```bash
head -c 64 /dev/urandom | base64 | tr -d '\n'
# paste into .env as FINSIGHT_JWT_SECRET=
```

---

### `FATAL: password authentication failed for user "finsight_app"`

**Cause.** `.env` is out of step with the role's actual password. Usually the
provisioning script ran twice and the password was rotated, or `.env` was edited.

**Diagnose.**

```bash
set -a; source .env; set +a
PGPASSWORD="$FINSIGHT_DB_PASSWORD" psql -h 127.0.0.1 -U finsight_app -d finsight_db -c '\conninfo'
```

**Fix.** Re-run `sudo bash scripts/provision-db.sh` and answer **y** when it asks
to reset the existing role's password. It will write the new value to `.env`.

---

### `FATAL: role "finsight_app" does not exist`

Provisioning never ran.

```bash
sudo bash scripts/provision-db.sh
```

---

### `FATAL: database "finsight_db" does not exist`

Role created, database not — usually a script that was interrupted. Re-running is
safe; it is idempotent.

```bash
sudo -u postgres psql -c "\l" | grep finsight
sudo bash scripts/provision-db.sh
```

---

### `Connection refused` to 127.0.0.1:5432

```bash
pg_lsclusters                      # Status must be "online"
sudo systemctl status postgresql
sudo systemctl start postgresql
ss -ltnp | grep 5432
```

If the cluster is `down`, the log says why:

```bash
sudo tail -50 /var/log/postgresql/postgresql-16-main.log
```

---

### `no pg_hba.conf entry for host "127.0.0.1"`

**Cause.** PostgreSQL has no rule permitting TCP password authentication from
loopback for this role.

```bash
sudo grep -nE '^(host|local)' /etc/postgresql/16/main/pg_hba.conf
```

You need a line like:

```
host    all    all    127.0.0.1/32    scram-sha-256
```

**Fix.** Add it (or ask an administrator to), then:

```bash
sudo systemctl reload postgresql
```

> This modifies your PostgreSQL configuration. Nothing in this project does it
> automatically.

---

### `Schema-validation: wrong column type encountered`

```
Schema-validation: wrong column type encountered in column [amount] in table
[transactions]; found [float8 (Types#DOUBLE)], but expecting [numeric(19,4)]
```

**Cause.** An entity mapping and the migrations disagree. This failure is the
system working — far better than discovering the mismatch when a report returns a
wrong number.

**Fix.** The message names the table and column. Decide which side is right:

- Entity wrong → fix the `@Column`.
- Migration wrong → add a **new** migration. Never edit an applied one; Flyway
  stores a checksum and `validate` will then fail everywhere it has already run.

```sql
\d+ transactions
```

---

### `Validate failed: Migration checksum mismatch for version 4`

**Cause.** An already-applied migration file was edited.

**Fix for a development database:**

```bash
./scripts/reset-db.sh
```

**Fix if the content must change:** revert the file to its original content and
add `V8__…sql` with the change. Fix forward, always.

---

### `Found non-empty schema(s) "public" but no schema history table`

**Cause.** Tables exist that Flyway did not create — usually a manual `CREATE
TABLE` or a half-finished reset.

**Fix (development):**

```bash
./scripts/reset-db.sh
```

Do **not** reach for `spring.flyway.baseline-on-migrate=true` unless you
genuinely intend to adopt an existing schema — it tells Flyway to pretend
everything before the baseline was applied.

---

### Port 8080 already in use

```bash
ss -ltnp | grep 8080
kill <pid>
# or
SERVER_PORT=8081 ./scripts/run-local.sh
```

---

## Requests

### Everything returns 401

**Checklist, in order:**

1. Is the header exactly `Authorization: Bearer <token>`? A missing `Bearer `
   prefix is the most common cause.
2. Has the token expired? Default TTL is 30 minutes.
   ```bash
   echo "$TOKEN" | cut -d. -f2 | base64 -d 2>/dev/null | jq '.exp, .typ'
   date +%s
   ```
3. Is `typ` `ACCESS`? A refresh token in the `Authorization` header returns
   `TOKEN_INVALID` **by design**.
4. Did the application restart with a different `FINSIGHT_JWT_SECRET`? Every
   previously issued token is then invalid.

The response body distinguishes the cases:

```json
{ "code": "TOKEN_EXPIRED" }   // refresh
{ "code": "TOKEN_INVALID" }   // re-authenticate
{ "code": "AUTHENTICATION_REQUIRED" }   // no token at all
```

---

### 404 for a resource I can see in the database

**This is almost always correct behaviour**, not a bug.

Cross-user access returns 404 rather than 403, deliberately — see
[07](07-SECURITY-AND-AUTHENTICATION.md).

```sql
SELECT id, user_id FROM transactions WHERE id = 42;
```

```bash
curl -s localhost:8080/api/v1/auth/me -H "Authorization: Bearer $TOKEN" | jq .id
```

If those two ids differ, the 404 is the system working.

---

### 403 instead of 200 on `/actuator/metrics`

By design. Only `/actuator/health`, `/health/**` and `/info` are public;
everything else requires `ROLE_ADMIN`.

To grant it in development:

```sql
UPDATE users SET role = 'ROLE_ADMIN' WHERE email = 'you@example.com';
```

Then **log in again** — the role is in the token, so the existing one still says
`ROLE_USER`.

---

### 409 CONCURRENT_MODIFICATION

Optimistic locking worked. Another request modified the row between your read and
your write.

**Fix:** re-read, re-apply, retry. If it happens constantly, two clients are
fighting over the same row.

---

### 422 instead of 400

Not a bug — a different thing. 400 means "I could not parse or bind this". 422
means "I understood it, and a domain rule forbids it". The `message` names the
rule.

---

### Validation errors have no `fieldErrors`

`fieldErrors` is only populated for Bean Validation failures
(`VALIDATION_FAILED`). A `BUSINESS_RULE_VIOLATION` is not field-scoped, so it
carries only a message.

---

## CSV import

### `INVALID_CSV: The header is missing required column(s)`

The first line must contain `date`, `type`, `amount` and `description`, under one
of their accepted aliases.

```bash
head -1 your-file.csv | cat -A | head -5
```

`cat -A` reveals the real problem surprisingly often: `^M$` means CRLF line
endings with a stray character, and `M-oM-;M-?` at the start is a UTF-8 BOM.

The BOM is handled. A semicolon delimiter is **not**:

```bash
sed -i 's/;/,/g' your-file.csv
```

---

### Every row fails with `INVALID_DATE`

The date format is not one of the four supported. Note that `MM/dd/yyyy` is
**deliberately unsupported** — it is indistinguishable from `dd/MM/yyyy` for the
first twelve days of a month, and guessing wrong files a March transaction in
December.

```bash
# Convert MM/dd/yyyy to ISO
awk -F, 'NR==1{print;next}{split($1,d,"/"); printf "%s-%s-%s", d[3],d[1],d[2];
         for(i=2;i<=NF;i++) printf ",%s", $i; print ""}' in.csv > out.csv
```

---

### `importedRows: 0, duplicateRows: 33`

**Working as designed.** You uploaded this exact content before. Every row's
idempotency key already exists.

```bash
curl -s ".../api/v1/imports/$JOB" -H "Authorization: Bearer $TOKEN" | jq .previouslyUploaded
```

To import it genuinely again, change the content — the key depends on the file
checksum, so renaming does nothing.

---

### An import is stuck in `ACCEPTED`

The file went down the asynchronous path and nothing has processed it.

**1. Is Kafka reachable?**

```bash
sudo docker compose ps kafka
sudo docker exec finsight-kafka kafka-topics --bootstrap-server localhost:9092 --list
```

**2. Is the outbox backed up?**

```sql
SELECT id, event_type, attempts, last_error, created_at
FROM outbox_events WHERE published_at IS NULL ORDER BY created_at;
```

| Observation | Meaning |
|---|---|
| `attempts = 0` and the row is recent | The poller has not run yet. Wait a second. |
| `attempts` climbing, `last_error` mentions timeouts | Kafka is unreachable |
| `attempts >= 10` | Parked — it will not retry on its own |

**3. Is the consumer enabled?**

```bash
grep -i consumer-enabled src/main/resources/application.yml
```

**4. Re-arm parked rows** once the cause is fixed:

```sql
UPDATE outbox_events SET attempts = 0, last_error = NULL
WHERE published_at IS NULL AND attempts >= 10;
```

Safe — republishing is exactly the at-least-once case the consumer absorbs.

**5. Or force it through synchronously**: split the file so each part is under
`finsight.csv-import.async-threshold-rows` (50).

---

### 413 Payload Too Large

5 MiB / 50 000 rows. Split the file:

```bash
head -1 big.csv > header.csv
tail -n +2 big.csv | split -l 40000 - part-
for f in part-*; do cat header.csv "$f" > "import-$f.csv"; done
```

---

## Kafka

### `Topic not present in metadata after 60000 ms`

The single most common Kafka-in-Docker problem, and it is almost always
`advertised.listeners`.

A client bootstraps, then **reconnects to whatever address the broker
advertises**. If the broker advertises an address the client cannot route to, you
get exactly this: a successful connection followed by a timeout.

```bash
sudo docker exec finsight-kafka kafka-configs \
  --bootstrap-server localhost:9092 --describe --entity-type brokers --entity-name 1 \
  | grep advertised
```

From the host you need `EXTERNAL://localhost:9092`; from another container,
`INTERNAL://kafka:29092`. The compose file sets both.

Also check you are using the right one:

```bash
grep KAFKA_BOOTSTRAP .env     # localhost:9092 on the host, kafka:29092 in a container
```

---

### The consumer never receives anything

```bash
# Is the group consuming? Look at LAG.
sudo docker exec finsight-kafka kafka-consumer-groups \
  --bootstrap-server localhost:9092 --describe --group finsight-import-processor
```

| LAG | Meaning |
|---|---|
| High and static | The consumer is not running, or is stuck |
| 0 with no messages arriving | Nothing is being produced — check the outbox |
| Decreasing | Working |

```bash
# Are messages actually on the topic?
sudo docker exec finsight-kafka kafka-console-consumer \
  --bootstrap-server localhost:9092 --topic finsight.imports.requested.v1 \
  --from-beginning --max-messages 5
```

---

### Messages are landing in the DLT

```bash
sudo docker exec finsight-kafka kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic finsight.imports.requested.v1.DLT \
  --from-beginning --property print.headers=true
```

The headers name the original topic, partition, offset and the exception.

Common causes: a schema change that broke deserialisation; a message whose
`userId` does not match its job (classified non-retryable on purpose).

**Replaying is a deliberate, manual act.** Automatic replay of something that
already failed three times is a loop. Fix the cause, then produce the record back
onto the main topic — the idempotency ledger makes that safe.

---

## Redis

### Reports are slow, and the logs show cache warnings

```
Cache GET failed (cache=reports, key=7:summary:…); falling back to the database
```

Redis is unreachable. **The API still works** — this is fail-open behaviour, by
design.

```bash
redis-cli ping
sudo docker compose up -d redis
```

---

### Stale report data

1. Did the write commit? Check the database directly.
2. Did eviction run? Look for an **ERROR** line from `CacheErrorHandler` — a
   failed eviction is logged at ERROR precisely because it is the only cache
   failure with a correctness consequence.
3. Is the key what you expect?

```bash
redis-cli --scan --pattern 'finsight::report-*::7:*'
redis-cli TTL 'finsight::report-summary::7:summary:2026-03-01:2026-03-31'
```

**Immediate fix:**

```bash
redis-cli --scan --pattern 'finsight::report-*::7:*' | xargs -r redis-cli DEL
```

**Bound on the damage:** the 10-minute TTL.

---

### Two users seeing each other's data

This would be serious. Check the key layout immediately:

```bash
redis-cli --scan --pattern 'finsight::*' | head -20
```

Every key must contain `::<userId>:`. If one does not, a `@Cacheable` key
expression is missing `#userId` — fix it and flush the cache:

```bash
redis-cli --scan --pattern 'finsight::*' | xargs -r redis-cli DEL
```

`RedisCacheIT.usersHaveSeparateEntries` exists to catch this.

---

## Tests

### `database "finsight_test_db" does not exist`

```bash
sudo bash scripts/provision-db.sh      # creates both databases
```

---

### Integration tests are skipped entirely

Expected without the profile:

```bash
./mvnw verify -Pintegration
```

---

### `RedisCacheIT` reports skipped

Expected without Redis. The test skips with a message rather than passing without
checking anything.

```bash
sudo docker compose up -d redis
```

---

### A test passes alone but fails in the suite

Shared state. Check the test is `@Transactional`, and that it uses
`uniqueEmail(...)` rather than a fixed address.

---

### `MockMvc` returns 401 unexpectedly

Dump the full exchange:

```java
.andDo(MockMvcResultHandlers.print())
```

Usually the `Authorization` header was not set.

---

## Build

### `Plugin … could not be resolved … in offline mode`

Drop `-o`:

```bash
./mvnw -B compile
```

### `release version 21 not supported`

```bash
./mvnw -version                       # check the Java it reports
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
```

### IntelliJ shows errors that Maven does not

```
File → Invalidate Caches / Restart
Maven tool window → Reload All Maven Projects
Build → Rebuild Project
```

Also tick *Settings → Build → Build Tools → Maven → Runner →
Delegate IDE build/run actions to Maven*, so the IDE and the CLI agree.

---

## Docker

### `permission denied … /var/run/docker.sock`

The user is not in the `docker` group. Use `sudo`, or:

```bash
sudo usermod -aG docker "$USER" && newgrp docker
```

> Docker group membership is effectively root on the host. Understand that before
> adding yourself.

### A containerised app cannot reach the host's PostgreSQL

`localhost` inside a container is the container.

```yaml
FINSIGHT_DB_URL: jdbc:postgresql://host.docker.internal:5432/finsight_db
extra_hosts:
  - "host.docker.internal:host-gateway"
```

PostgreSQL must also accept connections from the Docker bridge — see
[11](11-DOCKER-AND-DEPLOYMENT.md).

---

## Diagnostics

Always start here.

```bash
# What is the application's own view of its health?
curl -s localhost:8080/actuator/health | jq

# Anything stuck in the pipeline?
curl -s localhost:8080/actuator/prometheus | grep -E 'finsight_outbox|finsight_import'

# Database reachable, and what does it contain?
set -a; source .env; set +a
PGPASSWORD="$FINSIGHT_DB_PASSWORD" psql -h 127.0.0.1 -U finsight_app -d finsight_db -c '\dt'

# Cache
redis-cli ping && redis-cli --scan --pattern 'finsight::*' | head

# Kafka
sudo docker exec finsight-kafka kafka-topics --bootstrap-server localhost:9092 --list
```

### Using a trace id

Every error response carries one, and it is also in the `X-Trace-Id` header:

```json
{ "code": "INTERNAL_ERROR", "traceId": "0f6b1c2d9a3e4f58" }
```

```bash
grep '0f6b1c2d9a3e4f58' logs/finsight.log
```

That is the whole reason it exists: the caller gets an id, the stack trace stays
server-side.

### Turning up logging temporarily

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments=\
"--logging.level.com.finsight=DEBUG \
--logging.level.org.hibernate.SQL=DEBUG \
--logging.level.org.hibernate.orm.jdbc.bind=TRACE"
```

`bind=TRACE` is the one that matters — it shows the **parameter values**, so you
can see that `user_id` was 8 when you expected 7.

Turn both off afterwards: they are very verbose and they log financial values.
