# 09 — Redis and Caching

> **Verification status: verified against a live Redis.** All 11 `RedisCacheIT`
> tests pass, and the populate → hit → evict → fresh-read cycle was also checked
> by hand against the running application.
>
> Running it for real found a serious bug that the tests had hidden: **every
> cache *hit* failed**, while every miss silently succeeded. Values were stored
> without type information, so deserialization produced a `LinkedHashMap` the
> caching proxy could not cast back to the record. Only one test re-read a key,
> which is the sole reason it surfaced. The fix is described under
> *Serialization* below — and it is why this document now says one cache per
> value type.

---

## What is cached, and what is not

| Endpoint | Cache | TTL | Why |
|---|---|---|---|
| `GET /reports/summary` | `report-summary` | 10 min | Aggregate over many rows; few distinct shapes; read-heavy |
| `GET /reports/monthly` | `report-monthly` | 10 min | Same, plus densification work |
| `GET /reports/categories` | `report-categories` | 10 min | Grouping over many rows |
| `GET /categories` | `categories` | 30 min | Changes rarely, read on nearly every screen |
| `GET /transactions` | — | — | Unbounded filter space; near-zero hit rate |
| `GET /transactions/{id}` | — | — | Already a single primary-key lookup |
| `GET /budgets` | — | — | Depends on live spend; evicted on every transaction write anyway |
| Anything under `/auth` | — | — | Caching an identity decision is how you serve the wrong user's session |

**The rule applied:** cache things that are *expensive to compute*, *frequently
read* and have a *small number of distinct shapes*. The transaction list fails
the third test badly — any date range, any amount window, any search term — so
the hit rate would be near zero while the memory cost and the invalidation
surface would be large.

The budget endpoint is interesting: it is *not* cached, but every transaction
write evicts the report cache anyway, and budget evaluation reuses the report
queries. So the expensive part is cached one level down.

---

## Key layout

```
finsight::report-summary::7:summary:2026-03-01:2026-03-31
│         │              │ │
│         │              │ └─ derived from the method arguments
│         │              └─── userId
│         └────────────────── cache name (one per value type)
└──────────────────────────── global prefix
```

```java
.computePrefixWith(cacheName -> "finsight::" + cacheName + "::")
```

```java
@Cacheable(cacheNames = CACHE_REPORT_SUMMARY, key = "#userId + ':summary:' + #from + ':' + #to")
```

### Why the user id comes first

Two properties follow, and both are load-bearing.

**1. No cross-user leakage.** There is no key a second user could compute that
collides with the first's entry. For a cache sitting in front of per-user
financial data this is the single most important requirement.

`RedisCacheIT.identicalQueryDifferentUser` issues the same date range as two
different users and asserts each sees only their own figure.

**2. Per-user invalidation is possible at all.** A user's entries form a single
key prefix, so they can be dropped with one SCAN. If the dates came first,
dropping one user's entries would mean scanning the whole keyspace and filtering
— and the realistic alternative would be `cache.clear()`, which discards every
*other* user's entries on every write and makes the cache useless under load.

### Every argument that changes the answer is in the key

```java
@Cacheable(key = "#userId + ':all:' + #includeArchived")
public List<CategoryResponse> list(Long userId, boolean includeArchived)
```

Omitting `includeArchived` would let an "include archived" response be served to
a later "exclude archived" request. Any argument that changes the result must
appear in the key — a mistake that is easy to make and produces a bug that looks
like data corruption.

---

## Serialization

```java
.serializeKeysWith(… new StringRedisSerializer())
.serializeValuesWith(… new Jackson2JsonRedisSerializer<>(mapper, valueType))
```

**JSON, not JDK serialization.** Three reasons:

1. **Version brittleness.** JDK serialization ties every cached value to its
   exact class version. Adding a field invalidates the whole cache with an
   `InvalidClassException` at read time — during a rolling deploy, on the new
   nodes only.
2. **Security.** Deserializing untrusted bytes with JDK serialization is a
   well-known remote-code-execution vector.
3. **Debuggability.** `redis-cli GET finsight::report-summary::7:summary:…`
   returns readable JSON. That matters enormously when investigating a staleness
   report.

### One cache per value type

This is the part that running it for real forced.

Spring's cache abstraction deserializes a value **without telling the serializer
what type to produce**. With plain JSON and no type information,
`GenericJackson2JsonRedisSerializer` can only return a `LinkedHashMap`, which the
caching proxy then cannot cast to the method's return type. So **every hit fails
while every miss silently succeeds** — the cache looks healthy right up until
something reads from it.

Jackson's default typing is the usual answer, and it **does not work here**:

