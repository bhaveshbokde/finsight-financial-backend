# 06 — Class-by-Class Guide

What each class is for, and the one decision inside it worth knowing. Classes
whose behaviour is obvious from the name are listed in tables; the ones with real
reasoning get prose.

---

## `com.finsight`

### `FinSightApplication`

The entry point. `@SpringBootApplication` is a meta-annotation combining
`@Configuration`, `@EnableAutoConfiguration` and `@ComponentScan` — scanning
starts at this package and walks every sub-package, which is why feature packages
need no explicit registration.

Also enables JPA auditing, transaction management and scheduling.
`@EnableCaching` is deliberately **not** here — it lives on `RedisCacheConfig` so
caching can be switched off wholesale via `finsight.cache.enabled=false` without
touching this class.

---

## `common` — the shared kernel

### `error/ErrorCode`

An enum where each constant carries its own `HttpStatus`:

```java
VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "One or more fields are invalid"),
CONCURRENT_MODIFICATION(HttpStatus.CONFLICT, "The resource was modified by another request; retry"),
```

Status selection becomes a property of the error rather than an if-else chain in
the handler. Clients branch on the stable `code`; the human-readable message can
be reworded freely.

### `error/GlobalExceptionHandler`

`@RestControllerAdvice` mapping every exception to the single `ApiError` shape.
Spring picks the handler whose parameter type most closely matches, so specific
handlers always beat the `Exception` fallback.

Logging policy, which matters more than it looks:

- **4xx at WARN, without a stack trace.** They are expected traffic. Ten thousand
  bad logins should not produce ten thousand stack traces.
- **5xx at ERROR, with the trace**, keyed by the `traceId` returned to the caller.
- **The stack trace never enters the response.** The caller gets an id to quote.

`handleIntegrity` deserves a note. Services pre-check for duplicates, but the
check and the insert are not atomic — two concurrent requests can both pass. The
database constraint is the real guarantee, and this handler turns the loser's
`DataIntegrityViolationException` into a clean 409 instead of a 500.

> **Important:** exceptions thrown inside the security filter chain never reach
> this class. Filters run before the `DispatcherServlet`, so nothing they throw
> is visible to a `@RestControllerAdvice`. That is why
> `RestAuthenticationEntryPoint` exists.

### `error/ResourceNotFoundException`

Thrown when a resource does not exist **or exists but belongs to someone else**.
Deliberately 404 for both. Returning 403 for the second case would confirm that
id 42 exists, turning every endpoint into an existence oracle. Because every
ownership-scoped lookup is `findByIdAndUserId(...)`, the two cases are genuinely
indistinguishable in the code too.

### `util/Money`

`BigDecimal` helpers. Storage scale 4, display scale 2, `HALF_UP` throughout.

`HALF_UP` rather than `HALF_EVEN` because it is what a user checking the
arithmetic by hand will reproduce: 2.345 → 2.35.

`percentageOf` returns `0.00` when the denominator is zero rather than throwing.
A category with no budget, or a month with no income, is a normal state in a
report — not an exceptional one. Throwing would force every caller into a
try/catch.

### `util/Hashes`

SHA-256 helpers for file checksums, idempotency keys and duplicate fingerprints.

`fingerprint(Object...)` joins parts with `` (unit separator) rather than a
printable character. Joining with `|` would make `("a|b", "c")` and
`("a", "b|c")` hash identically — a field-boundary collision. It also normalises
each part (trim, collapse whitespace, lower-case) so "Big  Bazaar" and
"big bazaar" fingerprint alike.

These are **not** password hashes. Passwords go through BCrypt; a fast digest
would be exactly the wrong tool there.

### `util/DateRanges`

The single place that defines range semantics: **inclusive on both ends**. Also
caps the span at 3660 days, because without that `?from=0001-01-01&to=9999-12-31`
is a one-request table scan any authenticated user could issue repeatedly.

`parseMonth` converts a bad month into a 400 with a useful message rather than
letting a `DateTimeParseException` surface as a 500.

### `domain/AuditableEntity`

`@MappedSuperclass` contributing `created_at`, `updated_at` and `version` without
creating a table or an inheritance hierarchy in the database.

`@Version` gives optimistic locking: Hibernate appends `AND version = ?` to each
UPDATE, and zero matching rows raises an exception that becomes a 409. The right
default for a web application — a pessimistic row lock held across a user's
think-time would serialise unrelated requests.

### `cache/UserCacheEvictor`

Deletes every cache entry belonging to one user.

`@CacheEvict` can remove one known key or the entire cache, and neither fits.
Report keys embed a date range, so a user's key set is unbounded and cannot be
enumerated in an annotation; clearing the whole cache on every write would
discard every *other* user's entries.

