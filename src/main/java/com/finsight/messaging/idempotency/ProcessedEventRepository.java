package com.finsight.messaging.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

/** Data access for the consumer-side idempotency ledger. */
public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, ProcessedEvent.Key> {

    /**
     * Atomically claims an event for processing.
     *
     * <p>Returns 1 when this call won the claim and 0 when the event was already
     * recorded. The check and the insert are one statement, so two consumer threads
     * racing on the same redelivered record cannot both see "not processed" and both
     * do the work — which is exactly what a {@code existsById()} followed by a
     * {@code save()} would allow.
     *
     * <p>{@code ON CONFLICT DO NOTHING} rather than a caught constraint violation:
     * an exception would mark the surrounding transaction rollback-only in
     * PostgreSQL, forcing a savepoint dance for what is a completely normal outcome.
     *
     * <p>Native SQL because JPQL has no {@code ON CONFLICT}.
     */
    @Modifying
    @Query(value = """
            INSERT INTO processed_events (event_id, consumer_group, processed_at)
            VALUES (:eventId, :consumerGroup, now())
            ON CONFLICT (event_id, consumer_group) DO NOTHING
            """, nativeQuery = true)
    int claim(@Param("eventId") UUID eventId, @Param("consumerGroup") String consumerGroup);

    @Query(value = """
            SELECT COUNT(*) > 0 FROM processed_events
            WHERE event_id = :eventId AND consumer_group = :consumerGroup
            """, nativeQuery = true)
    boolean wasProcessed(@Param("eventId") UUID eventId,
                         @Param("consumerGroup") String consumerGroup);
}
