package com.finsight.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.finsight.auth.dto.AuthResponse;
import com.finsight.imports.domain.ImportStatus;
import com.finsight.messaging.event.ImportRequestedEvent;
import com.finsight.messaging.idempotency.ProcessedEventRepository;
import com.finsight.outbox.domain.OutboxEvent;
import com.finsight.outbox.repository.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The full asynchronous import path through a <strong>real Kafka broker</strong>:
 * upload → outbox row → publisher → broker → consumer → transactions inserted.
 *
 * <p>Tagged {@code kafka} and <strong>skipped</strong> when no broker is
 * reachable, so the suite stays runnable without one. Start a broker with
 * {@code ./scripts/local-services.sh start}.
 *
 * <h2>Why this test is not {@code @Transactional}</h2>
 * Everything here depends on data being <em>committed</em>: the outbox poller
 * runs on a scheduler thread in its own transaction, and the Kafka listener runs
 * on a container thread in another. A test-managed transaction would be invisible
 * to both, and its rollback would hide the rows they need. So the test commits
 * and cleans up after itself.
 *
 * <h2>What this proves that {@code OutboxIT} cannot</h2>
 * {@code OutboxIT} stops at the database: it proves the event row is written
 * atomically with the job. This one proves the rest of the chain actually
 * carries that row to a consumer and that the consumer applies it — including
 * the idempotency ledger absorbing a deliberate redelivery.
 */
@Tag("kafka")
@TestPropertySource(properties = {
        // Force the asynchronous path for a small test file.
        "finsight.csv-import.async-threshold-rows=2",
        // Both halves of the pipeline must actually run here, unlike every other IT.
        "finsight.outbox.enabled=true",
        "finsight.outbox.poll-interval-ms=500",
        "finsight.kafka.consumer-enabled=true",
        "spring.kafka.consumer.group-id=finsight-kafka-it",
        "spring.kafka.consumer.auto-offset-reset=earliest"
})
class KafkaImportIT extends AbstractIntegrationTest {

    /** Generous, because a cold broker plus consumer-group rebalance is not fast. */
    private static final Duration TIMEOUT = Duration.ofSeconds(90);

    @Autowired
    private OutboxEventRepository outboxRepository;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private KafkaAdmin kafkaAdmin;

    @Value("${finsight.kafka.topics.import-requested}")
    private String topic;

    private AuthResponse user;

    private static final String ASYNC_CSV = """
            date,type,amount,description,merchant,category
            2026-05-01,INCOME,95000.00,May salary,Lentra,Salary
            2026-05-02,EXPENSE,3480.75,Weekly groceries,Big Bazaar,Groceries
            2026-05-03,EXPENSE,1250.00,Electricity bill,MSEDCL,Utilities
            2026-05-04,EXPENSE,420.50,Team lunch,Cafe Mocha,Dining
            2026-05-05,EXPENSE,2100.00,Metro pass,Pune Metro,Transport
            """;

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(kafkaReachable(),
                "Kafka is not reachable on the configured bootstrap servers — skipping. "
                        + "Start it with: ./scripts/local-services.sh start");