Uses `SCAN`, not `KEYS`. `KEYS pattern` is O(N) over the whole keyspace and
blocks single-threaded Redis for the duration. `SCAN` returns in bounded batches.
The trade-off is no snapshot guarantee — a key written mid-scan may be missed —
which is acceptable because it expires on its TTL anyway.

Every operation is wrapped in a broad catch. A Redis outage must cost bounded
staleness, never a failed write.

### `dto/PageResponse<T>`

A stable pagination envelope. Spring's `Page` serialises to a large, unstable
structure that leaks `pageable` and `sort.unsorted` — and newer versions log a
warning about exactly that. Mapping to our own record keeps the public contract
under our control and independent of the Spring Data version.

---

## `auth` and `user`

### `security/JwtService`

Issues and verifies tokens. HS256 with a shared secret.

**Why the algorithm cannot be confused.** Verification is built with
`.verifyWith(SecretKey)`, so JJWT only accepts a MAC algorithm. A token whose
header claims `"alg":"none"` — or an RS256 token where the attacker supplies the
"public key" as the HMAC secret — fails signature verification. The header's
`alg` is never used to select the verification strategy.

**The `typ` claim.** `ACCESS` or `REFRESH`, checked on every verification.
Without it a long-lived refresh token would be accepted as a bearer credential —
a token-confusion bug that converts a 7-day token into a 7-day API key.

**Fail-fast key validation.** A secret shorter than 256 bits throws at
construction, so startup breaks rather than the first login in production.

**A package-private clock-injecting constructor** lets tests exercise expiry by
moving time rather than sleeping.

### `security/JwtAuthenticationFilter`

Turns a valid bearer header into an authenticated `SecurityContext`.

Extends `OncePerRequestFilter` so the work happens exactly once even across a
container `FORWARD` or `ERROR` dispatch — a plain `Filter` can run several times
per request.

A bad token does **not** abort the chain. The filter stashes the reason in a
request attribute and continues; the authorization rules decide. That keeps all
rejection in one place and is why every 401 body is identical.

`MDC.remove("userId")` in a `finally` block. Without it the value leaks to the
next request on the same pooled thread.

### `security/RestAuthenticationEntryPoint` and `RestAccessDeniedHandler`

Produce the 401 and 403 bodies. They exist because `GlobalExceptionHandler`
cannot help inside the filter chain. The entry point reads the attribute set by
the JWT filter, so the response distinguishes an **expired** token (client should
refresh) from an **invalid** one (client should re-authenticate) — which a bare
401 cannot.

401 means "I do not know who you are"; 403 means "I know, and the answer is no".
Returning 401 for the second would send a correctly-authenticated client into a
pointless re-login loop.

### `security/UserPrincipal`

The authenticated caller. A distinct type from the `User` entity on purpose — an
entity is a persistence concern and should not be attached to the security
context, where a stale or lazily-initialised instance could outlive its
persistence context.

Carries the database id, which is what makes per-user isolation cheap:
`principal.getId()` goes straight into `findByIdAndUserId(...)`, so ownership is
a SQL predicate rather than an afterthought.

`fromClaims` rebuilds it without a database round trip — the thing that makes the
filter stateless, with the stated trade-off that a role change takes up to one
TTL to apply.

### `security/DatabaseUserDetailsService`

Used by the **login path only**. Deliberately not used by the JWT filter, which
would add a database query to every authenticated request — exactly the cost a
stateless token exists to avoid.

### `service/AuthService`

The `register` method shows the check-plus-constraint pattern:

```java
if (userRepository.existsByEmailIgnoreCase(email)) throw new DuplicateResourceException(…);
…
try { user = userRepository.saveAndFlush(user); }
catch (DataIntegrityViolationException race) { throw new DuplicateResourceException(…); }
```

The pre-check gives a clean 409 for the ordinary case; the unique index is the
actual guarantee. Checking without the constraint is a race; relying on the
constraint without the check makes the common path throw.

`maskEmail` keeps logs useful without recording full addresses.

### `user/domain/User`

Note what is **absent**: no `@OneToMany` to transactions, categories or budgets.
A user can own tens of thousands of transactions, and a mapped collection invites
someone to call `user.getTransactions()`. Navigation goes the other way, through
repositories that always take a `userId` and a `Pageable`.

`equals` is id-based and returns false for two transient instances; `hashCode` is
constant. That is the standard JPA recipe — `Objects.hash(id)` would change when
the id is assigned, breaking any `HashSet` the entity was already in.

---

## `category`

