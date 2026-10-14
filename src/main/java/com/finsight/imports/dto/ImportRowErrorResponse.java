package com.finsight.imports.dto;

import com.finsight.imports.domain.ImportRowError;
import io.swagger.v3.oas.annotations.media.Schema;

/** One rejected row, as returned by {@code GET /api/v1/imports/{id}/errors}. */
@Schema(name = "ImportRowErrorResponse")
public record ImportRowErrorResponse(

        @Schema(example = "57", description = "1-based line number in the uploaded file; the header is line 1")
        int rowNumber,

        @Schema(example = "INVALID_DATE")
        String errorCode,

        @Schema(example = "Could not read '31/02/2026' as a date. Use yyyy-MM-dd (for example 2026-03-14).")
        String message,

        @Schema(description = "The offending line, so the user can find and fix it")
        String rawRow
) {
    public static ImportRowErrorResponse from(ImportRowError error) {
        return new ImportRowErrorResponse(
                error.getRowNumber(),
                error.getErrorCode(),
                error.getMessage(),
                error.getRawRow());
    }
}