```json
{"totalIncome":["java.math.BigDecimal",0.00], …}   // no @class on the root
```

`DefaultTyping.NON_FINAL` skips final types, and **records are implicitly
final** — so the marker lands on the `BigDecimal` fields and never on the record
itself.

The fix is to bind each cache to one concrete type and use
`Jackson2JsonRedisSerializer`, which is told the target type up front. Hence
`report-summary`, `report-monthly`, `report-categories` and `categories` rather
than a single `reports` cache. This is better than making default typing work:

- type safety is explicit and visible in `RedisCacheConfig.cacheManager`;
- stored JSON stays clean — no `@class` noise;
- **default typing stays off entirely**, so the Jackson gadget-chain
  deserialization problem is structurally out of reach rather than merely
  constrained by an allowlist.

The cost: adding a cached return type means declaring a cache for it — a
deliberate one-line act rather than something that silently half-works.

A **separate `ObjectMapper`** from the web one, because it needs different
settings: field visibility (cached records have no setters) and `JavaTimeModule`
so `LocalDate` and `Instant` round-trip as ISO strings rather than failing to
serialise.

---

## TTLs

| Cache | TTL | Reasoning |
|---|---|---|
| `report-summary` · `report-monthly` · `report-categories` | 10 min | Change with every transaction write. The TTL is the **backstop for a lost eviction**, so it is deliberately short. |
| `categories` | 30 min | Changes rarely; a stale category list is low-impact |

A TTL is not the primary invalidation mechanism — eviction is. It is the bound on
how wrong things can get if an eviction is lost to a Redis outage. Ten minutes is
chosen so that a user who adds a transaction during an outage sees correct
figures within ten minutes at worst.

---

## Invalidation

### What evicts what

| Write | Evicts |
|---|---|
| Create / update / delete a transaction | that user's three report caches |
| CSV import (sync or async) | that user's three report caches |
| Create / update / delete a budget | that user's three report caches |
| Create / update / archive a category | that user's `categories` |

Callers evict through the shared constant `ReportService.REPORT_CACHES` rather
than naming caches individually, so adding a report cache cannot leave a
stale-data bug behind in three other services.

### Why not `@CacheEvict`

`@CacheEvict` can remove **one known key** or **the entire cache**. Neither fits.

Report keys embed a date range, so the set of keys for a user is unbounded and
cannot be enumerated in an annotation. Clearing the entire cache would discard
every other user's entries on every write — converting a per-user invalidation
into a global one.

So `UserCacheEvictor` does it by prefix:

```java
String pattern = KEY_PREFIX + cacheName + "::" + userId + ":*";
try (Cursor<String> cursor = template.scan(ScanOptions.scanOptions()
        .match(pattern).count(256).build())) {
    while (cursor.hasNext()) doomed.add(cursor.next());
}
template.delete(doomed);
```

### SCAN, not KEYS

`KEYS pattern` is **O(N) over the whole keyspace** and blocks the single-threaded
Redis server for the duration. On a large keyspace that is a visible outage for
every other client — a genuinely dangerous command in production.

`SCAN` returns in bounded batches and lets other commands interleave. The
trade-off is no snapshot guarantee: a key written during the scan may be missed.
That is acceptable here because a missed entry expires on its TTL, and the same
write that created it will evict again.

### Transaction awareness

```java
RedisCacheManager.builder(connectionFactory)
    …
    .transactionAware()
```

Defers cache writes until after the database transaction commits. Without it, a
transaction that rolls back would still have written its cache entries — leaving
the cache describing data the database never committed. That is the worst class
of cache bug, because it survives the failure that should have prevented it.

---

## Fail-open

```java
@Bean
public CacheErrorHandler cacheErrorHandler() {
    return new CacheErrorHandler() {
        @Override public void handleCacheGetError(RuntimeException e, Cache cache, Object key) {
            log.warn("Cache GET failed (cache={}, key={}); falling back to the database: {}",
                     cache.getName(), key, e.toString());
        }
        …
    };
}
```

**Documented behaviour when Redis is unavailable:**

| Operation | Behaviour | Cost |
|---|---|---|
| Cache **get** fails | Falls through to PostgreSQL | Slower read |
| Cache **put** fails | Response returned normally, nothing cached | Next read is also slow |
| Cache **evict** fails | Logged at **ERROR** | **Stale data for up to the TTL** |
| Redis never starts | `UserCacheEvictor` returns 0; `@Cacheable` always misses | Reports read PostgreSQL every time |

The default `SimpleCacheErrorHandler` **rethrows**, which would turn a cache
outage into a complete API outage — inverting the entire reason for having a
cache. Losing a cache must never lose a transaction.

