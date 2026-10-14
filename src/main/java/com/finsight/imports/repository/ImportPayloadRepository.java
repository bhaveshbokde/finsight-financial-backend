package com.finsight.imports.repository;

import com.finsight.imports.domain.ImportPayload;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Data access for the stored CSV text of an upload. */
public interface ImportPayloadRepository extends JpaRepository<ImportPayload, Long> {

    /**
     * Removes the stored file content once a job has reached a terminal state.
     *
     * <p>The parsed transactions are the durable result; keeping every uploaded
     * file forever would grow the database without bound for no benefit, and would
     * retain the user's raw financial data longer than necessary.
     */
    @Modifying
    @Query("DELETE FROM ImportPayload p WHERE p.importJobId = :importJobId")
    int deleteByImportJobId(@Param("importJobId") Long importJobId);
}