| Class | Note |
|---|---|
| `Category` | Two flavours in one table; `isEditableBy` / `isUsableBy` encode the visibility rules |
| `CategoryRepository` | "Visible to user X" = X's own + all system, written once here so it cannot drift |
| `CategoryRequest` / `CategoryResponse` | `editable` is computed per viewer |

### `CategoryService`

`requireUsable(categoryId, userId, expectedType)` enforces three things at once:
visibility, not-archived, and type agreement. The type check matters because an
EXPENSE filed under an INCOME category would make the breakdown report disagree
with the income/expense totals — a discrepancy nobody notices in a chart.

`archive` rather than delete: a hard delete would set `category_id` to NULL on
every referencing transaction, silently rewriting the user's history.

---

## `transaction`

### `Transaction`

**Money.** `BigDecimal` / `NUMERIC(19,4)`, always positive, direction from
`type`.

**Date.** `LocalDate`, not an instant. A purchase happens on a calendar day;
storing an instant would make "March spending" depend on the reader's time zone.

**Associations.** `category` is `@ManyToOne(fetch = LAZY)` — `LAZY` stated
explicitly because `@ManyToOne` defaults to `EAGER`, which would issue an extra
SELECT per row on list endpoints. `userId` stays a raw `Long` because the owner
is never navigated to, only compared.

`applyEdit` recomputes `dedupeHash` in the same call that changes the fields, so
the fingerprint can never drift from the values it describes.

### `TransactionRepository`

Extends `JpaSpecificationExecutor` so the filter endpoint builds a dynamic WHERE
clause instead of this interface carrying a combinatorial explosion of
`findByUserIdAndTypeAndTxnDateBetweenAnd…` methods.

```java
@Override
@EntityGraph(attributePaths = "category")
Page<Transaction> findAll(Specification<Transaction> spec, Pageable pageable);
```

The entity graph is the important part: `category` is LAZY and the response DTO
reads its name, so without a fetch plan a 20-row page issues 1 + 20 queries. The
graph turns that into one LEFT JOIN. Safe with paging here because the
association is to-one; a to-*many* fetch with a `Pageable` would force Hibernate
to page in memory.

`findExistingExternalRefs` is the bulk idempotency probe — one `IN` query instead
of 5 000 round trips for a 5 000-row import.

`deleteByImportJob` carries `@Modifying(clearAutomatically = true,
flushAutomatically = true)`. A bulk JPQL DELETE bypasses the first-level cache,
so without those flags an entity loaded earlier in the same transaction would
still look alive after being deleted.

### `TransactionSpecifications`

Builds the predicate with the Criteria API, so every value becomes a bound JDBC
parameter and SQL injection is structurally impossible rather than filtered
against.

`ownedBy` is applied unconditionally and **first**. Ownership is not one filter
among many a caller might omit — it is the invariant that makes everything else
safe.