        user = registerUser("kafka");
    }

    /** Asks the broker for cluster metadata; a TCP check would pass while it was still starting. */
    private boolean kafkaReachable() {
        try (var admin = org.apache.kafka.clients.admin.AdminClient.create(
                kafkaAdmin.getConfigurationProperties())) {
            admin.describeCluster().nodes().get(5, java.util.concurrent.TimeUnit.SECONDS);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private JsonNode upload(String filename, String content) throws Exception {
        String body = mockMvc.perform(multipart("/api/v1/imports")
                        .file(new MockMultipartFile("file", filename, "text/csv",
                                content.getBytes(StandardCharsets.UTF_8)))
                        .header("Authorization", bearer(user)))
                // 202: durably stored and queued. Nothing is imported yet — that is
                // exactly what this status code means.
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private JsonNode fetchJob(long jobId) throws Exception {
        return objectMapper.readTree(
                mockMvc.perform(get("/api/v1/imports/" + jobId)
                                .header("Authorization", bearer(user)))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString());
    }

    // -------------------------------------------------------------- the path --

    @Test
    @DisplayName("an accepted upload is carried to the consumer and applied")
    void endToEndAsynchronousImport() throws Exception {
        JsonNode accepted = upload("may-statement.csv", ASYNC_CSV);
        long jobId = accepted.get("id").asLong();

        assertThat(accepted.get("status").asText()).isEqualTo(ImportStatus.ACCEPTED.name());
        assertThat(accepted.get("terminal").asBoolean()).isFalse();
        assertThat(accepted.get("importedRows").asInt()).isZero();

        // The event row exists and is unpublished at this instant (or very nearly —
        // the poller runs every 500 ms, so only assert that a row was recorded).
        List<OutboxEvent> events = outboxRepository.findByAggregateTypeAndAggregateId(
                ImportRequestedEvent.AGGREGATE_TYPE, String.valueOf(jobId));
        assertThat(events).hasSize(1);
        var eventId = events.get(0).getEventId();

        // --- publisher → broker → consumer → database ---
        await("import reaches a terminal state")
                .atMost(TIMEOUT)
                .pollInterval(Duration.ofMillis(500))
                .until(() -> fetchJob(jobId).get("terminal").asBoolean());

        JsonNode done = fetchJob(jobId);
        assertThat(done.get("status").asText()).isEqualTo(ImportStatus.COMPLETED.name());
        assertThat(done.get("totalRows").asInt()).isEqualTo(5);
        assertThat(done.get("importedRows").asInt()).isEqualTo(5);
        assertThat(done.get("duplicateRows").asInt()).isZero();
        assertThat(done.get("failedRows").asInt()).isZero();

        // The outbox row was marked published only after the broker acknowledged it.
        OutboxEvent published = outboxRepository.findByAggregateTypeAndAggregateId(
                ImportRequestedEvent.AGGREGATE_TYPE, String.valueOf(jobId)).get(0);
        assertThat(published.isPublished()).isTrue();
        assertThat(published.getPublishedAt()).isNotNull();

        // The consumer recorded its claim, in the same transaction as the work.
        assertThat(processedEventRepository.wasProcessed(eventId, "finsight-kafka-it")).isTrue();

        // And the transactions really are queryable through the API.
        String list = mockMvc.perform(get("/api/v1/transactions")
                        .param("from", "2026-05-01").param("to", "2026-05-31")
                        .param("size", "50")
                        .header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(list).get("totalElements").asInt()).isEqualTo(5);
    }

    @Test
    @DisplayName("a redelivered event is absorbed by the idempotency ledger")
    void redeliveryIsIdempotent() throws Exception {
        JsonNode accepted = upload("redelivery.csv", ASYNC_CSV);
        long jobId = accepted.get("id").asLong();

        await("first delivery completes")
                .atMost(TIMEOUT).pollInterval(Duration.ofMillis(500))
                .until(() -> fetchJob(jobId).get("terminal").asBoolean());

        JsonNode first = fetchJob(jobId);
        assertThat(first.get("importedRows").asInt()).isEqualTo(5);

        long transactionsAfterFirst = countTransactionsInMay();
        assertThat(transactionsAfterFirst).isEqualTo(5);

        // --- force a redelivery, exactly as a publisher crash would ---
        // Clearing published_at makes the poller send the identical event again.
        // This is the at-least-once window, reproduced deliberately.
        OutboxEvent event = outboxRepository.findByAggregateTypeAndAggregateId(
                ImportRequestedEvent.AGGREGATE_TYPE, String.valueOf(jobId)).get(0);
        var eventId = event.getEventId();

        outboxRepository.findById(event.getId()).ifPresent(e -> {
            setField(e, "publishedAt", null);
            outboxRepository.saveAndFlush(e);
        });

        await("event is republished")
                .atMost(TIMEOUT).pollInterval(Duration.ofMillis(500))
                .until(() -> outboxRepository.findById(event.getId())
                        .map(OutboxEvent::isPublished).orElse(false));

        // Give the consumer time to receive and discard it.
        Thread.sleep(3_000);

        // The ledger claim was already held, so the consumer did nothing.
        assertThat(processedEventRepository.wasProcessed(eventId, "finsight-kafka-it")).isTrue();
        assertThat(countTransactionsInMay())
                .as("redelivery must not insert a second copy of any row")
                .isEqualTo(transactionsAfterFirst);

        JsonNode afterRedelivery = fetchJob(jobId);
        assertThat(afterRedelivery.get("importedRows").asInt()).isEqualTo(5);
        assertThat(afterRedelivery.get("totalRows").asInt()).isEqualTo(5);
    }

    @Test
    @DisplayName("the configured topics exist on the broker")
    void topicsAreCreated() throws Exception {
        try (var admin = org.apache.kafka.clients.admin.AdminClient.create(
                kafkaAdmin.getConfigurationProperties())) {

            Map<String, org.apache.kafka.clients.admin.TopicDescription> described =
                    admin.describeTopics(List.of(topic)).allTopicNames()
                            .get(10, java.util.concurrent.TimeUnit.SECONDS);

            assertThat(described).containsKey(topic);
            assertThat(described.get(topic).partitions()).isNotEmpty();
        }
    }

    // ----------------------------------------------------------------- helpers --

    private long countTransactionsInMay() throws Exception {
        String list = mockMvc.perform(get("/api/v1/transactions")
                        .param("from", "2026-05-01").param("to", "2026-05-31")
                        .param("size", "100")
                        .header("Authorization", bearer(user)))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(list).get("totalElements").asLong();
    }

    /** Clears a field the domain model deliberately has no setter for. */
    private static void setField(Object target, String fieldName, Object value) {
        try {
            var field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not set " + fieldName, e);
        }
    }
}
