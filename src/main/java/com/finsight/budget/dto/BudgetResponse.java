package com.finsight.budget.dto;

import com.finsight.budget.domain.Budget;
import com.finsight.budget.domain.BudgetStatus;
import com.finsight.common.util.Money;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * A budget together with how it is tracking.
 *
 * <p>{@code spent}, {@code remaining}, {@code utilisationPct} and {@code status}
 * are all derived at read time from the month's actual transactions. Nothing here
 * is stored, so the figures cannot go stale behind a transaction edit.
 */
@Schema(name = "BudgetResponse")
public record BudgetResponse(
        Long id,

        @Schema(example = "2026-03")
        String month,

        @Schema(description = "Null for an overall budget")
        Long categoryId,

        @Schema(example = "Groceries", description = "\"All categories\" for an overall budget")
        String categoryName,

        @Schema(example = "25000.00", description = "The configured cap")
        BigDecimal amount,

        @Schema(example = "18420.00", description = "Actual expenses in the month within this scope")
        BigDecimal spent,

        @Schema(example = "6580.00", description = "amount - spent; negative when over budget")
        BigDecimal remaining,

        @Schema(example = "73.68", description = "spent as a percentage of amount")
        BigDecimal utilisationPct,

        @Schema(example = "80")
        short alertThresholdPct,

        @Schema(example = "ON_TRACK", allowableValues = {"ON_TRACK", "WARNING", "EXCEEDED"})
        BudgetStatus status
) {

    /** Label used for an overall budget, which has no category to name. */
    public static final String OVERALL_LABEL = "All categories";

    /**
     * Combines a budget with the spend measured for its scope.
     *
     * <p>Status thresholds, stated once here so the API and the report agree:
     * {@code EXCEEDED} when spend >= the cap, {@code WARNING} at or above the
     * configured percentage, otherwise {@code ON_TRACK}. "At or above" rather than
     * "above" so a budget set exactly to its threshold does warn.
     */
    public static BudgetResponse of(Budget budget, BigDecimal spent) {
        BigDecimal cap = Money.display(budget.getAmount());
        BigDecimal actual = Money.display(spent);
        BigDecimal utilisation = Money.percentageOf(actual, cap);

        BudgetStatus status;
        if (actual.compareTo(cap) >= 0) {
            status = BudgetStatus.EXCEEDED;
        } else if (utilisation.compareTo(BigDecimal.valueOf(budget.getAlertThresholdPct())) >= 0) {
            status = BudgetStatus.WARNING;
        } else {
            status = BudgetStatus.ON_TRACK;
        }

        return new BudgetResponse(
                budget.getId(),
                budget.month().toString(),
                budget.isOverall() ? null : budget.getCategory().getId(),
                budget.isOverall() ? OVERALL_LABEL : budget.getCategory().getName(),
                cap,
                actual,
                Money.subtract(cap, actual),
                utilisation,
                budget.getAlertThresholdPct(),
                status);
    }
}