`escapeLike` escapes `%`, `_` and `\` so a search term containing `%` matches a
literal percent sign instead of becoming a full-table wildcard.

### `TransactionService`

Every method takes a `userId` from the verified token and puts it in the query.
There is no code path that loads by id alone and checks ownership afterwards —
that pattern is one forgotten `if` from an IDOR vulnerability.

`capPageSize` caps at 200. Without it, `?size=1000000` is a one-request denial of
service.

Deliberately **not** cached. The filter space is effectively unbounded, so the
hit rate would be near zero while the memory cost and invalidation surface would
be large. Reports — few shapes, expensive to compute — are what deserve a cache.

---

## `budget`

### `BudgetService`

**Spend is measured, never accumulated.** Nothing maintains a "spent so far"
column; every read recomputes from the month's transactions.

That costs one indexed aggregate query and removes an entire class of bugs: a
counter would need adjusting on create, update (where amount, date *or* category
may change), delete, category archival and import rollback — and any missed path
corrupts it silently with no way to detect it. It also means two concurrent
transaction writes cannot race on a shared counter.

`evaluate` attaches spend to a whole month's budgets in **two queries total**,
regardless of how many budgets there are. Doing it per budget would be N+1 at the
service layer — just as slow as the ORM version and easier to miss in review.

An overall budget reads through `ReportRepository.totals`, the same query the
summary report uses, so the two endpoints can never disagree.

`update` refuses to change the month or the category: that is semantically a
different budget, and allowing it in place would need the same uniqueness checks
as a create while making the audit trail meaningless.

### `BudgetResponse.of`

Where the ON_TRACK / WARNING / EXCEEDED thresholds live, stated once so the API
and the report agree. "At or above" the threshold rather than strictly above, so
a budget sitting exactly on its alert point does alert.

---

## `report`

### `ReportRepository`

Extends the bare `Repository` marker rather than `JpaRepository`: reports never
save or delete, and inheriting twenty mutating methods onto a read-only interface
invites misuse.

```sql
SUM(CASE WHEN t.type = :income  THEN t.amount END),
SUM(CASE WHEN t.type = :expense THEN t.amount END),
COUNT(t)
```

Both directions in one pass. No `ELSE` branch — `CASE` yields NULL for the other
type and `SUM` skips NULLs.

`categoryTotals` uses `LEFT JOIN` so uncategorised transactions survive as a NULL
group. An INNER JOIN would drop them and the breakdown would silently fail to sum
to the period total.

It returns the complete grouped set rather than a `Page`. That is deliberate and
not the usual "load everything and paginate in Java" mistake: the group count is
bounded by the user's category count (tens), and the full set is *required* to
compute each category's percentage share — a database-side `LIMIT` would make
page 2's percentages measured against a different denominator.

### `ReportService`

`monthlySeries` **densifies**: SQL returns nothing for a month with no
transactions, and leaving the client to detect gaps is how charts end up with
misleading lines.

Averages divide by months **requested**, not months with activity. "Average
monthly spend over the last 12 months" means dividing by 12; dividing by the 7
active months would overstate it.

### `dto/PeriodTotals`

The compact constructor substitutes zero for null:

```java
public PeriodTotals {
    totalIncome = totalIncome == null ? BigDecimal.ZERO : totalIncome;
    …
}
```

`SUM()` over no rows is SQL NULL. Handling it here — at the one boundary where
the database's three-valued logic meets Java — means no caller needs a null check
and no response ever contains a null amount.

---

## `imports`

### `csv/CsvTransactionParser`

The most defensive class in the project.

**Header resolved once, not per row.** OpenCSV's `CSVReaderHeaderAware` throws as
soon as any line's column count differs from the header, aborting the whole file
over one ragged line. Resolving positions once and indexing into each row turns
that into a single reported row error while every other row still imports.

**Strict date resolution.** `ResolverStyle.STRICT` with `uuuu` patterns. The
default `SMART` style silently rewrites 31 February as 28 February — filing a
transaction on a date the user never wrote. (`uuuu` rather than `yyyy` because
STRICT rejects year-of-era without an era field.)

**`MM/dd/yyyy` is refused.** Indistinguishable from `dd/MM/yyyy` for twelve days
a month. Ambiguity is resolved by refusing to guess.

**Amounts.** Strips separators, symbols and accounting parentheses; takes the
magnitude because bank exports write expenses as negatives. Rejects values too
large for `NUMERIC(19,4)` here, so one bad row does not become a numeric-overflow
`SQLException` that aborts the whole batch.

**`RowValidationException`** is constructed with `super(message, null, false,
false)` — no writable stack trace. Thousands may be created while parsing a messy
file and none is a programming error; filling in a stack trace is the expensive
part of constructing an exception.

### `service/ImportService`

Owns the request-side concerns. Parses **before** persisting so a broken file is
a clean 400 rather than a FAILED job row.

The synchronous/asynchronous split exists because holding an HTTP connection open
while inserting thousands of rows ties up a request thread, risks a proxy
timeout, and leaves the user with no way to find out what happened if the
connection drops.

`validateUpload` duplicates the servlet container's size limit on purpose. That
limit is configuration someone may raise; a second check in the code that
actually allocates the buffer is cheap defence in depth.

`sanitiseFilename` strips anything path-like. Nothing here opens a file by that
name, but storing an unsanitised attacker-controlled string that a UI later
renders is a trap worth closing at the boundary.

### `service/ImportProcessor`

Where rows become transactions.

**Idempotency:** `externalRef = sha256(fileChecksum + ':' + rowNumber)` under a
partial unique index. Content *and position*, deliberately — keying on content
alone would reject two identical coffees in one file, which is legitimate data.
The stated limit is that the same rows in a different order would import again.

**Counters are assigned, not incremented**, and previous row errors are deleted
before each pass. Without both, a redelivery would double every number.

**Two queries replace N.** One `findAllUsableBy` for the category index, one
`findExistingExternalRefs` for the idempotency probe — instead of two per row.

**The payload is deleted when the job finishes.** The parsed transactions are the
durable result; holding the user's raw statement indefinitely is a data-retention
liability.

`processJob` re-checks that the event's `userId` matches the stored job. An event
is a message from another process, not a trusted assertion about ownership, and
importing one user's rows into another's account would be the worst possible
outcome.

---

## `outbox` and `messaging`

### `outbox/service/OutboxRecorder`

```java
@Transactional(propagation = Propagation.MANDATORY)
```

The single most important line in the package. It **refuses to run** unless a
transaction is already in progress. The entire point of an outbox is that the
event row and the business change commit together; recording in its own
transaction would silently reintroduce the dual write it exists to eliminate.
Making it a loud, immediate failure beats discovering it during an incident.

### `outbox/service/OutboxPublisher`

Claims with `FOR UPDATE SKIP LOCKED`, so several instances can poll concurrently
without duplicating or blocking.

Sends **synchronously** and waits for the broker ack. An asynchronous send would
let `published_at` commit before the broker accepted the record.

`fixedDelayString` rather than `fixedRate`: the delay is measured from the end of
the previous pass, so a slow pass cannot cause passes to pile up.

Failures are caught **per event**, so one bad topic does not stop the batch. A row
exceeding `maxAttempts` is **parked** — it stops being retried and is reported by
the `finsight.outbox.parked` gauge, so a sustained outage is visible rather than
silently eating imports.

### `messaging/config/KafkaConfig`

Retry with exponential backoff, then `DeadLetterPublishingRecoverer`.

Without a dead-letter step a poison pill blocks its partition forever: the
container retries, fails, declines to commit, re-polls the same offset, and no
later message is ever processed.

Deserialisation and validation errors are classified **non-retryable** and go
straight to the DLT. Retrying them burns the whole backoff budget on an outcome
that cannot change — bytes do not become valid on the third attempt.

### `messaging/consumer/ImportEventConsumer`

Claims atomically:

```java
int claimed = processedEventRepository.claim(event.eventId(), consumerGroup);
if (claimed == 0) { /* redelivery — nothing to do */ return; }
```

`INSERT … ON CONFLICT DO NOTHING` rather than `existsById()` then `save()`, which
would let two threads both see "not processed". `ON CONFLICT` rather than a caught
constraint violation, because an exception marks the PostgreSQL transaction
rollback-only and would force a savepoint dance for a completely normal outcome.

The claim shares the work's transaction. Claiming separately would mean a crash
after the claim but before the work leaves the event permanently marked done and
never performed.

### `messaging/idempotency/ProcessedEvent`

Composite key `(eventId, consumerGroup)` — not `eventId` alone, because different
consumer groups are independent subscribers and each must get its own chance.

---

## `config`

| Class | Purpose |
|---|---|
| `SecurityConfig` | The filter chain, password encoder, CORS |
| `RedisCacheConfig` | Key layout, TTLs, JSON serialization, fail-open error handler |
| `OpenApiConfig` | Document metadata and the `bearerAuth` scheme |
| `ObservabilityConfig` | Common meter tags, `TimedAspect` |
| `RequestLoggingFilter` | Trace id and request outcome logging |
| `JwtProperties`, `CsvImportProperties`, `OutboxProperties`, `KafkaSettings`, `CacheSettings` | Immutable `@ConfigurationProperties` records |

### `SecurityConfig` — the four decisions

**CSRF disabled, correctly.** CSRF defends against the browser automatically
attaching an *ambient* credential (a cookie). This API has none: it is stateless
and authenticates only from an `Authorization` header, which a cross-origin page
cannot cause the browser to send. Disabling it here is right; doing so in a
cookie-session application would be a serious mistake.

**Sessions STATELESS.** No `JSESSIONID`; the context is rebuilt from the token
each request. That is what lets several instances serve the same client with no
sticky sessions or shared session store.

**Deny by default.** The chain ends `.anyRequest().authenticated()`. A new
controller is protected the moment it is written; opening it up requires an
explicit line. The reverse default is how endpoints ship unprotected.

**Health is public, the rest of `/actuator` is not.** An orchestrator must be
able to probe liveness without credentials; everything else exposes internals.

### `RedisCacheConfig`

Key layout `finsight::<cache>::<userId>:<rest>`. The user id placement is
load-bearing — see `UserCacheEvictor` above.

JSON, not JDK serialization. JDK serialization ties every cached value to its
exact class version (adding a field invalidates the cache with an
`InvalidClassException` at read time) and deserializing untrusted bytes is a
known RCE vector. Default typing is deliberately **not** enabled, which is what
keeps the Jackson gadget-chain problem out of reach.

`.transactionAware()` defers cache writes until after commit — otherwise a
rolled-back transaction would leave the cache describing data the database never
committed.

`cacheErrorHandler` swallows Redis faults. The default `SimpleCacheErrorHandler`
rethrows, which would turn a cache outage into a complete API outage — inverting
the reason for having a cache.
