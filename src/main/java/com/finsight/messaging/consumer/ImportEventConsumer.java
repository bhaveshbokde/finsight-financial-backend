package com.finsight.messaging.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finsight.config.CsvImportProperties;
import com.finsight.imports.service.ImportProcessor;
import com.finsight.messaging.event.ImportRequestedEvent;
import com.finsight.messaging.idempotency.ProcessedEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumes {@code ImportRequested} events and runs the import.
 *
 * <h2>Delivery semantics, stated honestly</h2>
 * Kafka delivers <strong>at least once</strong>. {@code enable-auto-commit} is
 * false and the offset is committed only after the listener returns normally, so a
 * crash mid-processing causes redelivery rather than silent loss. Redelivery means
 * this method must be safe to run twice on the same event, and two independent
 * mechanisms ensure it is:
 * <ol>
 *   <li>{@code processed_events} — an atomic {@code INSERT ... ON CONFLICT DO
 *       NOTHING} claims the event. The second delivery loses the claim and returns
 *       immediately.</li>
 *   <li>The unique index on {@code (user_id, external_ref)} — even if the ledger
 *       were bypassed entirely, the rows themselves cannot be inserted twice.</li>
 * </ol>
 * Neither of these makes delivery exactly-once. They make <em>effects</em> apply
 * once, which is the property that actually matters for financial data.
 *
 * <h2>Why the claim and the work share a transaction</h2>
 * The ledger insert and the import run in one database transaction. If processing
 * fails, the claim rolls back with it, so a retry is free to try again. Claiming in
 * a separate transaction would mean a crash after the claim but before the work
 * leaves the event permanently marked done and never performed.
 */
@Component
public class ImportEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(ImportEventConsumer.class);

    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final ImportProcessor importProcessor;
    private final CsvImportProperties importProperties;
    private final String consumerGroup;
    private final Counter processedCounter;
    private final Counter skippedCounter;

    public ImportEventConsumer(ObjectMapper objectMapper,
                               ProcessedEventRepository processedEventRepository,
                               ImportProcessor importProcessor,
                               CsvImportProperties importProperties,
                               MeterRegistry meterRegistry,
                               @Value("${spring.kafka.consumer.group-id}") String consumerGroup) {
        this.objectMapper = objectMapper;
        this.processedEventRepository = processedEventRepository;
        this.importProcessor = importProcessor;
        this.importProperties = importProperties;
        this.consumerGroup = consumerGroup;

        this.processedCounter = Counter.builder("finsight.kafka.events")
                .tag("outcome", "processed").register(meterRegistry);
        this.skippedCounter = Counter.builder("finsight.kafka.events")
                .tag("outcome", "skipped_duplicate").register(meterRegistry);
    }

    /**
     * Handles one record.
     *
     * <p>{@code autoStartup} is bound to a property so the listener can be switched
     * off entirely — slice tests and any environment without a broker then start
     * cleanly instead of logging connection failures forever.
     *
     * <p>Exceptions thrown here are handled by the container's
     * {@code DefaultErrorHandler}: retried with backoff for transient faults, or
     * routed straight to the dead-letter topic for the non-retryable ones listed in
     * {@link com.finsight.messaging.config.KafkaConfig}.
     */
    @KafkaListener(
            topics = "${finsight.kafka.topics.import-requested}",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "finsightListenerContainerFactory",
            autoStartup = "${finsight.kafka.consumer-enabled:true}")
    @Transactional
    public void onImportRequested(ConsumerRecord<String, String> record) {
        ImportRequestedEvent event = deserialise(record);

        MDC.put("eventId", String.valueOf(event.eventId()));
        MDC.put("userId", String.valueOf(event.userId()));
        try {
            // Atomic claim. 0 means another delivery of this event already did the
            // work — nothing more to do, and returning normally commits the offset.
            int claimed = processedEventRepository.claim(event.eventId(), consumerGroup);
            if (claimed == 0) {
                skippedCounter.increment();
                log.info("Event {} already processed by group '{}'; skipping duplicate delivery",
                        event.eventId(), consumerGroup);
                return;
            }

            log.info("Processing import job id={} from event {} (topic={} partition={} offset={})",
                    event.importJobId(), event.eventId(),
                    record.topic(), record.partition(), record.offset());

            importProcessor.processJob(event.importJobId(), event.userId(), importProperties.maxRows());
            processedCounter.increment();

        } finally {
            // Must be removed: the container reuses this thread for the next record,
            // and a leftover value would mislabel its log lines.
            MDC.remove("eventId");
            MDC.remove("userId");
        }
    }

    /**
     * Parses and validates the message body.
     *
     * <p>Both failure modes throw {@link IllegalArgumentException}, which
     * {@code KafkaConfig} classifies as non-retryable: malformed bytes will not
     * become valid on a second attempt, so the record goes straight to the DLT
     * instead of burning the retry budget and delaying every message behind it.
     */
    private ImportRequestedEvent deserialise(ConsumerRecord<String, String> record) {
        ImportRequestedEvent event;
        try {
            event = objectMapper.readValue(record.value(), ImportRequestedEvent.class);
        } catch (Exception e) {
            log.error("Unparseable message at topic={} partition={} offset={}: {}",
                    record.topic(), record.partition(), record.offset(), e.toString());
            throw new IllegalArgumentException("Message could not be deserialised", e);
        }

        if (!event.isValid()) {
            log.error("Invalid ImportRequestedEvent at topic={} offset={}: {}",
                    record.topic(), record.offset(), event);
            throw new IllegalArgumentException("Message failed schema validation");
        }
        return event;
    }
}
