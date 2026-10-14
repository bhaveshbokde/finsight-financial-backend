package com.finsight.outbox.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finsight.common.error.ApiException;
import com.finsight.common.error.ErrorCode;
import com.finsight.messaging.event.ImportRequestedEvent;
import com.finsight.outbox.domain.OutboxEvent;
import com.finsight.outbox.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Appends events to the outbox.
 *
 * <p>{@code Propagation.MANDATORY} is the key design decision here: this method
 * <em>refuses to run</em> unless a transaction is already in progress. The entire
 * point of an outbox is that the event row and the business change commit together,
 * so recording an event in its own transaction would silently reintroduce the dual
 * write the outbox exists to eliminate. Making it a startup-visible error beats
 * discovering it during an incident.
 */
@Service
public class OutboxRecorder {

    private static final Logger log = LoggerFactory.getLogger(OutboxRecorder.class);

    private final OutboxEventRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public OutboxRecorder(OutboxEventRepository outboxRepository, ObjectMapper objectMapper) {
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Records an {@code ImportRequested} event.
     *
     * @param topic the destination topic, injected by the caller so topic naming
     *              stays in configuration rather than being hard-coded here
     * @return the persisted row, whose {@code eventId} matches the event's
     * @throws org.springframework.transaction.IllegalTransactionStateException
     *         if called outside an active transaction
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public OutboxEvent recordImportRequested(Long importJobId, Long userId, String fileChecksum,
                                             int totalRows, String topic) {
        // One id, used both as the row's identity and inside the serialised payload,
        // so the consumer deduplicates on exactly the value stored here.
        UUID eventId = UUID.randomUUID();
        ImportRequestedEvent event = ImportRequestedEvent.of(
                eventId, importJobId, userId, fileChecksum, totalRows);

        OutboxEvent row = OutboxEvent.pending(
                eventId,
                ImportRequestedEvent.AGGREGATE_TYPE,
                String.valueOf(importJobId),
                ImportRequestedEvent.TYPE,
                topic,
                // Partition key = import job id, so every event about one job keeps
                // its order relative to the others.
                String.valueOf(importJobId),
                toJson(event));

        OutboxEvent saved = outboxRepository.save(row);
        log.debug("Recorded outbox event id={} eventId={} for importJob={}",
                saved.getId(), saved.getEventId(), importJobId);
        return saved;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            // Unreachable for our own records; if it ever happens it is a bug, and
            // failing the enclosing transaction is the correct response.
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Could not serialise outbox payload", e);
        }
    }
}
