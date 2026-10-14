package com.finsight.outbox.service;

import com.finsight.config.OutboxProperties;
import com.finsight.outbox.domain.OutboxEvent;
import com.finsight.outbox.repository.OutboxEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Publishes outbox rows to Kafka.
 *
 * <h2>How a pass works</h2>
 * <ol>
 *   <li>Claim up to {@code batchSize} unpublished rows with
 *       {@code SELECT ... FOR UPDATE SKIP LOCKED}, so several application
 *       instances can poll concurrently without duplicating or blocking.</li>
 *   <li>Send each one <em>synchronously</em> and wait for the broker's
 *       acknowledgement.</li>
 *   <li>Stamp {@code publishedAt} on success, or increment {@code attempts} and
 *       record the reason on failure.</li>
 * </ol>
 *
 * <h2>Why the send is synchronous</h2>
 * An asynchronous send would let the transaction commit {@code publishedAt} before
 * the broker had actually accepted the record — and a broker rejection afterwards
 * would leave a row marked published that never was. Waiting for the ack keeps the
 * database's view and the broker's view in agreement. The cost is throughput, which
 * is bounded here by {@code batchSize} and is irrelevant at this application's scale.
 *
 * <h2>What can still go wrong</h2>
 * If the process dies between a successful send and the commit of
 * {@code publishedAt}, the next pass republishes the event. That is the
 * at-least-once window, and it is why the consumer deduplicates on
 * {@code eventId}. Claiming that the outbox gives exactly-once delivery would be
 * wrong — see docs/08.
 *
 * <h2>Parking</h2>
 * A row that fails {@code maxAttempts} times stops being retried. It stays in the
 * table with its last error, and {@code finsight.outbox.parked} reports how many
 * there are, so a sustained Kafka outage is visible rather than silently eating
 * imports. Recovery steps are in TROUBLESHOOTING.md.
 */
@Component
@ConditionalOnProperty(prefix = "finsight.outbox", name = "enabled", havingValue = "true",
                       matchIfMissing = true)
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    /** How long to wait for a broker acknowledgement before treating the send as failed. */
    private static final long SEND_TIMEOUT_SECONDS = 10;

    private final OutboxEventRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final OutboxProperties properties;
    private final Counter publishedCounter;
    private final Counter failedCounter;

    public OutboxPublisher(OutboxEventRepository outboxRepository,
                           KafkaTemplate<String, String> kafkaTemplate,
                           OutboxProperties properties,
                           MeterRegistry meterRegistry) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;

        this.publishedCounter = Counter.builder("finsight.outbox.published")
                .description("Outbox events acknowledged by the broker")
                .register(meterRegistry);
        this.failedCounter = Counter.builder("finsight.outbox.publish.failures")
                .description("Outbox publish attempts that failed")
                .register(meterRegistry);

        // A gauge rather than a counter: the question is "how many are stuck right
        // now", which is a level, not a rate.
        meterRegistry.gauge("finsight.outbox.parked", this,
                p -> p.outboxRepository.countParked(p.properties.maxAttempts()));
        meterRegistry.gauge("finsight.outbox.pending", this,
                p -> p.outboxRepository.countPending());
    }

    /**
     * One polling pass.
     *
     * <p>{@code fixedDelayString} rather than {@code fixedRate}: the delay is
     * measured from the <em>end</em> of the previous pass, so a slow pass (a
     * struggling broker) cannot cause passes to pile up on top of each other.
     *
     * <p>{@code REQUIRES_NEW} gives the pass its own transaction, independent of
     * whatever the scheduler thread may have been doing.
     */
    @Scheduled(fixedDelayString = "${finsight.outbox.poll-interval-ms:1000}",
               initialDelayString = "${finsight.outbox.poll-interval-ms:1000}")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void publishPending() {
        List<OutboxEvent> batch =
                outboxRepository.claimUnpublished(properties.maxAttempts(), properties.batchSize());

        if (batch.isEmpty()) {
            return;
        }
        log.debug("Publishing {} outbox event(s)", batch.size());

        for (OutboxEvent event : batch) {
            publishOne(event);
        }
    }

    /**
     * Sends one event and records the outcome on its row.
     *
     * <p>Failures are caught per event so that one bad topic or one oversized
     * message does not stop the rest of the batch. The row stays unpublished and
     * is retried on a later pass.
     */
    private void publishOne(OutboxEvent event) {
        try {
            kafkaTemplate
                    .send(event.getTopic(), event.getPartitionKey(), event.getPayload())
                    .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            event.markPublished();
            publishedCounter.increment();
            log.info("Published outbox event eventId={} type={} to topic={}",
                    event.getEventId(), event.getEventType(), event.getTopic());

        } catch (InterruptedException e) {
            // Restore the flag so the scheduler's shutdown logic still sees it.
            Thread.currentThread().interrupt();
            event.recordFailure("Interrupted while waiting for broker acknowledgement");
            failedCounter.increment();
            log.warn("Interrupted publishing outbox event eventId={}", event.getEventId());

        } catch (Exception e) {
            // Deliberately broad: a broker outage, a timeout, a serialisation fault
            // and an authorisation error must all leave the row retryable rather
            // than aborting the pass. The reason is persisted for diagnosis.
            event.recordFailure(e.getClass().getSimpleName() + ": " + e.getMessage());
            failedCounter.increment();

            int attempts = event.getAttempts();
            if (attempts >= properties.maxAttempts()) {
                log.error("Outbox event eventId={} parked after {} failed attempts; "
                                + "it will not be retried automatically. Last error: {}",
                        event.getEventId(), attempts, event.getLastError());
            } else {
                log.warn("Publish attempt {}/{} failed for outbox event eventId={}: {}",
                        attempts, properties.maxAttempts(), event.getEventId(), e.toString());
            }
        }
        // No explicit save(): `event` is managed by the transaction opened in
        // publishPending(), so the state change flushes on commit.
    }
}
