package com.finsight.imports.domain;

import com.finsight.common.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;

/**
 * One uploaded CSV file and the outcome of processing it.
 *
 * <p>The row counters are the import's whole result summary:
 * {@code totalRows = importedRows + duplicateRows + failedRows} once the job
 * reaches a terminal state.
 */
@Entity
@Table(name = "import_jobs")
public class ImportJob extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "original_filename", nullable = false, length = 255)
    private String originalFilename;

    /**
     * SHA-256 of the uploaded bytes.
     *
     * <p>Serves two purposes: telling the user "you have uploaded this exact file
     * before", and seeding every row's idempotency key as
     * {@code sha256(fileChecksum + ':' + rowNumber)}. Because the key depends only
     * on content and position, re-uploading an identical file imports nothing new —
     * it reports every row as a duplicate.
     */
    @Column(name = "file_checksum", nullable = false, length = 64)
    private String fileChecksum;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 25)
    private ImportStatus status = ImportStatus.ACCEPTED;

    @Column(name = "total_rows", nullable = false)
    private int totalRows;

    @Column(name = "imported_rows", nullable = false)
    private int importedRows;

    @Column(name = "duplicate_rows", nullable = false)
    private int duplicateRows;

    @Column(name = "failed_rows", nullable = false)
    private int failedRows;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected ImportJob() {
    }

    private ImportJob(Long userId, String originalFilename, String fileChecksum, long sizeBytes,
                      int totalRows) {
        this.userId = userId;
        this.originalFilename = originalFilename;
        this.fileChecksum = fileChecksum;
        this.sizeBytes = sizeBytes;
        this.totalRows = totalRows;
        this.status = ImportStatus.ACCEPTED;
    }

    public static ImportJob accepted(Long userId, String originalFilename, String fileChecksum,
                                     long sizeBytes, int totalRows) {
        return new ImportJob(Objects.requireNonNull(userId, "userId"),
                originalFilename, fileChecksum, sizeBytes, totalRows);
    }

    /** Marks the job as claimed by a processor. Idempotent — a redelivery re-stamps the time. */
    public void markProcessing() {
        this.status = ImportStatus.PROCESSING;
        this.startedAt = Instant.now();
    }

    /**
     * Records the outcome and picks the terminal status.
     *
     * <p>Counters are <em>assigned</em>, not incremented, so re-processing the same
     * job after a redelivery produces the same final numbers rather than doubling
     * them. That is the job-level half of the idempotency story; the row-level half
     * is the unique index on {@code (user_id, external_ref)}.
     */
    public void markCompleted(int imported, int duplicates, int failed) {
        this.importedRows = imported;
        this.duplicateRows = duplicates;
        this.failedRows = failed;
        this.status = failed > 0 ? ImportStatus.COMPLETED_WITH_ERRORS : ImportStatus.COMPLETED;
        this.completedAt = Instant.now();
        this.errorMessage = null;
    }

    /** Terminal failure: nothing from this job is imported. */
    public void markFailed(String reason) {
        this.status = ImportStatus.FAILED;
        this.completedAt = Instant.now();
        this.errorMessage = truncate(reason, 1000);
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public String getFileChecksum() {
        return fileChecksum;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public ImportStatus getStatus() {
        return status;
    }

    public int getTotalRows() {
        return totalRows;
    }

    public void setTotalRows(int totalRows) {
        this.totalRows = totalRows;
    }

    public int getImportedRows() {
        return importedRows;
    }

    public int getDuplicateRows() {
        return duplicateRows;
    }

    public int getFailedRows() {
        return failedRows;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max - 3) + "...";
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ImportJob job)) {
            return false;
        }
        return id != null && id.equals(job.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(ImportJob.class);
    }

    @Override
    public String toString() {
        return "ImportJob{id=%d, userId=%d, status=%s, total=%d, imported=%d, dup=%d, failed=%d}"
                .formatted(id, userId, status, totalRows, importedRows, duplicateRows, failedRows);
    }
}
