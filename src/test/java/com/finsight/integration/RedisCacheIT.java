package com.finsight.integration;

import com.finsight.auth.dto.AuthResponse;
import com.finsight.report.service.ReportService;
import com.finsight.transaction.domain.TransactionType;
import com.finsight.transaction.dto.TransactionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Redis caching: key isolation between users, population, and invalidation on write.
 *
 * <p>Tagged {@code redis} and <strong>skipped</strong> when no Redis is reachable,
 * via {@link org.junit.jupiter.api.Assumptions}. A skip is honest — the suite
 * reports the test as not run rather than passing without having checked
 * anything. Start Redis with {@code sudo docker compose up -d redis} to run it.
 *
 * <p>Not {@code @Transactional}: cache writes happen on commit, so a rolled-back
 * test would never populate the cache. Each test therefore cleans its own keys.
 */
@Tag("redis")
@TestPropertySource(properties = {
        "finsight.cache.enabled=true",
        "spring.cache.type=redis"
})
class RedisCacheIT extends AbstractIntegrationTest {

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    private AuthResponse alice;
    private AuthResponse bob;

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(redisAvailable(),
                "Redis is not reachable on the configured host/port — skipping. "
                        + "Start it with: sudo docker compose up -d redis");

        alice = registerUser("cache-alice");
        bob = registerUser("cache-bob");

