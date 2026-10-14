package com.finsight.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.finsight.category.dto.CategoryResponse;
import com.finsight.category.service.CategoryService;
import com.finsight.report.dto.CategoryBreakdownResponse;
import com.finsight.report.dto.MonthlySeriesResponse;
import com.finsight.report.dto.SummaryResponse;
import com.finsight.report.service.ReportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.interceptor.SimpleCacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Redis-backed caching.
 *
 * <h2>Key layout</h2>
 * <pre>
 *   finsight::&lt;cacheName&gt;::&lt;userId&gt;:&lt;rest-of-key&gt;
 *   finsight::reports::7:summary:2026-03-01:2026-03-31
 *   finsight::categories::7:all:false
 * </pre>
 * Two properties follow from putting the user id immediately after the cache name:
 * <ul>
 *   <li><strong>No cross-user leakage.</strong> Every key is namespaced by owner,
 *       so there is no key a second user could compute that collides with the
 *       first's entry. This is the single most important requirement for a cache in
 *       front of per-user financial data.</li>
 *   <li><strong>Per-user invalidation is possible.</strong>
 *       {@link com.finsight.common.cache.UserCacheEvictor} deletes by the
 *       {@code finsight::<cache>::<userId>:} prefix. If the dates came first,
 *       dropping one user's entries would mean scanning the whole keyspace.</li>
 * </ul>
 *
 * <h2>Serialization</h2>
 * JSON, not JDK serialization. JDK serialization ties every cached value to its
 * exact class version — adding a field invalidates the whole cache with a
 * {@code ClassNotFoundException} or {@code InvalidClassException} at read time —
 * and deserializing untrusted bytes is a well-known remote-code-execution vector.
 * JSON entries are also readable with {@code redis-cli}, which matters when
 * debugging a staleness report.
 *
 * <h2>One cache per value type, and why</h2>
 * Spring's cache abstraction deserializes a value without telling the serializer
 * what type to produce. With plain JSON and no type information,
 * {@code GenericJackson2JsonRedisSerializer} can only return a
 * {@code LinkedHashMap}, which the caching proxy then cannot cast to the method's
 * return type — so <em>every cache hit fails while every miss silently
 * succeeds</em>. That is a nasty failure mode: the cache looks like it is working
 * right up until something actually reads from it.
 *
 * <p>Jackson's default typing is the usual answer, but it does not work here:
 * {@code DefaultTyping.NON_FINAL} skips final types, and <strong>records are
 * implicitly final</strong>, so the {@code @class} marker lands on the
 * {@code BigDecimal} fields and never on the record itself.
 *
 * <p>So each cache is instead bound to one concrete type and uses
 * {@link Jackson2JsonRedisSerializer}, which is told the target type up front.
 * This is better than making default typing work:
 * <ul>
 *   <li>Type safety is explicit and visible in {@link #cacheManager}.</li>
 *   <li>Stored JSON stays clean and readable — no {@code @class} noise.</li>
 *   <li>Default typing stays off entirely, so the Jackson gadget-chain
 *       deserialization problem is structurally out of reach rather than merely
 *       constrained by an allowlist.</li>
 * </ul>
 * The cost is that adding a cached return type means declaring a cache for it —
 * a deliberate, one-line act rather than something that silently half-works.
 *
 * <h2>Fail-open</h2>
 * {@link #cacheErrorHandler()} swallows Redis faults. A cache miss caused by an
 * outage falls through to PostgreSQL and the request succeeds, more slowly. The
 * alternative — propagating the error — would turn a cache outage into a complete
 * API outage, which inverts the reason for having a cache. The trade-off is that a
 * failed <em>eviction</em> leaves data stale until its TTL expires, which is why
 * the report TTL is deliberately short.
 */
@Configuration
@EnableCaching
@ConditionalOnProperty(prefix = "finsight.cache", name = "enabled", havingValue = "true",
                       matchIfMissing = true)
public class RedisCacheConfig {

    private static final Logger log = LoggerFactory.getLogger(RedisCacheConfig.class);

    /** Must match {@link com.finsight.common.cache.UserCacheEvictor#KEY_PREFIX}. */
    public static final String KEY_PREFIX = "finsight::";

    private final CacheSettings settings;

    public RedisCacheConfig(CacheSettings settings) {
        this.settings = settings;
    }

    /**
     * One cache per value type.
     *
     * <p>Each cache is bound to a concrete {@link JavaType}, so its serializer
     * knows exactly what to reconstruct on a hit. That is what makes
     * {@link #typedConfiguration} able to avoid default typing entirely — see the
     * class-level note.
     */
    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        ObjectMapper mapper = cacheObjectMapper();
        var types = mapper.getTypeFactory();

        Map<String, RedisCacheConfiguration> perCache = new HashMap<>();

        // Reports change with every transaction write, so a short TTL bounds how
        // stale they can get if an eviction is ever lost to a Redis outage.
        perCache.put(ReportService.CACHE_REPORT_SUMMARY,
                typedConfiguration(settings.reportTtl(), mapper,
                        types.constructType(SummaryResponse.class)));
        perCache.put(ReportService.CACHE_REPORT_MONTHLY,
                typedConfiguration(settings.reportTtl(), mapper,
                        types.constructType(MonthlySeriesResponse.class)));
        perCache.put(ReportService.CACHE_REPORT_CATEGORIES,
                typedConfiguration(settings.reportTtl(), mapper,
                        types.constructType(CategoryBreakdownResponse.class)));

        // Categories change rarely and are small; a longer TTL is safe.
        perCache.put(CategoryService.CACHE_CATEGORIES,
                typedConfiguration(settings.categoryTtl(), mapper,
                        types.constructCollectionType(List.class, CategoryResponse.class)));

        return RedisCacheManager.builder(connectionFactory)
                // An unknown cache name is a programming error, not something to
                // silently create with guessed settings — every cache this
                // application uses is declared above.
                .cacheDefaults(typedConfiguration(settings.reportTtl(), mapper,
                        types.constructType(Object.class)))
                .withInitialCacheConfigurations(perCache)
                // Without this, a transaction that rolls back would still have
                // written its cache entries — leaving the cache describing data the
                // database never committed.
                .transactionAware()
                .build();
    }

    /**
     * Builds a cache configuration whose values serialise as plain JSON of one
     * known type.
     *
     * <p>{@link Jackson2JsonRedisSerializer} is given the target type up front, so
     * a cache hit deserialises straight back into the record. The alternative —
     * {@code GenericJackson2JsonRedisSerializer} without type information —
     * returns a {@code LinkedHashMap} that the caching proxy cannot cast, so every
     * hit fails while every miss succeeds.
     */
    private RedisCacheConfiguration typedConfiguration(Duration ttl, ObjectMapper mapper,
                                                        JavaType valueType) {
        return RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(ttl)
                // A null result means "no data", which is a perfectly cacheable
                // answer — but caching it here would make a @Cacheable miss
                // indistinguishable from a cached null for Spring's own bookkeeping.
                .disableCachingNullValues()
                .computePrefixWith(cacheName -> KEY_PREFIX + cacheName + "::")
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new Jackson2JsonRedisSerializer<>(mapper, valueType)));
    }

    /**
     * A dedicated {@link ObjectMapper} for cache values.
     *
     * <p>Separate from the web one because it needs different settings: field
     * visibility (cached records have no setters) and {@code JavaTimeModule} so
     * {@code LocalDate} and {@code Instant} round-trip as ISO strings rather than
     * failing to serialise.
     *
     * <p>Default typing is deliberately <strong>not</strong> enabled — see the
     * class-level note. Each cache supplies its value type explicitly instead.
     */
    private ObjectMapper cacheObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        mapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        return mapper;
    }

    /**
     * Makes every cache operation non-fatal.
     *
     * <p>{@link SimpleCacheErrorHandler}, the default, rethrows — so a Redis outage
     * would surface as a 500 on endpoints that merely <em>wanted</em> a cache.
     * Each failure is logged, so an outage is visible in the logs and in the
     * Actuator Redis health indicator rather than being silently absorbed.
     */
    @Bean
    public CacheErrorHandler cacheErrorHandler() {
        return new CacheErrorHandler() {

            @Override
            public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
                log.warn("Cache GET failed (cache={}, key={}); falling back to the database: {}",
                        cache.getName(), key, exception.toString());
            }

            @Override
            public void handleCachePutError(RuntimeException exception, Cache cache, Object key,
                                            Object value) {
                log.warn("Cache PUT failed (cache={}, key={}); the response is unaffected: {}",
                        cache.getName(), key, exception.toString());
            }

            @Override
            public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
                // The one genuinely risky case: the entry stays until its TTL.
                log.error("Cache EVICT failed (cache={}, key={}); data may be stale for up to the TTL: {}",
                        cache.getName(), key, exception.toString());
            }

            @Override
            public void handleCacheClearError(RuntimeException exception, Cache cache) {
                log.error("Cache CLEAR failed (cache={}): {}", cache.getName(), exception.toString());
            }
        };
    }
}
