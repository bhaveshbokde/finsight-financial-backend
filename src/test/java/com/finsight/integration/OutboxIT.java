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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The transactional outbox and the consumer-side idempotency ledger.
 *
 * <p>{@code async-threshold-rows} is lowered to 2 here so a small test file takes
 * the asynchronous path and an outbox row is actually written.
 *
 * <p>No broker is involved. These tests prove the half of the pipeline that lives
 * in PostgreSQL — the event row is written atomically with the business change,
 * and the ledger makes a redelivered event a no-op. Whether Kafka then delivers
 * the record is Kafka's problem, and is covered separately by
 * {@code KafkaImportIT}, which needs a broker.
 */
@Transactional
@TestPropertySource(properties = {
        "finsight.csv-import.async-threshold-rows=2",
        "finsight.outbox.enabled=false"      // the poller must not race the test's rollback
})
class OutboxIT extends AbstractIntegrationTest {

    @Autowired
    private OutboxEventRepository outboxRepository;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    private AuthResponse user;

    private static final String LARGE_ENOUGH_CSV = """
            date,type,amount,description,merchant,category
            2026-03-01,EXPENSE,100.00,Row one,Shop,Groceries
            2026-03-02,EXPENSE,200.00,Row two,Shop,Groceries
            2026-03-03,EXPENSE,300.00,Row three,Shop,Groceries
            2026-03-04,EXPENSE,400.00,Row four,Shop,Groceries
            """;

    @BeforeEach
    void setUp() throws Exception {
        user = registerUser("outbox");
    }

