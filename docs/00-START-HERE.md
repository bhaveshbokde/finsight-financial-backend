# 00 — Start Here

This directory documents FinSight as it is actually built. Where something is
designed but unverified, it says so.

## What FinSight is

A Spring Boot backend for personal cash-flow management. It stores income and
expense transactions, imports bank-style CSV statements, organises spending into
categories, tracks monthly budgets, and produces cash-flow reports.

It is deliberately a **modular monolith**, not microservices. One deployable, one
database, feature packages with clear boundaries. At this size, splitting it would
add network calls, distributed transactions and operational burden to solve a
scaling problem that does not exist. See
[12-DESIGN-DECISIONS-AND-TRADEOFFS](12-DESIGN-DECISIONS-AND-TRADEOFFS.md).

All data is synthetic.

## Reading order

Depending on why you are here.

### "I want to run it"

1. [01-PREREQUISITES-AND-SETUP](01-PREREQUISITES-AND-SETUP.md)
2. [04-API-REFERENCE](04-API-REFERENCE.md) — then `./scripts/smoke-test.sh`
3. [TROUBLESHOOTING](TROUBLESHOOTING.md) when something refuses to start

### "I want to understand it"

1. [02-ARCHITECTURE](02-ARCHITECTURE.md) — the shape
2. [03-DATABASE-DESIGN](03-DATABASE-DESIGN.md) — the data model everything rests on
3. [05-COMPLETE-EXECUTION-FLOW](05-COMPLETE-EXECUTION-FLOW.md) — one request, traced
4. [06-CLASS-BY-CLASS-GUIDE](06-CLASS-BY-CLASS-GUIDE.md) — what each class does
5. [07](07-SECURITY-AND-AUTHENTICATION.md), [08](08-KAFKA-AND-ASYNCHRONOUS-PROCESSING.md),
   [09](09-REDIS-AND-CACHING.md) — the three subsystems with real complexity
6. [12-DESIGN-DECISIONS-AND-TRADEOFFS](12-DESIGN-DECISIONS-AND-TRADEOFFS.md) — the why

### "I am preparing for an interview"

1. [14-LEARNING-ROADMAP](14-LEARNING-ROADMAP.md) — a study order with checkpoints
2. [13-INTERVIEW-PREPARATION](13-INTERVIEW-PREPARATION.md) — questions tied to this code
3. Then the subsystem documents above, because the answers only hold up if you
   can point at the file

## The five things worth knowing first

**1. Money is `BigDecimal`, and `amount` is always positive.**
Direction comes from `type` (INCOME / EXPENSE). That makes
`CHECK (amount > 0)` possible in the schema and removes a whole class of sign
bugs from aggregation. `double` appears nowhere near a currency value.

**2. Ownership lives in the `WHERE` clause.**
Every ownership-scoped query is `findByIdAndUserId(...)`, never `findById(...)`
followed by a check. A developer cannot forget the second half, and another
user's id is indistinguishable from a nonexistent one — hence **404, not 403**.

**3. Flyway owns the schema.**
`spring.jpa.hibernate.ddl-auto=validate`. Hibernate never creates or alters a
table; if an entity mapping drifts from the migrations, the application refuses to
start.

**4. The outbox makes event publication atomic with the business write.**
It gives **at-least-once** delivery. Not exactly-once. Duplicate *effects* are
prevented by a separate idempotency ledger and a unique index.

**5. The cache is fail-open and namespaced by user.**
A Redis outage degrades to reading PostgreSQL, never to a failed request. Every
key starts with the user id, so entries cannot leak across accounts and a single
user's entries can be evicted without clearing everyone's.

## Conventions used throughout

| | |
|---|---|
| Timestamps | `TIMESTAMPTZ` / `Instant`, UTC everywhere |
| `txn_date` | plain `DATE` — a purchase happens on a calendar day, with no time-zone ambiguity |
| Report ranges | **inclusive on both ends** |
| Money | `NUMERIC(19,4)` stored, 2 decimals displayed, `HALF_UP` |
| Errors | one `ApiError` shape, a stable `code`, a `traceId`, never a stack trace |
| Injection | constructor only; no `@Autowired` fields anywhere |

## A note on honesty

Several things in this project are designed and coded but were not executed end
to end on the machine it was built on — notably the Kafka consumer path, because
that workstation's Docker daemon is not reachable from the JVM.
[FINAL-DELIVERY-REPORT.md](../FINAL-DELIVERY-REPORT.md) lists exactly which checks
ran, which were skipped, and what their output was. Nothing in these documents
describes behaviour that does not exist in the code.
