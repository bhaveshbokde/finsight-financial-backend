package com.finsight.imports.repository;

import com.finsight.imports.domain.ImportJob;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** Data access for {@link ImportJob}. User-scoped finders only, except the consumer's lookup. */
public interface ImportJobRepository extends JpaRepository<ImportJob, Long> {

    /** Ownership-scoped fetch used by every API path. */
    Optional<ImportJob> findByIdAndUserId(Long id, Long userId);

    Page<ImportJob> findByUserIdOrderByCreatedAtDescIdDesc(Long userId, Pageable pageable);

    /**
     * "Have I uploaded this exact file before?"
     *
     * <p>Not a uniqueness constraint — re-uploading is allowed, and thanks to the
     * per-row idempotency keys it imports nothing new. This finder just lets the
     * API tell the user what happened instead of leaving them puzzled by a summary
     * of "0 imported, 1200 duplicates".
     */
    List<ImportJob> findByUserIdAndFileChecksumOrderByCreatedAtDesc(Long userId, String fileChecksum);
}
