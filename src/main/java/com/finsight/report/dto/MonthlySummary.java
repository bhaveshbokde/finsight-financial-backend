package com.finsight.report.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * One month of a monthly series.
 *
 * <p>Months with no transactions are present with zeros rather than absent. A
 * client plotting the series should not have to infer gaps, and "no data" and
 * "zero spend" look identical on a chart either way — so the API commits to the
 * dense form and says so.
 */
@Schema(name = "MonthlySummary")
public record MonthlySummary(

        @Schema(example = "2026-03") String month,
        @Schema(example = "95000.00") BigDecimal totalIncome,
        @Schema(example = "61840.25") BigDecimal totalExpense,
        @Schema(example = "33159.75") BigDecimal netCashFlow,
        @Schema(example = "142") long transactionCount

) implements Serializable {
}
