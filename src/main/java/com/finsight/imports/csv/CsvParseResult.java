package com.finsight.imports.csv;

import java.util.List;

/**
 * Outcome of parsing a whole file.
 *
 * <p>Both lists are populated: parsing does not stop at the first bad row. A user
 * who uploads 900 rows with 12 problems wants all 12 reported in one pass, not one
 * per re-upload.
 *
 * @param rows   rows that validated successfully
 * @param errors rows that did not, with their reasons
 */
public record CsvParseResult(List<ParsedTransactionRow> rows, List<CsvRowError> errors) {

    /** Total data rows seen, valid or not. Excludes the header. */
    public int totalRows() {
        return rows.size() + errors.size();
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    public static CsvParseResult empty() {
        return new CsvParseResult(List.of(), List.of());
    }
}
