package com.finsight.report.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * One row of a category breakdown.
 *
 * <p>{@code categoryId} is null for the synthetic "Uncategorized" group, which
 * collects transactions with no category. That group is included so the rows sum
 * to the period total.
 */
@Schema(name = "CategoryBreakdownItem")
public record CategoryBreakdownItem(

        @Schema(description = "Null for the Uncategorized group") Long categoryId,
        @Schema(example = "Groceries") String categoryName,
        @Schema(example = "18420.00") BigDecimal total,
        @Schema(example = "29.78", description = "Share of the period total for this transaction type")
        BigDecimal percentageOfTotal,
        @Schema(example = "34") long transactionCount

) implements Serializable {

    /** Label for transactions that carry no category. */
    public static final String UNCATEGORIZED = "Uncategorized";
}
