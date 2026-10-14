package com.finsight.imports.csv;

/**
 * A validation failure for one CSV row.
 *
 * @param rowNumber 1-based line number as a spreadsheet would show it (header = 1)
 * @param rawRow    the original line, for the user to locate and fix
 * @param code      stable machine-readable reason
 * @param message   human-readable explanation naming the offending column
 */
public record CsvRowError(int rowNumber, String rawRow, String code, String message) {

    // Stable codes. Clients and tests branch on these; the messages may be reworded.
    public static final String MISSING_COLUMN = "MISSING_COLUMN";
    public static final String INVALID_DATE = "INVALID_DATE";
    public static final String INVALID_AMOUNT = "INVALID_AMOUNT";
    public static final String INVALID_TYPE = "INVALID_TYPE";
    public static final String MISSING_DESCRIPTION = "MISSING_DESCRIPTION";
    public static final String UNKNOWN_CATEGORY = "UNKNOWN_CATEGORY";
    public static final String CATEGORY_TYPE_MISMATCH = "CATEGORY_TYPE_MISMATCH";
    public static final String FIELD_TOO_LONG = "FIELD_TOO_LONG";
    public static final String MALFORMED_ROW = "MALFORMED_ROW";
}
