# 05 — Complete Execution Flow

Three requests, traced from the socket to the response and back. File and method
names are real; follow along in the IDE.

---

## Flow 1 — Creating a transaction

```
POST /api/v1/transactions
Authorization: Bearer eyJhbGciOiJIUzI1NiJ9…
Content-Type: application/json

{"type":"EXPENSE","amount":1249.50,"txnDate":"2026-03-14",
 "description":"Weekly groceries","merchant":"Big Bazaar","categoryId":5}
```

```mermaid
sequenceDiagram
    autonumber
    participant C as Client
    participant RL as RequestLoggingFilter
    participant JF as JwtAuthenticationFilter
    participant FS as FilterSecurityInterceptor
    participant DS as DispatcherServlet
    participant TC as TransactionController
    participant TS as TransactionService
    participant CS as CategoryService
    participant TR as TransactionRepository
    participant DB as PostgreSQL
    participant RC as Redis

    C->>RL: POST /api/v1/transactions
    RL->>RL: assign traceId → MDC + X-Trace-Id
    RL->>JF: continue chain
    JF->>JF: read Bearer header
    JF->>JF: JwtService.verify(token, ACCESS)
    JF->>JF: SecurityContext ← UserPrincipal(id=7)
    JF->>FS: continue
    FS->>FS: anyRequest().authenticated() → pass
    FS->>DS: continue
    DS->>DS: map handler, bind body, run @Valid
    DS->>TC: create(principal, request)
    TC->>TS: create(7, request)

    rect rgb(245,245,245)
        note over TS,DB: one transaction
        TS->>CS: requireUsable(5, 7, EXPENSE)
        CS->>DB: SELECT … WHERE id=5 AND (user_id=7 OR user_id IS NULL)
        DB-->>CS: Category(Groceries, EXPENSE)
        CS-->>TS: Category
        TS->>TS: Transaction.manual(…) → computes dedupeHash
        TS->>TR: save(transaction)
        TR->>DB: INSERT INTO transactions …
        DB-->>TR: id = 42
        TS->>RC: evictUser(7, REPORT_CACHES)
    end

    TS-->>TC: TransactionResponse
    TC-->>DS: 201 + Location
    DS-->>C: 201 Created
    RL->>RL: log "POST /api/v1/transactions -> 201 in 14ms"
```

### Step by step

**1 — `RequestLoggingFilter`** (`config/RequestLoggingFilter.java`)

`@Order(HIGHEST_PRECEDENCE)`, so it runs before security. It assigns a trace id
(honouring an inbound `X-Trace-Id` only if it matches `^[A-Za-z0-9_-]{1,64}$` —
the value is attacker-controlled and ends up in log lines, where a newline could
forge an entry) and pushes it onto the SLF4J MDC.

It runs first precisely so that an *authentication failure* — exactly the event
someone needs to trace — is logged with an id.

The `finally` block removes the MDC key. Without that, the value would leak to
the next request served by the same pooled thread and mislabel its logs.

**2 — `JwtAuthenticationFilter`** (`auth/security/JwtAuthenticationFilter.java`)

Extends `OncePerRequestFilter`, so the work happens exactly once even across a
`FORWARD` or `ERROR` dispatch.

```java
String token = extractBearerToken(request);
if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
    try {
        UserPrincipal principal = jwtService.verify(token, TokenType.ACCESS);
        authenticate(principal, request);
        MDC.put("userId", String.valueOf(principal.getId()));
    } catch (ApiException ex) {
        request.setAttribute(ATTR_AUTH_ERROR, ex);   // stash the reason
        SecurityContextHolder.clearContext();
    }
}
chain.doFilter(request, response);
```

A bad token **does not abort the chain here**. The filter records why and
continues; the authorization rules decide. That keeps every rejection in one
place, which is what makes every 401 body identical.

Inside `JwtService.verify`:

```java
Jwts.parser()
    .verifyWith(signingKey)                              // pins HMAC; alg:none cannot pass
    .requireIssuer(properties.issuer())
    .clockSkewSeconds(properties.clockSkew().toSeconds())
    .build()
    .parseSignedClaims(token)
```

Then the custom `typ` claim is checked against `ACCESS`. Without that check a
7-day refresh token would work as a 7-day API credential.

No database query. The principal is rebuilt from verified claims. That is what
makes the filter stateless — and the explicit trade-off is that a role change or
account disablement takes up to one access-token TTL to take effect.

**3 — Authorization.** `SecurityConfig` ends with
`.anyRequest().authenticated()`. Deny by default: a new controller is protected
the moment it is written.

