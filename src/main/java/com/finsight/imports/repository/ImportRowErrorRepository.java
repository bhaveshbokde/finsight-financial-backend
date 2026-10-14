package com.finsight.imports.repository;

import com.finsight.imports.domain.ImportRowError;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Data access for per-row import errors. */
public interface ImportRowErrorRepository extends JpaRepository<ImportRowError, Long> {

    Page<ImportRowError> findByImportJobIdOrderByRowNumberAsc(Long importJobId, Pageable pageable);

    long countByImportJobId(Long importJobId);

    /**
     * Clears previous errors before a re-processing pass.
     *
     * <p>Without this, a redelivered Kafka message would append a second copy of
     * every error and the job would appear to have twice as many problems as the
     * file actually has. The job counters are assigned rather than incremented for
     * the same reason.
     */
    @Modifying
    @Query("DELETE FROM ImportRowError e WHERE e.importJobId = :importJobId")
    int deleteByImportJobId(@Param("importJobId") Long importJobId);
}