Failures are logged rather than silently swallowed, so an outage is visible both
in the logs and in the Actuator Redis health indicator. The eviction failure is
logged at ERROR rather than WARN because it is the only one with a correctness
consequence.

### The honest cost

Fail-open trades correctness for availability in exactly one window: if a write
succeeds while Redis is unreachable, the stale entry survives until its TTL. For
a personal finance dashboard, a figure that is up to ten minutes behind is
acceptable; a 500 on every report is not.

**It would not be acceptable** for, say, an account balance used to authorise a
payment. There, the right answer is to fail closed, or not to cache at all.

---

## Cache stampede

Not mitigated, and worth being honest about.

When a popular entry expires, every concurrent request for it misses and runs the
query. For a per-user cache this is bounded by one user's concurrency — typically
one or two requests — so it is not a real risk here.

It **would** matter for a shared cache entry. The standard mitigations are a
per-key lock so only one caller recomputes, or probabilistic early expiry.
Neither is implemented, because neither is needed at this shape.

---

## Operating it

```bash
sudo docker compose up -d redis
redis-cli ping                                  # PONG

redis-cli KEYS 'finsight::*'                    # fine on a dev box, never in production
redis-cli --scan --pattern 'finsight::*'        # the safe equivalent

redis-cli GET 'finsight::report-summary::7:summary:2026-03-01:2026-03-31' | jq
redis-cli TTL 'finsight::report-summary::7:summary:2026-03-01:2026-03-31'

redis-cli INFO stats | grep -E 'keyspace_hits|keyspace_misses'
redis-cli INFO memory | grep used_memory_human
```

Drop one user's entries by hand:

```bash
redis-cli --scan --pattern 'finsight::report-*::7:*' | xargs -r redis-cli DEL
```

### Compose configuration

```yaml
command: >
  redis-server --appendonly yes --maxmemory 256mb --maxmemory-policy allkeys-lru
```

`allkeys-lru` is the correct policy **because this Redis is a pure cache**: every
entry is reconstructible from PostgreSQL, so evicting the least-recently-used key
under memory pressure is exactly right. The default `noeviction` would start
refusing writes instead — turning a full cache into a stream of errors.

If this Redis ever also held something non-reconstructible (a session store, a
rate-limit counter), `allkeys-lru` would silently discard it. Then it would need
a separate instance or `volatile-lru`.

---

## Verifying the behaviour yourself

```bash
# 1. Populate
curl -s "http://localhost:8080/api/v1/reports/summary?from=2026-03-01&to=2026-03-31" \
  -H "Authorization: Bearer $TOKEN" > /dev/null
redis-cli --scan --pattern 'finsight::report-*'

# 2. Confirm the value is readable JSON
redis-cli GET "$(redis-cli --scan --pattern 'finsight::report-*' | head -1)" | jq

# 3. Write, then confirm the entry is gone
curl -s -X POST http://localhost:8080/api/v1/transactions \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"type":"EXPENSE","amount":10.00,"txnDate":"2026-03-15","description":"Cache check"}' >/dev/null
redis-cli --scan --pattern 'finsight::report-*'      # empty for that user

# 4. Confirm fail-open: stop Redis, the API still works
sudo docker compose stop redis
curl -s "http://localhost:8080/api/v1/reports/summary?from=2026-03-01&to=2026-03-31" \
  -H "Authorization: Bearer $TOKEN" | jq        # still 200, just slower
sudo docker compose start redis
```

Step 4 is the one worth running — it is the difference between a cache and a
dependency.

### Tests

`RedisCacheIT` covers: population, the `userId` key prefix, JSON values, a
positive TTL, two users getting separate entries, the same query from a different
user returning that user's own data, write-evicts-own-cache, read-after-write
freshness, **one user's write not evicting another's**, import eviction, and
different date ranges getting different entries.

It skips with a message when Redis is unreachable:

```java
assumeTrue(redisAvailable(),
        "Redis is not reachable — skipping. Start it with: sudo docker compose up -d redis");
```

A skip is honest. A test that silently passes without Redis would be worse than
no test at all.

---

## What a production deployment would add

| | |
|---|---|
| Authentication | `requirepass`, or Redis ACLs. The compose file has none and binds to loopback only. |
| TLS | Redis traffic is plaintext |
| High availability | Sentinel or Cluster; a single node is a single point of *performance* failure (not correctness, thanks to fail-open) |
| Memory sizing | `maxmemory` set from measured working-set size, not a guessed 256 MB |
| Eviction alerting | Alert on `evicted_keys` rising — it means the cache is undersized |
| Hit-ratio monitoring | A falling hit ratio signals a key-design problem, often a newly-added argument missing from the key |
