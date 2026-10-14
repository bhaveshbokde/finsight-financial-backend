package com.finsight.imports.dto;

import com.finsight.imports.domain.ImportJob;
import com.finsight.imports.domain.ImportStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Status and result summary of an import.
 *
 * <p>This is what {@code GET /api/v1/imports/{id}} returns and what a client polls
 * after a 202. The counters satisfy
 * {@code totalRows = importedRows + duplicateRows + failedRows} once
 * {@code status.isTerminal()}.
 */
@Schema(name = "ImportJobResponse")
public record ImportJobResponse(

        Long id,

        @Schema(example = "march-statement.csv")
        String filename,

        @Schema(example = "COMPLETED_WITH_ERRORS",
                allowableValues = {"ACCEPTED", "PROCESSING", "COMPLETED", "COMPLETED_WITH_ERRORS", "FAILED"})
        ImportStatus status,

        @Schema(example = "true", description = "True when no further state change is expected")
        boolean terminal,

        @Schema(example = "1200", description = "Data rows in the file, excluding the header")
        int totalRows,

        @Schema(example = "1150", description = "Rows inserted as new transactions")
        int importedRows,

        @Schema(example = "40",
                description = "Rows skipped because an identical idempotency key already existed")
        int duplicateRows,

        @Schema(example = "10", description = "Rows rejected; see GET /api/v1/imports/{id}/errors")
        int failedRows,

        @Schema(example = "122880")
        long sizeBytes,

        @Schema(description = "Populated only when status is FAILED")
        String errorMessage,

        @Schema(description = "True when this exact file had already been uploaded before")
        boolean previouslyUploaded,

        Instant createdAt,
        Instant startedAt,
        Instant completedAt
) {

    public static ImportJobResponse from(ImportJob job, boolean previouslyUploaded) {
        return new ImportJobResponse(
                job.getId(),
                job.getOriginalFilename(),
                job.getStatus(),
                job.getStatus().isTerminal(),
                job.getTotalRows(),
                job.getImportedRows(),
                job.getDuplicateRows(),
                job.getFailedRows(),
                job.getSizeBytes(),
                job.getErrorMessage(),
                previouslyUploaded,
                job.getCreatedAt(),
                job.getStartedAt(),
                job.getCompletedAt());
    }

    public static ImportJobResponse from(ImportJob job) {
        return from(job, false);
    }
}
