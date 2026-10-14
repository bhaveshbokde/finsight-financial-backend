package com.finsight.common.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Deletes every cache entry belonging to one user.
 *
 * <h2>Why this exists</h2>
 * {@code @CacheEvict} can remove one known key or the entire cache. Neither fits
 * here. Report cache keys embed a date range, so the set of keys for a user is
 * unbounded and cannot be enumerated in an annotation; and clearing the whole
 * cache after every transaction write would throw away every <em>other</em> user's
 * entries, turning a per-user invalidation into a global one.
 *
 * <p>Because this application prefixes Redis keys as
 * {@code finsight::<cacheName>::<userId>:<rest>}, a user's entries form a single
 * key prefix and can be removed with one SCAN + DEL pass.
 *
 * <h2>SCAN, not KEYS</h2>
 * {@code KEYS pattern} is O(N) over the whole keyspace and blocks the single-threaded
 * Redis server for the duration. {@code SCAN} returns in bounded batches and lets
 * other commands interleave. The trade-off is that SCAN gives no snapshot guarantee —
 * a key written during the scan may be missed. That is acceptable here: a missed
 * entry expires on its TTL, and the same write that created it will evict again.
 *
 * <h2>Fail-open</h2>
 * Every operation is wrapped: if Redis is unreachable, eviction is logged and
 * skipped rather than failing the user's write. The cost is bounded staleness —
 * at most the cache TTL (10 minutes for reports). Losing a cache must never lose a
 * transaction. See docs/09-REDIS-AND-CACHING.md.
 */
@Component
public class UserCacheEvictor {

    private static final Logger log = LoggerFactory.getLogger(UserCacheEvictor.class);

    /** Matches {@code RedisCacheConfig.KEY_PREFIX}. */
    public static final String KEY_PREFIX = "finsight::";

    private static final int SCAN_BATCH = 256;

    /**
     * {@link ObjectProvider} rather than a direct dependency so the application
     * still starts when no Redis connection factory is present — for example in a
     * slice test, or when caching is switched off.
     */
    private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;

    public UserCacheEvictor(ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
        this.redisTemplateProvider = redisTemplateProvider;
    }

    /**
     * Removes every entry of {@code cacheName} whose key starts with {@code userId:}.
     *
     * @return the number of keys deleted; 0 when caching is unavailable
     */
    public long evictUser(String cacheName, Long userId) {
        StringRedisTemplate template = redisTemplateProvider.getIfAvailable();
        if (template == null || userId == null) {
            return 0;
        }

        String pattern = KEY_PREFIX + cacheName + "::" + userId + ":*";
        try {
            List<String> doomed = new ArrayList<>();
            ScanOptions options = ScanOptions.scanOptions().match(pattern).count(SCAN_BATCH).build();
            try (Cursor<String> cursor = template.scan(options)) {
                while (cursor.hasNext()) {
                    doomed.add(cursor.next());
                }
            }
            if (doomed.isEmpty()) {
                return 0;
            }
            Long deleted = template.delete(doomed);
            log.debug("Evicted {} entries from cache '{}' for user id={}", deleted, cacheName, userId);
            return deleted == null ? 0 : deleted;
        } catch (RuntimeException ex) {
            // Deliberately broad: a cache outage, a timeout and a serialization
            // fault must all degrade to "stale data for up to one TTL", never to a
            // failed write. The reason is logged so the outage is still visible.
            log.warn("Cache eviction skipped for cache='{}' user id={}: {}",
                    cacheName, userId, ex.toString());
            return 0;
        }
    }

    /** Evicts the same user across several caches — used after a transaction write. */
    public void evictUser(Long userId, String... cacheNames) {
        for (String cacheName : cacheNames) {
            evictUser(cacheName, userId);
        }
    }
}
