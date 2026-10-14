package com.finsight.outbox.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * A domain event waiting to be published to Kafka.
 *
 * <h2>The problem this solves</h2>
 * "Save the import job, then send a Kafka message" is a dual write across two
 * systems with no shared transaction. Two failure modes follow:
 * <ul>
 *   <li>Commit succeeds, send fails → the job sits in ACCEPTED forever and nothing
 *       ever processes it.</li>
 *   <li>Send succeeds, commit rolls back → a consumer receives an event for a job
 *       that does not exist.</li>
 * </ul>
 *
 * <h2>How the outbox fixes it</h2>
 * The event row is INSERTed in the <em>same</em> local transaction as the business
 * change. Either both are durable or neither is. A separate poller then publishes
 * unpublished rows and stamps {@code publishedAt}.
 *
 * <h2>What is actually guaranteed</h2>
 * <strong>At-least-once delivery.</strong> Not exactly-once. The publisher can
 * crash after the broker accepted the record but before {@code publishedAt} is
 * committed, and the next pass will publish it again. Using Kafka does not change
 * that. Duplicates are absorbed downstream by two independent mechanisms:
 * {@code processed_events} at the consumer, and the unique index on
 * {@code (user_id, external_ref)} at the row level. See docs/08.
 */
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Stable identity of the event itself, distinct from the row id.
     *
     * <p>This is what the consumer records in {@code processed_events}. It must be
     * generated once, when the event is created — regenerating it on a republish
     * would defeat consumer-side deduplication entirely.
     */
    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "aggregate_type", nullable = false, length = 50)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, length = 50)
    private String aggregateId;

    @Column(name = "event_type", nullable = false, length = 80)
    private String eventType;

    @Column(name = "topic", nullable = false, length = 120)
    private String topic;

    /**
     * Kafka message key. Set to the aggregate id so every event about one import
     * lands on the same partition and is therefore processed in order.
     */
    @Column(name = "partition_key", nullable = false, length = 120)
    private String partitionKey;

    /**
     * Serialised event body.
     *
     * <p>{@code @JdbcTypeCode(SqlTypes.JSON)} maps a Java {@code String} onto a
     * PostgreSQL {@code jsonb} column, so the payload is queryable and validated as
     * JSON by the database rather than being an opaque blob.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    /** Null until the broker has acknowledged the record. The poller's only predicate. */
    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    protected OutboxEvent() {
    }

    private OutboxEvent(UUID eventId, String aggregateType, String aggregateId, String eventType,
                        String topic, String partitionKey, String payload) {
        this.eventId = eventId;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.topic = topic;
        this.partitionKey = partitionKey;
        this.payload = payload;
        this.createdAt = Instant.now();
    }

    /**
     * Creates an unpublished event row.
     *
     * <p>{@code eventId} is supplied by the caller rather than generated here, so
     * the same value can be embedded in the serialised payload. The consumer
     * deduplicates on the id it reads from the payload, so the two must agree.
     */
    public static OutboxEvent pending(UUID eventId, String aggregateType, String aggregateId,
                                      String eventType, String topic, String partitionKey,
                                      String payload) {
        return new OutboxEvent(eventId, aggregateType, aggregateId, eventType,
                topic, partitionKey, payload);
    }

    public void markPublished() {
        this.publishedAt = Instant.now();
        this.lastError = null;
    }

    /** Records a failed attempt so the poller can back off and eventually park the row. */
    public void recordFailure(String reason) {
        this.attempts++;
        this.lastError = reason == null ? null
                : (reason.length() > 1000 ? reason.substring(0, 997) + "..." : reason);
    }

    public boolean isPublished() {
        return publishedAt != null;
    }

    public Long getId() {
        return id;
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public String getAggregateId() {
        return aggregateId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getTopic() {
        return topic;
    }

    public String getPartitionKey() {
        return partitionKey;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getLastError() {
        return lastError;
    }

    /** Never prints the payload: it carries user data. */
    @Override
    public String toString() {
        return "OutboxEvent{id=%d, eventId=%s, type=%s, topic=%s, attempts=%d, published=%s}"
                .formatted(id, eventId, eventType, topic, attempts, isPublished());
    }
}
