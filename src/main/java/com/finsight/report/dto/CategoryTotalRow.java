package com.finsight.report.dto;

import java.math.BigDecimal;

/**
 * One {@code GROUP BY category} row.
 *
 * <p>{@code categoryId} and {@code categoryName} are null for transactions with no
 * category — the LEFT JOIN keeps them, and the service relabels that group as
 * "Uncategorized". Dropping those rows with an INNER JOIN would make the
 * breakdown silently fail to add up to the period's expense total, which is the
 * kind of discrepancy that is very hard to spot in a chart.
 */
public record CategoryTotalRow(Long categoryId, String categoryName,
                               BigDecimal total, long transactionCount) {

    public CategoryTotalRow {
        total = total == null ? BigDecimal.ZERO : total;
    }
}
