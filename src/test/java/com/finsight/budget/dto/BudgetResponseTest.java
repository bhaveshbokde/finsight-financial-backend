package com.finsight.budget.dto;

import com.finsight.budget.domain.Budget;
import com.finsight.budget.domain.BudgetStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Budget-versus-actual arithmetic and the ON_TRACK / WARNING / EXCEEDED thresholds.
 *
 * <p>These are pure functions of (cap, spend, threshold), so they are tested
 * without a Spring context or a database.
 */
class BudgetResponseTest {

    private static final YearMonth MARCH = YearMonth.of(2026, 3);

    private Budget budgetOf(String amount, short threshold) {
        return Budget.overall(1L, MARCH, new BigDecimal(amount), threshold);
    }

    @ParameterizedTest(name = "spend {1} of cap {0} (threshold {2}%) -> {3}")
    @CsvSource({
            // under the threshold
            "10000.00, 0.00,     80, ON_TRACK",
            "10000.00, 4000.00,  80, ON_TRACK",
            "10000.00, 7999.00,  80, ON_TRACK",
            // at or above the threshold but below the cap
            "10000.00, 8000.00,  80, WARNING",
            "10000.00, 9999.99,  80, WARNING",
            // at or above the cap
            "10000.00, 10000.00, 80, EXCEEDED",
            "10000.00, 12500.00, 80, EXCEEDED",
            // a different threshold moves only the WARNING boundary
            "10000.00, 5000.00,  50, WARNING",
            "10000.00, 4999.00,  50, ON_TRACK",
            // a 100% threshold means WARNING and EXCEEDED coincide; EXCEEDED wins
            "10000.00, 10000.00, 100, EXCEEDED",
            "10000.00, 9999.00,  100, ON_TRACK"
    })
    void statusThresholds(String cap, String spend, short threshold, BudgetStatus expected) {
        BudgetResponse response = BudgetResponse.of(budgetOf(cap, threshold), new BigDecimal(spend));
        assertThat(response.status()).isEqualTo(expected);
    }

    @Test
    @DisplayName("exactly at the threshold warns, because 'at or above' is the rule")
    void thresholdIsInclusive() {
        // A budget sitting precisely on its alert point should alert. Using a
        // strict '>' here would silently skip the single most likely value.
        BudgetResponse response = BudgetResponse.of(
                budgetOf("10000.00", (short) 80), new BigDecimal("8000.00"));

        assertThat(response.utilisationPct()).isEqualByComparingTo("80.00");
        assertThat(response.status()).isEqualTo(BudgetStatus.WARNING);
    }

    @Test
    @DisplayName("remaining goes negative when over budget rather than clamping to zero")
    void negativeRemaining() {
        // Clamping would hide how far over the user is, which is the one number
        // they most want when the status says EXCEEDED.
        BudgetResponse response = BudgetResponse.of(
                budgetOf("10000.00", (short) 80), new BigDecimal("12500.00"));

        assertThat(response.remaining()).isEqualByComparingTo("-2500.00");
        assertThat(response.utilisationPct()).isEqualByComparingTo("125.00");
    }

    @Test
    @DisplayName("zero spend reports 0% rather than failing")
    void zeroSpend() {
        BudgetResponse response = BudgetResponse.of(
                budgetOf("10000.00", (short) 80), BigDecimal.ZERO);

        assertThat(response.spent()).isEqualByComparingTo("0.00");
        assertThat(response.remaining()).isEqualByComparingTo("10000.00");
        assertThat(response.utilisationPct()).isEqualByComparingTo("0.00");
        assertThat(response.status()).isEqualTo(BudgetStatus.ON_TRACK);
    }

    @Test
    @DisplayName("a null spend is treated as zero")
    void nullSpend() {
        // The per-category spend map returns null for a category with no
        // transactions this month; that must read as zero, not blow up.
        BudgetResponse response = BudgetResponse.of(budgetOf("10000.00", (short) 80), null);
        assertThat(response.spent()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("an overall budget is labelled rather than left with a null category")
    void overallBudgetLabel() {
        BudgetResponse response = BudgetResponse.of(
                budgetOf("10000.00", (short) 80), new BigDecimal("500.00"));

        assertThat(response.categoryId()).isNull();
        assertThat(response.categoryName()).isEqualTo(BudgetResponse.OVERALL_LABEL);
    }

    @Test
    @DisplayName("the month round-trips as YYYY-MM")
    void monthFormat() {
        BudgetResponse response = BudgetResponse.of(
                budgetOf("10000.00", (short) 80), BigDecimal.ZERO);

        assertThat(response.month()).isEqualTo("2026-03");
    }

    @Test
    @DisplayName("amounts are rounded for presentation but stay exact")
    void presentationRounding() {
        BudgetResponse response = BudgetResponse.of(
                budgetOf("10000.0000", (short) 80), new BigDecimal("3333.3333"));

        assertThat(response.spent()).isEqualByComparingTo("3333.33");
        assertThat(response.spent().scale()).isEqualTo(2);
        assertThat(response.utilisationPct()).isEqualByComparingTo("33.33");
    }
}
