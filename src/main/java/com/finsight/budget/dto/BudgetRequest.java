package com.finsight.budget.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;

/**
 * Create/update payload for a budget.
 *
 * <p>{@code month} is a {@code "YYYY-MM"} string rather than a date, because a
 * budget covers a whole month and accepting {@code 2026-03-17} would raise the
 * question of what the other 30 days mean. The {@code @Pattern} rejects the shape
 * early; {@code DateRanges.parseMonth} turns it into a {@code YearMonth}.
 *
 * <p>A null {@code categoryId} means an overall budget for the month.
 */
@Schema(name = "BudgetRequest")
public record BudgetRequest(

        @NotBlank(message = "month is required")
        @Pattern(regexp = "^\\d{4}-(0[1-9]|1[0-2])$", message = "must be in YYYY-MM format, e.g. 2026-03")
        @Schema(example = "2026-03")
        String month,

        @NotNull(message = "amount is required")
        @DecimalMin(value = "0.0", inclusive = false, message = "must be greater than 0")
        @DecimalMax(value = "999999999999999.9999", message = "exceeds the maximum supported amount")
        @Digits(integer = 15, fraction = 4, message = "must have at most 4 decimal places")
        @Schema(example = "25000.00")
        BigDecimal amount,

        @Schema(example = "7",
                description = "Omit for an overall monthly budget; must be an EXPENSE category otherwise")
        Long categoryId,

        @Min(value = 1, message = "must be at least 1")
        @Max(value = 100, message = "must not exceed 100")
        @Schema(example = "80", defaultValue = "80",
                description = "Utilisation percentage at which status becomes WARNING")
        Short alertThresholdPct
) {
}
