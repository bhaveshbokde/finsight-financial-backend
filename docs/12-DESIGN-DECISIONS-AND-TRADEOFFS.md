# 12 — Design Decisions and Trade-offs

Every decision costs something. This document states what each one bought and
what it gave up. Where the alternative would be better at a different scale, it
says so.

---

## Architecture

### Modular monolith, not microservices

**Chose:** one deployable, one database, feature packages with enforced
boundaries.

**Bought:** ACID transactions across features (the import pipeline writes a job,
a payload and an outbox row atomically — a saga otherwise); one thing to deploy,
debug and trace; refactoring across boundaries is a compiler-checked rename.

**Gave up:** independent scaling and deployment of features; language choice per
service; fault isolation between modules.

**When the alternative wins:** when teams need to deploy independently, or one
feature's load genuinely differs by an order of magnitude. Neither applies to a
single-author project.

The boundaries are real, so the seam exists if it is ever needed. `imports` could
become a service: it already communicates through events.

### Layered, not hexagonal

**Chose:** `api → service → repository → domain`, with services depending
directly on Spring Data interfaces.

**Bought:** far less code. No port interfaces, no adapter implementations, no
mapping between a domain model and a persistence model.

**Gave up:** the domain is not framework-free — entities carry JPA annotations,
so swapping the persistence technology would touch them.

**Honest assessment:** hexagonal architecture earns its keep when the domain is
complex and genuinely needs to be reasoned about independently of storage, or
when storage really might change. Here the domain is a financial ledger that maps
cleanly onto tables, and "we might replace PostgreSQL" is a hypothetical. The
abstraction would be cost without benefit — and reviewers notice unjustified
indirection as readily as they notice its absence.

### Spring Boot 3.5.x, not 4.x

**Chose:** the last 3.x line.

**Bought:** a mature, well-documented ecosystem where every Stack Overflow answer
and every tutorial still applies — which matters for a project meant to be read
and learned from.

**Gave up:** Spring Framework 7 features.

---

## Data model

### `amount` is always positive; `type` carries the direction

**Bought:** `CHECK (amount > 0)` becomes possible, so the database enforces it.
Aggregation reads as
`SUM(CASE WHEN type = 'INCOME' THEN amount END)` rather than trusting every
writer to have got a sign right. CSV import can accept `-1249.50` from a bank
export and store the magnitude.

**Gave up:** `SUM(amount)` alone is meaningless — every query must say which
direction it wants.

**The alternative's failure mode:** signed amounts look simpler until one writer
stores an expense as positive. Then every total is wrong, and nothing complains.

### `txn_date` is `DATE`, not `TIMESTAMPTZ`

**Bought:** no time-zone ambiguity at month boundaries. A transaction dated 31
March is in March for every reader, everywhere. `BETWEEN '2026-03-01' AND
'2026-03-31'` means exactly what it looks like.

**Gave up:** cannot order two transactions within a day, or report on time of
day.

**Why it is right:** a purchase happens on a calendar day from the user's point
of view. Storing an instant would put a 23:30 IST purchase on 31 March into April
for a UTC reader — a bug that appears only at month ends and is extremely
confusing when reported.

### `BIGINT` identity keys, not UUID

**Bought:** 8 bytes instead of 16; sequential inserts with excellent B-tree
locality; readable in logs and `psql`.

**Gave up:** ids are guessable and enumerable.

**Why that is acceptable here:** no endpoint ever returns another user's id.
Ownership is in the `WHERE` clause, so a foreign id is "not found". Enumeration
yields 404s.

**When to switch:** ids crossing a trust boundary, or multi-master writes. Then
UUIDv7 — time-ordered, so it keeps the locality that UUIDv4 destroys.

### Two separate "sameness" concepts

| | `external_ref` | `dedupe_hash` |
|---|---|---|
| Purpose | idempotency key | heuristic fingerprint |
| Unique | **yes**, per user | **no** |
| Source | `sha256(fileChecksum + ':' + rowNumber)` | content hash |

**Bought:** a redelivered message cannot insert twice (unique), *and* two
identical coffees on the same day are both recorded (non-unique).

**Gave up:** two columns and an explanation instead of one.

**The failure mode if conflated:** making `dedupe_hash` unique rejects legitimate
data. Making `external_ref` non-unique loses the idempotency guarantee. They are
genuinely different ideas.

