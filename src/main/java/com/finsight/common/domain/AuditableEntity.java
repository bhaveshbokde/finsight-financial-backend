package com.finsight.common.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

/**
 * Shared audit columns and the optimistic-locking version.
 *
 * <p>{@code @MappedSuperclass} contributes these columns to every subclass table
 * without creating a table of its own and without introducing an inheritance
 * hierarchy in the database.
 *
 * <p>{@code @Version} gives optimistic locking: Hibernate appends
 * {@code AND version = ?} to each UPDATE and raises
 * {@link jakarta.persistence.OptimisticLockException} when zero rows match, which
 * {@code GlobalExceptionHandler} converts to HTTP 409. This is the right default
 * for a web application — pessimistic row locks held across a user's think-time
 * would serialise unrelated requests. See docs/12 for where this matters
 * (concurrent budget updates) and where it does not.
 *
 * <p>Timestamps are {@link Instant} mapped to {@code TIMESTAMPTZ}; the application
 * works exclusively in UTC and converts only at presentation boundaries.
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class AuditableEntity {

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }
}
