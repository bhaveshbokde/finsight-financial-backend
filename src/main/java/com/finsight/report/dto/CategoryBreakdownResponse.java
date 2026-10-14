package com.finsight.report.dto;

import com.finsight.common.dto.PageResponse;
import com.finsight.transaction.domain.TransactionType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A paginated category breakdown.
 *
 * <p>{@code grandTotal} is the total across <em>all</em> categories, not just the
 * ones on this page — otherwise the percentages on page 2 would be computed
 * against a different denominator and would not add up to 100.
 */
@Schema(name = "CategoryBreakdownResponse")
public record CategoryBreakdownResponse(

        @Schema(example = "2026-03-01") LocalDate from,
        @Schema(example = "2026-03-31") LocalDate to,

        @Schema(example = "EXPENSE") TransactionType type,

        @Schema(example = "61840.25", description = "Total across every category, not just this page")
        BigDecimal grandTotal,

        PageResponse<CategoryBreakdownItem> categories

) implements Serializable {
}