### Partial unique indexes instead of one composite

```sql
CREATE UNIQUE INDEX uq_budgets_user_overall_month
    ON budgets (user_id, period_month) WHERE category_id IS NULL;
CREATE UNIQUE INDEX uq_budgets_user_category_month
    ON budgets (user_id, category_id, period_month) WHERE category_id IS NOT NULL;
```

**Why:** SQL treats NULLs as distinct, so a single
`UNIQUE (user_id, category_id, period_month)` would happily allow two overall
budgets for the same month. The same reasoning applies to system vs user
categories.

**Cost:** two indexes and a non-obvious schema that needs this paragraph.

### Soft delete for categories, hard delete for transactions

**Categories archive.** A hard delete would fire `ON DELETE SET NULL` across
every transaction that referenced it, silently rewriting the user's spending
history.

**Transactions delete outright.** A user who deletes a transaction means it; a
soft delete would require every query and aggregate to filter on it, and one
forgotten filter puts deleted money back in a report.

### `ON DELETE` behaviours

| FK | Action | Why |
|---|---|---|
| `transactions.user_id` | `CASCADE` | Deleting an account must remove its data |
| `transactions.category_id` | `SET NULL` | A deleted category must not delete a year of spending |
| `transactions.import_job_id` | `SET NULL` | Purging import history must not delete what it produced |

---

## Persistence

### Flyway owns the schema; `ddl-auto=validate`

**Bought:** versioned, reviewable, reproducible schema. Every environment is
identical. `validate` makes a drifted mapping a **startup failure** rather than a
wrong number in a report six weeks later.

**Gave up:** adding a field is two edits, not one.

**Why `update` is not an option:** it never drops or narrows a column, so the
schema accumulates dead columns; it produces different results depending on the
starting state; and it has no review step. It is a development convenience that
quietly becomes a production incident.

### `open-in-view: false`

**Bought:** database connections are released when the service method returns,
not held for the whole request. A `LazyInitializationException` surfaces
immediately in development instead of under production load.

**Gave up:** every lazy association must be fetched deliberately — hence the
`@EntityGraph` annotations.

**Why the default (`true`) is wrong:** it pins a connection to the request thread
for the entire request, including JSON serialisation. Under load the pool
exhausts and the cause is extremely hard to find. It also hides N+1 queries,
because the extra SELECTs silently succeed.

### `@EntityGraph` rather than `JOIN FETCH` or eager loading

**Bought:** one query for a 20-row page instead of 21, with the fetch plan
declared on the repository method rather than duplicated into each query string.

**Gave up:** a forgotten graph on a new method reintroduces the N+1.

**Why not `EAGER` on the association:** it would apply to *every* load, including
the many that never touch the category.

### Optimistic locking by default

**Bought:** no locks held across a request; concurrent edits to *different* rows
never contend; lost updates become a clean 409.

**Gave up:** a client must handle 409 and retry.

**Why not pessimistic:** a row lock held across a user's think-time serialises
unrelated work. The conflict rate here is low — two requests editing the *same*
transaction simultaneously is rare.

### Budget spend measured, never accumulated

**Bought:** correctness that cannot drift. No counter to race on. No reconciliation
job.

**Gave up:** an aggregate query per budget read, instead of a column read.

**Why:** a `spent_so_far` counter needs adjusting on create, update (where the
amount, date *or* category may change), delete, category archival and import
rollback. Any missed path corrupts it silently, with no way to detect it short of
a full recomputation — at which point you may as well compute it. The query is
indexed and bounded by one user's month.

**When the alternative wins:** if this aggregate became hot enough to matter, a
materialised view refreshed on write would be the next step — still derived, not
hand-maintained.

---

## Asynchronous processing

### Transactional outbox

**Bought:** the business write and the event are atomic. No state where a job
waits for an event that was never recorded.

**Gave up:** a table, a poller, up to one poll interval of latency, and a row to
clean up.

**Rejected alternatives:**

| Alternative | Why not |
|---|---|
| Send after commit | The process can die between the commit and the send |
| `TransactionSynchronization` afterCommit | Same window, just smaller |
| Two-phase commit | PostgreSQL and Kafka do not usefully support XA together; 2PC is a coordinator and a new failure mode |
| Debezium / CDC | Reads the WAL, so no poller and no latency — but it is a separate deployed system. Correct at scale; disproportionate here. |

