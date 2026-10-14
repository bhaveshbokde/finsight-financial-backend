package com.finsight.outbox.repository;

import com.finsight.outbox.domain.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/** Data access for the transactional outbox. */
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * Claims a batch of unpublished events for this poller instance.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} is what makes the poller safe to run on
     * several application instances at once: each instance locks the rows it reads,
     * and other instances step over the locked rows instead of blocking on them.
     * Without {@code SKIP LOCKED} the instances would serialise behind one another;
     * without {@code FOR UPDATE} they would all publish the same events.
     *
     * <p>A native query because JPQL has no way to express {@code SKIP LOCKED}, and
     * {@code @Lock(PESSIMISTIC_WRITE)} alone would block rather than skip.
     *
     * <p>Rows that have exhausted their attempt budget are left behind deliberately —
     * they are "parked" for an operator rather than retried forever.
     */
    @Query(value = """
            SELECT * FROM outbox_events
            WHERE published_at IS NULL
              AND attempts < :maxAttempts
            ORDER BY created_at, id
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> claimUnpublished(@Param("maxAttempts") int maxAttempts,
                                       @Param("batchSize") int batchSize);

    /** Rows that gave up. Surfaced as a metric so a Kafka outage is visible, not silent. */
    @Query("SELECT COUNT(e) FROM OutboxEvent e WHERE e.publishedAt IS NULL AND e.attempts >= :maxAttempts")
    long countParked(@Param("maxAttempts") int maxAttempts);

    /** Backlog depth, including rows not yet attempted. */
    @Query("SELECT COUNT(e) FROM OutboxEvent e WHERE e.publishedAt IS NULL")
    long countPending();

    /** Used by tests and by the status endpoint to confirm an event was recorded. */
    List<OutboxEvent> findByAggregateTypeAndAggregateId(String aggregateType, String aggregateId);
}