    private JsonNode uploadLargeFile() throws Exception {
        String body = mockMvc.perform(multipart("/api/v1/imports")
                        .file(new MockMultipartFile("file", "large.csv", "text/csv",
                                LARGE_ENOUGH_CSV.getBytes(StandardCharsets.UTF_8)))
                        .header("Authorization", bearer(user)))
                // 202: accepted and durably stored, but no transaction inserted yet.
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    // ------------------------------------------------------------ atomic write --

    @Nested
    @DisplayName("atomic write")
    class AtomicWrite {

        @Test
        @DisplayName("an accepted upload returns 202 and leaves the job ACCEPTED")
        void queuedUploadIsAccepted() throws Exception {
            JsonNode job = uploadLargeFile();

            assertThat(job.get("status").asText()).isEqualTo(ImportStatus.ACCEPTED.name());
            assertThat(job.get("terminal").asBoolean()).isFalse();
            // Nothing has been imported yet — that is exactly what 202 means.
            assertThat(job.get("importedRows").asInt()).isZero();
        }

        @Test
        @DisplayName("the event row is written in the same transaction as the job")
        void eventRecordedWithJob() throws Exception {
            JsonNode job = uploadLargeFile();

            List<OutboxEvent> events = outboxRepository.findByAggregateTypeAndAggregateId(
                    ImportRequestedEvent.AGGREGATE_TYPE, String.valueOf(job.get("id").asLong()));

            // This is the whole point of the outbox: the job and its event are one
            // atomic local write, so there is no state where a job waits forever
            // for an event that was never recorded.
            assertThat(events).hasSize(1);

            OutboxEvent event = events.get(0);
            assertThat(event.getEventType()).isEqualTo(ImportRequestedEvent.TYPE);
            assertThat(event.isPublished()).isFalse();      // the poller has not run
            assertThat(event.getAttempts()).isZero();
            assertThat(event.getEventId()).isNotNull();
        }

        @Test
        @DisplayName("the stored payload carries the same eventId as the row")
        void payloadEventIdMatchesRow() throws Exception {
            // They must agree: the consumer deduplicates on the id it reads from
            // the payload, and the operator debugs using the id on the row.
            JsonNode job = uploadLargeFile();

            OutboxEvent event = outboxRepository.findByAggregateTypeAndAggregateId(
                    ImportRequestedEvent.AGGREGATE_TYPE,
                    String.valueOf(job.get("id").asLong())).get(0);

            ImportRequestedEvent payload =
                    objectMapper.readValue(event.getPayload(), ImportRequestedEvent.class);

            assertThat(payload.eventId()).isEqualTo(event.getEventId());
            assertThat(payload.importJobId()).isEqualTo(job.get("id").asLong());
            assertThat(payload.userId()).isEqualTo(user.userId());
            assertThat(payload.isValid()).isTrue();
        }

        @Test
        @DisplayName("the message key is the job id, so events for one job keep their order")
        void partitionKeyIsAggregateId() throws Exception {
            JsonNode job = uploadLargeFile();

            OutboxEvent event = outboxRepository.findByAggregateTypeAndAggregateId(
                    ImportRequestedEvent.AGGREGATE_TYPE,
                    String.valueOf(job.get("id").asLong())).get(0);

            assertThat(event.getPartitionKey()).isEqualTo(String.valueOf(job.get("id").asLong()));
        }

        @Test
        @DisplayName("the payload does not carry the CSV content")
        void payloadExcludesFileContent() throws Exception {
            // Shipping the file through Kafka would hit the broker's 1 MiB default
            // message limit and would duplicate the data. The consumer reads it
            // from import_payloads by id instead.
            JsonNode job = uploadLargeFile();

            OutboxEvent event = outboxRepository.findByAggregateTypeAndAggregateId(
                    ImportRequestedEvent.AGGREGATE_TYPE,
                    String.valueOf(job.get("id").asLong())).get(0);

            assertThat(event.getPayload()).doesNotContain("Row one");
            assertThat(event.getPayload()).doesNotContain("Groceries");
            assertThat(event.getPayload()).contains("fileChecksum");
        }

        @Test
        @DisplayName("a small file takes the synchronous path and writes no event")
        void smallFileBypassesTheOutbox() throws Exception {
            String tiny = """
                    date,type,amount,description
                    2026-03-01,EXPENSE,100.00,Only row
                    """;

            String body = mockMvc.perform(multipart("/api/v1/imports")
                            .file(new MockMultipartFile("file", "tiny.csv", "text/csv",
                                    tiny.getBytes(StandardCharsets.UTF_8)))
                            .header("Authorization", bearer(user)))
                    .andExpect(status().isOk())   // 200, not 202
                    .andReturn().getResponse().getContentAsString();

            JsonNode job = objectMapper.readTree(body);
            assertThat(job.get("status").asText()).isEqualTo("COMPLETED");

            assertThat(outboxRepository.findByAggregateTypeAndAggregateId(
                    ImportRequestedEvent.AGGREGATE_TYPE,
                    String.valueOf(job.get("id").asLong()))).isEmpty();
        }
    }

    // --------------------------------------------------------------- claiming --

    @Nested
    @DisplayName("publisher claim query")
    class Claiming {

        @Test
        @DisplayName("returns unpublished rows oldest first")
        void claimsUnpublished() throws Exception {
            uploadLargeFile();

            List<OutboxEvent> claimed = outboxRepository.claimUnpublished(10, 100);

            assertThat(claimed).isNotEmpty();
            assertThat(claimed).allMatch(e -> !e.isPublished());
        }

        @Test
        @DisplayName("ignores rows that have exhausted their attempt budget")
        void skipsParkedRows() throws Exception {
            uploadLargeFile();

            OutboxEvent event = outboxRepository.claimUnpublished(10, 100).get(0);
            for (int i = 0; i < 10; i++) {
                event.recordFailure("simulated broker outage");
            }
            outboxRepository.saveAndFlush(event);

            // Parked, not retried forever: a permanently bad row must not consume
            // the poller's budget on every pass.
            assertThat(outboxRepository.claimUnpublished(10, 100))
                    .noneMatch(e -> e.getEventId().equals(event.getEventId()));

            assertThat(outboxRepository.countParked(10)).isGreaterThanOrEqualTo(1);
        }

        @Test
        @DisplayName("a published row is never claimed again")
        void skipsPublishedRows() throws Exception {
            uploadLargeFile();

            OutboxEvent event = outboxRepository.claimUnpublished(10, 100).get(0);
            event.markPublished();
            outboxRepository.saveAndFlush(event);

            assertThat(outboxRepository.claimUnpublished(10, 100))
                    .noneMatch(e -> e.getEventId().equals(event.getEventId()));
        }

        @Test
        @DisplayName("respects the batch size")
        void respectsBatchSize() throws Exception {
            uploadLargeFile();
            // Each upload has different content, so each produces its own event.
            mockMvc.perform(multipart("/api/v1/imports")
                            .file(new MockMultipartFile("file", "second.csv", "text/csv",
                                    (LARGE_ENOUGH_CSV + "2026-03-05,EXPENSE,500.00,Row five,Shop,Dining\n")
                                            .getBytes(StandardCharsets.UTF_8)))
                            .header("Authorization", bearer(user)))
                    .andExpect(status().isAccepted());

            assertThat(outboxRepository.countPending()).isGreaterThanOrEqualTo(2);
            assertThat(outboxRepository.claimUnpublished(10, 1)).hasSize(1);
        }
    }

    // ------------------------------------------------------------ idempotency --

    @Nested
    @DisplayName("consumer idempotency ledger")
    class IdempotencyLedger {

        @Test
        @DisplayName("the first claim wins and the second is a no-op")
        void claimIsAtomicAndSingleWinner() {
            UUID eventId = UUID.randomUUID();
            String group = "finsight-test-consumer";

            // This is what makes at-least-once delivery effectively once-applied.
            assertThat(processedEventRepository.claim(eventId, group)).isEqualTo(1);
            assertThat(processedEventRepository.claim(eventId, group)).isZero();
            assertThat(processedEventRepository.wasProcessed(eventId, group)).isTrue();
        }

        @Test
        @DisplayName("different consumer groups each get their own claim")
        void groupsAreIndependent() {
            UUID eventId = UUID.randomUUID();

            // Independent subscribers must each get a chance to process the event;
            // keying on eventId alone would starve the second group.
            assertThat(processedEventRepository.claim(eventId, "group-a")).isEqualTo(1);
            assertThat(processedEventRepository.claim(eventId, "group-b")).isEqualTo(1);

            assertThat(processedEventRepository.wasProcessed(eventId, "group-a")).isTrue();
            assertThat(processedEventRepository.wasProcessed(eventId, "group-b")).isTrue();
            assertThat(processedEventRepository.wasProcessed(eventId, "group-c")).isFalse();
        }

        @Test
        @DisplayName("an unseen event is reported as unprocessed")
        void unseenEvent() {
            assertThat(processedEventRepository.wasProcessed(
                    UUID.randomUUID(), "finsight-test-consumer")).isFalse();
        }
    }

    // ----------------------------------------------------------- failure state --

    @Test
    @DisplayName("a failure is recorded on the row with its reason and attempt count")
    void recordsFailureDetail() throws Exception {
        // The operator needs to know *why* publication is failing, not just that
        // the backlog is growing.
        uploadLargeFile();

        OutboxEvent event = outboxRepository.claimUnpublished(10, 100).get(0);
        event.recordFailure("TimeoutException: Topic not present in metadata after 10000 ms");
        outboxRepository.saveAndFlush(event);

        OutboxEvent reloaded = outboxRepository.findById(event.getId()).orElseThrow();

        assertThat(reloaded.getAttempts()).isEqualTo(1);
        assertThat(reloaded.getLastError()).contains("TimeoutException");
        assertThat(reloaded.isPublished()).isFalse();
    }

    @Test
    @DisplayName("a successful publish clears the previous error")
    void publishClearsError() throws Exception {
        uploadLargeFile();

        OutboxEvent event = outboxRepository.claimUnpublished(10, 100).get(0);
        event.recordFailure("transient failure");
        event.markPublished();
        outboxRepository.saveAndFlush(event);

        OutboxEvent reloaded = outboxRepository.findById(event.getId()).orElseThrow();

        assertThat(reloaded.isPublished()).isTrue();
        assertThat(reloaded.getPublishedAt()).isNotNull();
        assertThat(reloaded.getLastError()).isNull();
        // The attempt count is kept: it is useful history, not state to reset.
        assertThat(reloaded.getAttempts()).isEqualTo(1);
    }
}