### At-least-once, stated honestly

The publisher can die after the broker acks but before `published_at` commits.
That window is small and **not zero**, and it cannot be closed without a
distributed transaction.

**This project does not claim exactly-once.** It prevents duplicate *effects*
with two independent mechanisms. Claiming exactly-once because Kafka is involved
is a common and wrong thing to say.

### Two idempotency layers

**Bought:** correctness survives one of them being bypassed by a future change.

**Gave up:** a table and an index.

**Why both:** the ledger is consumer-scoped and would be defeated by a different
consumer group or a manual replay. The unique index is unconditional. For
financial data, defence in depth is proportionate.

### CSV content stored in the database

**Bought:** the job, the payload and the event are one atomic write. No second
system to keep consistent. No orphaned objects.

**Gave up:** database size grows with upload volume; a 5 MiB row is not free.

**Mitigations in place:** a 5 MiB cap; a separate table so `import_jobs` stays
narrow; the payload is **deleted** when the job finishes.

**At real scale:** object storage with a key in the row — reintroducing a dual
write that would need its own cleanup strategy.

### Event version in the topic name

**Bought:** old and new consumers run side by side during a rollout. A breaking
change cannot break a running consumer.

**Gave up:** a topic per version to create and eventually retire.

---

## Caching

### Reports cached, lists not

**Why:** cache what is expensive, frequently read, and has few distinct shapes.
The transaction list fails the third test badly — any date range, amount window
or search term — so the hit rate would be near zero while the memory cost and
invalidation surface would be large.

### User id first in the key

**Bought:** no cross-user leakage, and per-user eviction with one SCAN.

**Gave up:** no shared entries between users (there is nothing worth sharing).

**The alternative's failure mode:** with the range first, per-user eviction
requires scanning the whole keyspace — so in practice people reach for
`cache.clear()`, which discards every other user's entries on every write.

### Fail-open

**Bought:** a cache outage is slower reads, never failed requests.

**Gave up:** a failed *eviction* leaves data stale until the TTL.

**Why acceptable:** a personal-finance figure up to ten minutes behind is
tolerable; a 500 on every report is not. The short report TTL bounds the damage.

**When it is not acceptable:** a balance used to authorise a payment. There, fail
closed — or do not cache.

### SCAN, not KEYS

`KEYS` is O(N) over the whole keyspace and blocks single-threaded Redis. `SCAN`
gives up snapshot consistency, which is fine because a missed key expires anyway.

### JSON, not JDK serialization

**Bought:** schema evolution without invalidating the cache; readable with
`redis-cli`; no deserialization-gadget risk.

**Gave up:** slightly larger entries and marginally slower serialisation.

---

## Security

### Stateless JWT

**Bought:** horizontal scaling with no sticky sessions and no shared session
store; no database query per authenticated request.

**Gave up:** no immediate revocation. A role change or disablement takes up to one
access-token TTL (30 minutes) to apply.

**Mitigations if that mattered:** a shorter TTL (more refresh traffic), a
Redis-backed `jti` denylist (a lookup per request — i.e. partly undoing the
benefit), or re-reading the user on sensitive operations only. `/auth/refresh`
already re-reads and rejects a disabled account.

### HS256, not RS256

For one service there is no third party that needs to verify without also being
able to mint. A multi-service estate would want asymmetric keys.

### 404, not 403, for another user's resource

**Bought:** no existence oracle. An attacker walking the id space learns nothing.

**Gave up:** a legitimate user hitting a permissions problem sees "not found",
which is marginally less informative.

**Why it is not merely cosmetic:** the lookup is `findByIdAndUserId`, so the two
cases are genuinely indistinguishable in the code. A 403 would require
*deliberately* fetching the row first to discover it exists.

### CSRF disabled

Correct here: there is no ambient credential. The `Authorization` header is not
attached automatically by the browser. **This would become wrong the moment the
token moved into a cookie** — the reasoning is about the credential mechanism,
not about being "an API".

### BCrypt cost 10

~100 ms per hash. Cost 12 is ~400 ms, which also multiplies the cost of a login
flood. Cost 10 is the common default and a reasonable balance; a deployment with
rate limiting in front could raise it.

---

## API design

### `PageResponse`, not Spring's `Page`

Spring's `Page` serialises to a large, unstable structure that leaks `pageable`
and `sort.unsorted`, and newer versions log a warning about serialising it
directly. The envelope keeps the contract stable and independent of the Spring
Data version.