**4 — Binding and validation.** `@RequestBody` → Jackson → `TransactionRequest`.
`@Valid` runs Bean Validation. A failure throws
`MethodArgumentNotValidException`, which `GlobalExceptionHandler` turns into a
400 listing each field — with secret-bearing values replaced by `***`.

**5 — Controller.** Three lines. The owner is `principal.getId()`, from the
*verified token*, never from the body.

**6 — Service, inside `@Transactional`.**

```java
Category category = resolveCategory(request.categoryId(), userId, request.type());
```

`CategoryService.requireUsable` checks three things at once: the category is
visible to this user (ownership is in the `WHERE` clause), it is not archived,
and its type agrees with the transaction's. The last one matters because an
EXPENSE filed under an INCOME category would make the breakdown report disagree
with the income/expense totals.

```java
Transaction transaction = Transaction.manual(userId, request.type(), request.amount(), …);
```

The factory computes `dedupeHash` from the normalised field values. Doing it in
the constructor means the hash can never drift from the row it describes.

**7 — Repository.** `save()` on a new entity makes Hibernate issue the INSERT.

**8 — Cache invalidation.**

```java
cacheEvictor.evictUser(userId, ReportService.REPORT_CACHES);
```

Reports are derived entirely from transactions, so a cached report that survives
a write is visibly wrong. `UserCacheEvictor` SCANs `finsight::report-*::7:*` and
deletes. If Redis is down it logs and returns 0 — the write still succeeds.

**9 — Response.** `201` with a `Location` header.

**10 — Unwinding.** Transaction commits. `RequestLoggingFilter` logs method,
path, status, duration — and deliberately *not* the body, which contains the
user's financial detail.

---

## Flow 2 — Importing a large CSV

```mermaid
sequenceDiagram
    autonumber
    participant C as Client
    participant IC as ImportController
    participant IS as ImportService
    participant P as CsvTransactionParser
    participant OR as OutboxRecorder
    participant DB as PostgreSQL
    participant OP as OutboxPublisher
    participant K as Kafka
    participant EC as ImportEventConsumer
    participant IP as ImportProcessor

    C->>IC: POST /api/v1/imports (multipart)
    IC->>IS: upload(userId, file)
    IS->>IS: validate size, content type, extension
    IS->>IS: sha256(bytes) → checksum
    IS->>P: parse(content, maxRows)
    P-->>IS: CsvParseResult(rows, errors)

    rect rgb(245,245,245)
        note over IS,DB: ONE transaction — this is the whole point
        IS->>DB: INSERT import_jobs (status=ACCEPTED)
        IS->>DB: INSERT import_payloads
        IS->>OR: recordImportRequested(...)
        OR->>DB: INSERT outbox_events (published_at = NULL)
    end

    IS-->>IC: ImportJobResponse(ACCEPTED)
    IC-->>C: 202 Accepted + Location

    note over OP: later, @Scheduled
    OP->>DB: SELECT … WHERE published_at IS NULL FOR UPDATE SKIP LOCKED
    OP->>K: send(topic, key=jobId, payload) — synchronous
    K-->>OP: ack
    OP->>DB: UPDATE outbox_events SET published_at = now()

    K->>EC: deliver record
    rect rgb(245,245,245)
        note over EC,DB: ONE transaction
        EC->>DB: INSERT processed_events … ON CONFLICT DO NOTHING
        alt returned 0 — already processed
            EC-->>K: return (commit offset, do nothing)
        else returned 1 — claim won
            EC->>IP: processJob(jobId, userId, maxRows)
            IP->>DB: SELECT import_payloads
            IP->>DB: SELECT existing external_refs (one IN query)
            IP->>DB: INSERT transactions (batched)
            IP->>DB: UPDATE import_jobs SET status=COMPLETED, counters
            IP->>DB: DELETE import_payloads
        end
    end
    EC-->>K: commit offset
```

### The decisions worth noticing

**Parse before persisting.** A structurally broken file becomes a clean `400`,
not a `FAILED` job row the user has to go and look at.

**Three writes, one transaction.** The job, the payload and the outbox event
commit together. There is no state where a job waits forever for an event that
was never recorded. This is the dual-write problem, solved by not doing a dual
write.

**202, not 200.** Nothing has been inserted yet. Returning 200 would tell the
client the work is done when it is merely queued.

**The publisher sends synchronously.**

```java
kafkaTemplate.send(topic, key, payload).get(10, TimeUnit.SECONDS);
event.markPublished();
```

An asynchronous send would let `published_at` commit before the broker accepted
the record. Waiting for the ack keeps the two views in agreement.