        clearFinsightKeys();
    }

    private boolean redisAvailable() {
        if (redisTemplate == null) {
            return false;
        }
        try {
            redisTemplate.hasKey("finsight::probe");
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void clearFinsightKeys() {
        Set<String> keys = redisTemplate.keys("finsight::*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    private Set<String> reportKeys() {
        return redisTemplate.keys("finsight::report-*");
    }

    private void createExpense(AuthResponse user, String amount, LocalDate date) throws Exception {
        mockMvc.perform(post("/api/v1/transactions")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TransactionRequest(
                                TransactionType.EXPENSE, new BigDecimal(amount), date,
                                "Cache test spend", null, null, null))))
                .andExpect(status().isCreated());
    }

    private void fetchSummary(AuthResponse user) throws Exception {
        mockMvc.perform(get("/api/v1/reports/summary")
                        .param("from", "2026-03-01").param("to", "2026-03-31")
                        .header("Authorization", bearer(user)))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------- population --

    @Test
    @DisplayName("a report read populates the cache")
    void readPopulatesCache() throws Exception {
        createExpense(alice, "1000.00", LocalDate.of(2026, 3, 10));

        assertThat(reportKeys()).isEmpty();

        fetchSummary(alice);

        assertThat(reportKeys()).isNotEmpty();
    }

    @Test
    @DisplayName("the key starts with the user id, so eviction can be per user")
    void keyIsPrefixedWithUserId() throws Exception {
        createExpense(alice, "1000.00", LocalDate.of(2026, 3, 10));
        fetchSummary(alice);

        String expectedPrefix = "finsight::" + ReportService.CACHE_REPORT_SUMMARY + "::" + alice.userId() + ":";

        assertThat(reportKeys()).anyMatch(key -> key.startsWith(expectedPrefix));
    }

    @Test
    @DisplayName("cache values are JSON, not JDK-serialized bytes")
    void valuesAreJson() throws Exception {
        // JSON keeps entries readable with redis-cli and avoids both the
        // class-version brittleness and the deserialization-gadget risk of JDK
        // serialization.
        createExpense(alice, "1000.00", LocalDate.of(2026, 3, 10));
        fetchSummary(alice);

        String key = reportKeys().iterator().next();
        String raw = redisTemplate.opsForValue().get(key);

        assertThat(raw).isNotNull();
        assertThat(raw.trim()).startsWith("{");
        assertThat(raw).contains("totalExpense");
    }

    @Test
    @DisplayName("entries carry a TTL so a lost eviction cannot go stale forever")
    void entriesExpire() throws Exception {
        createExpense(alice, "1000.00", LocalDate.of(2026, 3, 10));
        fetchSummary(alice);

        String key = reportKeys().iterator().next();
        Long ttl = redisTemplate.getExpire(key);

        assertThat(ttl).isNotNull().isPositive();
        // The configured report TTL is 10 minutes.
        assertThat(ttl).isLessThanOrEqualTo(600);
    }

    // ---------------------------------------------------------- user isolation --

    @Test
    @DisplayName("two users never share a cache entry")
    void usersHaveSeparateEntries() throws Exception {
        // The single most important property of a cache in front of per-user
        // financial data. A shared key here would serve Alice's spending to Bob.
        createExpense(alice, "5000.00", LocalDate.of(2026, 3, 10));
        createExpense(bob, "77.00", LocalDate.of(2026, 3, 10));

        fetchSummary(alice);
        fetchSummary(bob);

        assertThat(reportKeys())
                .anyMatch(k -> k.contains("::" + alice.userId() + ":"))
                .anyMatch(k -> k.contains("::" + bob.userId() + ":"));

        // Each still sees only their own figure, served from their own entry.
        mockMvc.perform(get("/api/v1/reports/summary")
                        .param("from", "2026-03-01").param("to", "2026-03-31")
                        .header("Authorization", bearer(alice)))
                .andExpect(jsonPath("$.totalExpense").value(5000.00));

        mockMvc.perform(get("/api/v1/reports/summary")
                        .param("from", "2026-03-01").param("to", "2026-03-31")
                        .header("Authorization", bearer(bob)))
                .andExpect(jsonPath("$.totalExpense").value(77.00));
    }

    @Test
    @DisplayName("a cached entry is not served to a different user with the same query")
    void identicalQueryDifferentUser() throws Exception {
        // Same date range, same endpoint — only the identity differs. If the key
        // omitted the user id these two would collide.
        createExpense(alice, "5000.00", LocalDate.of(2026, 3, 10));
        fetchSummary(alice);

        mockMvc.perform(get("/api/v1/reports/summary")
                        .param("from", "2026-03-01").param("to", "2026-03-31")
                        .header("Authorization", bearer(bob)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalExpense").value(0.00));
    }

    // ------------------------------------------------------------ invalidation --

    @Test
    @DisplayName("creating a transaction evicts that user's cached reports")
    void writeEvictsOwnCache() throws Exception {
        createExpense(alice, "1000.00", LocalDate.of(2026, 3, 10));
        fetchSummary(alice);
        assertThat(reportKeys()).isNotEmpty();

        createExpense(alice, "500.00", LocalDate.of(2026, 3, 11));

        // Alice's entries are gone, so her next read recomputes.
        assertThat(reportKeys()).noneMatch(k -> k.contains("::" + alice.userId() + ":"));
    }

    @Test
    @DisplayName("the next read after a write returns the updated figure")
    void readAfterWriteIsFresh() throws Exception {
        createExpense(alice, "1000.00", LocalDate.of(2026, 3, 10));

        mockMvc.perform(get("/api/v1/reports/summary")
                        .param("from", "2026-03-01").param("to", "2026-03-31")
                        .header("Authorization", bearer(alice)))
                .andExpect(jsonPath("$.totalExpense").value(1000.00));

        createExpense(alice, "500.00", LocalDate.of(2026, 3, 11));

        mockMvc.perform(get("/api/v1/reports/summary")
                        .param("from", "2026-03-01").param("to", "2026-03-31")
                        .header("Authorization", bearer(alice)))
                .andExpect(jsonPath("$.totalExpense").value(1500.00));
    }

    @Test
    @DisplayName("one user's write does not evict another user's cache")
    void evictionIsScopedToOneUser() throws Exception {
        // The reason UserCacheEvictor scans by prefix rather than calling
        // cache.clear(): a global clear would throw away every other user's
        // entries on every write, making the cache useless under any real load.
        createExpense(alice, "5000.00", LocalDate.of(2026, 3, 10));
        createExpense(bob, "77.00", LocalDate.of(2026, 3, 10));

        fetchSummary(alice);
        fetchSummary(bob);

        createExpense(alice, "1.00", LocalDate.of(2026, 3, 12));

        assertThat(reportKeys())
                .noneMatch(k -> k.contains("::" + alice.userId() + ":"))
                .anyMatch(k -> k.contains("::" + bob.userId() + ":"));
    }

    @Test
    @DisplayName("a CSV import evicts the cache too")
    void importEvictsCache() throws Exception {
        createExpense(alice, "1000.00", LocalDate.of(2026, 3, 10));
        fetchSummary(alice);
        assertThat(reportKeys()).isNotEmpty();

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/api/v1/imports")
                        .file(new org.springframework.mock.web.MockMultipartFile(
                                "file", "cache.csv", "text/csv",
                                ("date,type,amount,description\n2026-03-15,EXPENSE,250.00,Imported\n")
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                        .header("Authorization", bearer(alice)))
                .andExpect(status().isOk());

        assertThat(reportKeys()).noneMatch(k -> k.contains("::" + alice.userId() + ":"));

        mockMvc.perform(get("/api/v1/reports/summary")
                        .param("from", "2026-03-01").param("to", "2026-03-31")
                        .header("Authorization", bearer(alice)))
                .andExpect(jsonPath("$.totalExpense").value(1250.00));
    }

    @Test
    @DisplayName("different date ranges get different entries")
    void rangeIsPartOfTheKey() throws Exception {
        createExpense(alice, "1000.00", LocalDate.of(2026, 3, 10));

        fetchSummary(alice);
        mockMvc.perform(get("/api/v1/reports/summary")
                        .param("from", "2026-04-01").param("to", "2026-04-30")
                        .header("Authorization", bearer(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalExpense").value(0.00));

        // Two distinct ranges must not share an entry, or April would show March's
        // spending.
        assertThat(reportKeys().stream()
                .filter(k -> k.contains("::" + alice.userId() + ":summary:"))
                .count()).isEqualTo(2);
    }
}