### 202 for queued imports, 200 for inline

**Bought:** the status code tells the truth. 202 means accepted-not-performed.

**Gave up:** clients must handle two success codes.

**Why not always 202:** a two-row import that finishes in 5 ms should not force a
poll.

### Partial-failure imports

**Bought:** a 2 000-row statement with one typo imports 1 999 rows and reports the
one to fix.

**Gave up:** the result is not all-or-nothing, so a user may need to reconcile.

**Why:** rejecting the whole file over one bad date is a worse user experience by
a wide margin, and re-uploading just the fixed rows is safe because of the
idempotency key.

### Category breakdown paginated in memory

**Bought:** correct percentages. The full set is needed to compute each
category's share — a database-side `LIMIT` would make page 2's percentages
measured against a different denominator.

**Gave up:** the whole grouped set is materialised.

**Why bounded:** the group count is the user's category count — tens. This is not
the "load everything and paginate in Java" anti-pattern, which is about unbounded
row counts.

### Inclusive date ranges

**Bought:** `from=2026-03-01&to=2026-03-31` means March, which is what a user
expects and what the URL looks like it says.

**Gave up:** differs from the half-open `[start, end)` convention common in
programming.

**Why:** `txn_date` is a `DATE`, so there is no midnight-boundary subtlety to
protect against — which is the usual argument for half-open intervals.
`ReportApiIT` has a transaction dated 31 March specifically to catch a regression
here.

---

## Testing

### Three tiers by infrastructure need

**Bought:** `mvn test` always runs, in seconds, with no setup. That is what keeps
tests being run.

**Gave up:** a developer who only runs `mvn test` misses the integration tier.

### Testcontainers declared but excluded

**Honest reason:** the developing machine's Docker socket is not reachable from
the JVM. The options were to skip them with a clear tag or to pretend otherwise.
CI uses GitHub service containers, which give the same confidence without socket
access.

### Redis tests skip rather than fail

A skip is reported honestly. A test that silently passes without Redis would be
worse than no test at all.

### No coverage percentage quoted

A percentage is trivially gamed by exercising getters, and a specific number goes
stale immediately. What is stated instead: which paths have tests, and which
explicitly do not.

---

## Things deliberately not built

| Not built | Why |
|---|---|
| Multi-currency | A real implementation needs exchange rates, a rate date per transaction, and a reporting currency. Half of that is worse than none. `currency` exists on the row as the placeholder. |
| Recurring transactions | Scope. Would need a schedule model and a generator job. |
| Bank integration | Explicitly out of scope. Synthetic data only. |
| Email notification | Would need a provider, templates, and a second outbox consumer. |
| Admin UI | This is a backend. |
| GraphQL | REST fits these resource-shaped endpoints. |
| Event sourcing | The domain has no audit requirement that a transaction log plus `updated_at` does not meet. Enormous complexity for no gain here. |
| Rate limiting | Named as a real gap in [07](07-SECURITY-AND-AUTHENTICATION.md), not an oversight. |
| Soft delete on transactions | Every query and aggregate would need the filter; one omission puts deleted money back in a report. |

---

## If this had to handle 1000× the data

In order of what would break first:

1. **Report queries.** Pre-aggregate into a monthly rollup table maintained by
   the import pipeline, or a materialised view refreshed on write. The current
   design scans a user's transactions for the period.
2. **Transaction listing.** Offset pagination degrades at high offsets —
   PostgreSQL still walks the skipped rows. Keyset pagination
   (`WHERE (txn_date, id) < (?, ?)`) instead; the index already supports it.
3. **Table size.** Partition `transactions` by month. Queries are already
   date-ranged, so partition pruning would work immediately.
4. **Import throughput.** More Kafka partitions and more consumer instances. The
   design already supports it — consumer groups, and `SKIP LOCKED` in the poller.
5. **The outbox table.** A scheduled purge of published rows; the partial index
   keeps the poller fast regardless, but the table itself grows.
6. **Connection pool.** Add PgBouncer rather than raising `maximum-pool-size`
   past what PostgreSQL handles well.

None of these is implemented, because implementing them without the load to
justify them would be speculative complexity — and would make the code harder to
read for no measured benefit. **No performance numbers appear anywhere in this
project, because none were measured.**
