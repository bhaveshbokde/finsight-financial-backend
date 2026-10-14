package com.finsight.report.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * A dense monthly series plus the totals across it.
 *
 * <p>The totals are returned alongside the months rather than left to the client
 * to sum, so the endpoint's own rounding is authoritative: summing twelve
 * already-rounded monthly figures can differ in the last paisa from rounding the
 * sum once.
 */
@Schema(name = "MonthlySeriesResponse")
public record MonthlySeriesResponse(

        @Schema(example = "2026-01") String from,
        @Schema(example = "2026-12") String to,

        List<MonthlySummary> months,

        @Schema(example = "1140000.00") BigDecimal totalIncome,
        @Schema(example = "742083.00") BigDecimal totalExpense,
        @Schema(example = "397917.00") BigDecimal netCashFlow,

        @Schema(example = "95000.00", description = "Mean monthly income across the whole requested span")
        BigDecimal averageMonthlyIncome,
        @Schema(example = "61840.25", description = "Mean monthly expense across the whole requested span")
        BigDecimal averageMonthlyExpense

) implements Serializable {
}
