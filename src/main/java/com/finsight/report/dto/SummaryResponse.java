package com.finsight.report.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Income, expense and net cash flow over an inclusive date range.
 *
 * <p>Implements {@link Serializable} because this type is stored in Redis. The
 * cache uses JSON serialization (see {@code RedisCacheConfig}), so the marker is
 * belt-and-braces rather than load-bearing — but a cached type that cannot be
 * serialised fails at runtime, inside a cache put, which is an unpleasant place to
 * discover it.
 */
@Schema(name = "SummaryResponse", description = "Totals for a period")
public record SummaryResponse(

        @Schema(example = "2026-03-01") LocalDate from,
        @Schema(example = "2026-03-31") LocalDate to,

        @Schema(example = "95000.00") BigDecimal totalIncome,
        @Schema(example = "61840.25") BigDecimal totalExpense,

        @Schema(example = "33159.75",
                description = "totalIncome - totalExpense. Negative means the period spent more than it earned.")
        BigDecimal netCashFlow,

        @Schema(example = "142", description = "Number of transactions in the period")
        long transactionCount,

        @Schema(example = "65.09", description = "Expenses as a percentage of income; 0 when income is 0")
        BigDecimal expenseRatioPct

) implements Serializable {
}
