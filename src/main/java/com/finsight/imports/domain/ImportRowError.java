package com.finsight.imports.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One rejected CSV row.
 *
 * <p>Stored per row rather than concatenated into the job's {@code errorMessage},
 * so a user whose 900-row file had 12 bad rows can see exactly which twelve and
 * why — and fix them — instead of being told "12 rows failed".
 */
@Entity
@Table(name = "import_row_errors")
public class ImportRowError {

    /** Maximum characters of the offending line kept for display. */
    private static final int RAW_ROW_LIMIT = 1000;
    private static final int MESSAGE_LIMIT = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "import_job_id", nullable = false, updatable = false)
    private Long importJobId;

    /** 1-based, counting the header as row 1, so it matches what a spreadsheet shows. */
    @Column(name = "row_number", nullable = false)
    private int rowNumber;

    @Column(name = "raw_row", length = RAW_ROW_LIMIT)
    private String rawRow;

    @Column(name = "error_code", nullable = false, length = 50)
    private String errorCode;

    @Column(name = "message", nullable = false, length = MESSAGE_LIMIT)
    private String message;

    protected ImportRowError() {
    }

    public ImportRowError(Long importJobId, int rowNumber, String rawRow,
                          String errorCode, String message) {
        this.importJobId = importJobId;
        this.rowNumber = rowNumber;
        this.rawRow = truncate(rawRow, RAW_ROW_LIMIT);
        this.errorCode = errorCode;
        this.message = truncate(message, MESSAGE_LIMIT);
    }

    public Long getId() {
        return id;
    }

    public Long getImportJobId() {
        return importJobId;
    }

    public int getRowNumber() {
        return rowNumber;
    }

    public String getRawRow() {
        return rawRow;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getMessage() {
        return message;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max - 3) + "...";
    }
}
