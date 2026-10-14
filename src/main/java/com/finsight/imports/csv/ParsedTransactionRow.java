package com.finsight.imports.csv;

import com.finsight.transaction.domain.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A CSV row that passed validation, ready to become a
 * {@link com.finsight.transaction.domain.Transaction}.
 *
 * @param rowNumber    1-based source line, carried through so a later database-level
 *                     failure can still be reported against the right line
 * @param type         INCOME or EXPENSE
 * @param amount       positive magnitude, already normalised to 4 decimal places
 * @param txnDate      calendar date
 * @param description  non-blank, trimmed
 * @param merchant     trimmed, or null
 * @param categoryName the raw name from the file, or null; resolved to an id by the importer
 */
public record ParsedTransactionRow(
        int rowNumber,
        TransactionType type,
        BigDecimal amount,
        LocalDate txnDate,
        String description,
        String merchant,
        String categoryName
) {
}
