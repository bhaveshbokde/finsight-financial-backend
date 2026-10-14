package com.finsight.messaging.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Ledger entry recording that one consumer group has already applied one event.
 *
 * <p>Kafka gives at-least-once delivery: a consumer that crashes after doing its
 * work but before committing the offset will see the same record again. This table
 * is what turns that into <em>effectively once</em>.
 *
 * <p>The key is {@code (eventId, consumerGroup)}, not {@code eventId} alone —
 * different consumer groups are independent subscribers and each must get its own
 * chance to process the event.
 *
 * <p>The insert happens inside the same transaction as the work it guards, so
 * either both commit or neither does. There is no window in which the event is
 * marked processed but the work was rolled back.
 */
@Entity
@Table(name = "processed_events")
@IdClass(ProcessedEvent.Key.class)
public class ProcessedEvent {

    @Id
    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Id
    @Column(name = "consumer_group", nullable = false, length = 120, updatable = false)
    private String consumerGroup;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt = Instant.now();

    protected ProcessedEvent() {
    }

    public ProcessedEvent(UUID eventId, String consumerGroup) {
        this.eventId = eventId;
        this.consumerGroup = consumerGroup;
        this.processedAt = Instant.now();
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getConsumerGroup() {
        return consumerGroup;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }

    /** Composite primary key. Must be {@link Serializable} and implement equals/hashCode per the JPA spec. */
    public static class Key implements Serializable {

        private UUID eventId;
        private String consumerGroup;

        public Key() {
        }

        public Key(UUID eventId, String consumerGroup) {
            this.eventId = eventId;
            this.consumerGroup = consumerGroup;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key key)) {
                return false;
            }
            return Objects.equals(eventId, key.eventId)
                    && Objects.equals(consumerGroup, key.consumerGroup);
        }

        @Override
        public int hashCode() {
            return Objects.hash(eventId, consumerGroup);
        }
    }
}
