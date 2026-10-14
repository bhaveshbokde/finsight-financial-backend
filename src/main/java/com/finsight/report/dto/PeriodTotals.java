package com.finsight.report.dto;

import java.math.BigDecimal;

/**
 * Raw aggregate row returned by the totals query.
 *
 * <p>An internal shape, not part of the API — {@link SummaryResponse} is what
 * clients see. Keeping them separate means the query can change (an extra column,
 * a different grouping) without altering the published contract.
 *
 * <p>The compact constructor substitutes zero for null. {@code SUM(...)} over a
 * period with no matching rows returns SQL NULL, and every caller would otherwise
 * need its own null check — the one place where that can be handled correctly is
 * here, at the boundary where the database's three-valued logic meets Java.
 */
public record PeriodTotals(BigDecimal totalIncome, BigDecimal totalExpense, long transactionCount) {

    public PeriodTotals {
        totalIncome = totalIncome == null ? BigDecimal.ZERO : totalIncome;
        totalExpense = totalExpense == null ? BigDecimal.ZERO : totalExpense;
    }

    public static PeriodTotals empty() {
        return new PeriodTotals(BigDecimal.ZERO, BigDecimal.ZERO, 0L);
    }
}
