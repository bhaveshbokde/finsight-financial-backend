package com.finsight.report.dto;

import java.math.BigDecimal;

/**
 * One {@code GROUP BY year, month} row.
 *
 * <p>Internal. The service turns these into a dense {@link MonthlySummary} series,
 * filling in the months that produced no row at all — SQL returns nothing for a
 * month with no transactions, but a chart needs the gap to be an explicit zero.
 */
public record MonthlyTotalsRow(Integer year, Integer month,
                               BigDecimal totalIncome, BigDecimal totalExpense,
                               long transactionCount) {

    public MonthlyTotalsRow {
        totalIncome = totalIncome == null ? BigDecimal.ZERO : totalIncome;
        totalExpense = totalExpense == null ? BigDecimal.ZERO : totalExpense;
    }
}