**The residual window is real.** If the process dies between a successful send
and the commit of `published_at`, the next pass republishes. That is
at-least-once delivery, and claiming otherwise would be wrong.

**The consumer claims atomically.**

```sql
INSERT INTO processed_events (event_id, consumer_group, processed_at)
VALUES (?, ?, now()) ON CONFLICT (event_id, consumer_group) DO NOTHING
```

Returns 1 for the winner, 0 for a redelivery. A separate `existsById()` followed
by `save()` would let two threads both see "not processed". The claim is in the
same transaction as the work, so a failure rolls both back and a retry is free to
try again.

**Row-level idempotency underneath.** Even with the ledger bypassed entirely,
`sha256(fileChecksum + ':' + rowNumber)` under a unique index means the rows
cannot be inserted twice. Two independent mechanisms, because this is financial
data.

---

## Flow 3 — Reading a cached report

```mermaid
sequenceDiagram
    autonumber
    participant C as Client
    participant RC as ReportController
    participant Proxy as Spring cache proxy
    participant R as Redis
    participant RS as ReportService
    participant DB as PostgreSQL

    C->>RC: GET /reports/summary?from=…&to=…
    RC->>Proxy: summary(7, from, to)
    Proxy->>R: GET finsight::report-summary::7:summary:2026-03-01:2026-03-31

    alt hit
        R-->>Proxy: JSON
        Proxy-->>RC: SummaryResponse (method never ran)
    else miss
        R-->>Proxy: nil
        Proxy->>RS: invoke
        RS->>DB: SELECT SUM(CASE WHEN type='INCOME' …), COUNT(*) …
        DB-->>RS: PeriodTotals
        RS-->>Proxy: SummaryResponse
        Proxy->>R: SETEX … 600
    else Redis unreachable
        R--xProxy: connection refused
        Proxy->>Proxy: CacheErrorHandler logs and swallows
        Proxy->>RS: invoke (fall through to PostgreSQL)
        RS-->>Proxy: SummaryResponse
    end

    Proxy-->>RC: SummaryResponse
    RC-->>C: 200 OK
```

**The key.**

```
finsight::report-summary::7:summary:2026-03-01:2026-03-31
└ prefix ┘└── cache ───┘└u┘└──── derived from arguments ───┘
```

The user id comes immediately after the cache name. Two consequences:

1. No key a second user could compute collides with the first's entry.
2. A user's entries form a single prefix, so `UserCacheEvictor` can drop exactly
   them with one SCAN. If the dates came first, per-user eviction would mean
   scanning the whole keyspace, and the realistic alternative would be
   `cache.clear()` — throwing away every other user's entries on every write.

**One query, not two.**

```sql
SELECT SUM(CASE WHEN type = 'INCOME'  THEN amount END),
       SUM(CASE WHEN type = 'EXPENSE' THEN amount END),
       COUNT(*)
FROM transactions
WHERE user_id = ? AND txn_date >= ? AND txn_date <= ?
```

Both directions in a single pass over `idx_transactions_user_type_date`. No
`ELSE` branch is needed — `CASE` yields NULL for the other type and `SUM` skips
NULLs. The resulting NULL for an empty period is normalised to zero once, in
`PeriodTotals`' compact constructor, so no caller needs a null check.

**Fail-open.** `CacheErrorHandler` swallows Redis faults. A cache outage becomes
slower reads, never failed ones. The cost is that a failed *eviction* leaves data
stale until the TTL expires — which is why the report TTL is a deliberately short
10 minutes.

---

## Transaction boundaries and rollback

```java
@Transactional
public ImportJobResponse upload(Long userId, MultipartFile file) { … }
```

`@Transactional` is an **AOP proxy**. The caller holds the proxy; the proxy opens
a transaction, invokes the real method, and commits or rolls back.

**Rollback rules.** Spring rolls back on unchecked exceptions
(`RuntimeException`, `Error`) and commits on checked ones. Every exception in
this application extends `RuntimeException` precisely so a failing business rule
aborts its transaction with no `rollbackFor` tuning.

**The self-invocation trap.** Calling `this.transactionalMethod()` from inside
the same class bypasses the proxy entirely, so the annotation does nothing. Where
it matters here the call crosses a bean boundary (`ImportService` →
`ImportProcessor`), so the proxy is involved.

**A rollback does not undo a Kafka send.** It is the reason the outbox exists.

**A rollback does not undo a cache write** either — which is why
`RedisCacheManager` is built `.transactionAware()`, deferring cache writes to
after commit.
