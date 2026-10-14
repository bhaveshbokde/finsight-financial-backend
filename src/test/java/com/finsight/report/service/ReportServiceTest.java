package com.finsight.report.service;

import com.finsight.common.error.BusinessRuleException;
import com.finsight.report.dto.CategoryBreakdownResponse;
import com.finsight.report.dto.CategoryTotalRow;
import com.finsight.report.dto.MonthlySeriesResponse;
import com.finsight.report.dto.MonthlySummary;
import com.finsight.report.dto.MonthlyTotalsRow;
import com.finsight.report.dto.PeriodTotals;
import com.finsight.report.dto.SummaryResponse;
import com.finsight.report.repository.ReportRepository;
import com.finsight.transaction.domain.TransactionType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * Report arithmetic, densification and edge cases.
 *
 * <p>The repository is mocked so each scenario — an empty period, a month with no
 * rows, a single category — can be set up exactly, including cases that are
 * awkward to arrange in a real database.
 */
@ExtendWith(MockitoExtension.class)
class ReportServiceTest {

    private static final Long USER_ID = 7L;
    private static final LocalDate MARCH_START = LocalDate.of(2026, 3, 1);
    private static final LocalDate MARCH_END = LocalDate.of(2026, 3, 31);

    @Mock
    private ReportRepository reportRepository;

    @InjectMocks
    private ReportService reportService;

    // ---------------------------------------------------------------- summary --

    @Nested
    @DisplayName("summary")
    class Summary {

        @Test
        @DisplayName("computes net cash flow as income minus expense")
        void netCashFlow() {
            when(reportRepository.totals(anyLong(), any(), any(), any(), any()))
                    .thenReturn(new PeriodTotals(
                            new BigDecimal("95000.0000"), new BigDecimal("61840.2500"), 142L));

            SummaryResponse summary = reportService.summary(USER_ID, MARCH_START, MARCH_END);

            assertThat(summary.totalIncome()).isEqualByComparingTo("95000.00");
            assertThat(summary.totalExpense()).isEqualByComparingTo("61840.25");
            assertThat(summary.netCashFlow()).isEqualByComparingTo("33159.75");
            assertThat(summary.transactionCount()).isEqualTo(142L);
        }

        @Test
        @DisplayName("returns a negative net when expenses exceed income")
        void negativeNet() {
            when(reportRepository.totals(anyLong(), any(), any(), any(), any()))
                    .thenReturn(new PeriodTotals(
                            new BigDecimal("1000.0000"), new BigDecimal("2500.0000"), 5L));

            assertThat(reportService.summary(USER_ID, MARCH_START, MARCH_END).netCashFlow())
                    .isEqualByComparingTo("-1500.00");
        }

        @Test
        @DisplayName("reports zeros for a period with no transactions")
        void emptyPeriod() {
            // SUM() over no rows is SQL NULL. PeriodTotals' compact constructor
            // turns that into zero once, at the boundary, so no caller needs a
            // null check and the response never contains null amounts.
            when(reportRepository.totals(anyLong(), any(), any(), any(), any()))
                    .thenReturn(new PeriodTotals(null, null, 0L));

            SummaryResponse summary = reportService.summary(USER_ID, MARCH_START, MARCH_END);

            assertThat(summary.totalIncome()).isEqualByComparingTo("0.00");
            assertThat(summary.totalExpense()).isEqualByComparingTo("0.00");
            assertThat(summary.netCashFlow()).isEqualByComparingTo("0.00");
            assertThat(summary.transactionCount()).isZero();
            assertThat(summary.expenseRatioPct()).isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("does not divide by zero when a period has expenses but no income")
        void expensesWithoutIncome() {
            // A real and common case — a month of spending with the salary landing
            // on the 1st of the next month. It must not be an error.
            when(reportRepository.totals(anyLong(), any(), any(), any(), any()))
                    .thenReturn(new PeriodTotals(BigDecimal.ZERO, new BigDecimal("5000.0000"), 10L));

            SummaryResponse summary = reportService.summary(USER_ID, MARCH_START, MARCH_END);

            assertThat(summary.expenseRatioPct()).isEqualByComparingTo("0.00");
            assertThat(summary.netCashFlow()).isEqualByComparingTo("-5000.00");
        }

        @Test
        @DisplayName("echoes the requested bounds so the client can label the result")
        void echoesBounds() {
            when(reportRepository.totals(anyLong(), any(), any(), any(), any()))
                    .thenReturn(PeriodTotals.empty());

            SummaryResponse summary = reportService.summary(USER_ID, MARCH_START, MARCH_END);

            assertThat(summary.from()).isEqualTo(MARCH_START);
            assertThat(summary.to()).isEqualTo(MARCH_END);
        }

        @Test
        @DisplayName("rejects an inverted range before querying")
        void rejectsInvertedRange() {
            assertThatThrownBy(() -> reportService.summary(USER_ID, MARCH_END, MARCH_START))
                    .isInstanceOf(BusinessRuleException.class);
        }
    }

    // ---------------------------------------------------------- monthly series --

    @Nested
    @DisplayName("monthly series")
    class Monthly {

        @Test
        @DisplayName("fills in months that produced no database row")
        void densifiesSeries() {
            // SQL returns nothing for a month with no transactions. A chart needs
            // that gap to be an explicit zero, not a missing point.
            when(reportRepository.monthlyTotals(anyLong(), any(), any(), any(), any()))
                    .thenReturn(List.of(
                            new MonthlyTotalsRow(2026, 1, new BigDecimal("1000"), new BigDecimal("400"), 3L),
                            new MonthlyTotalsRow(2026, 3, new BigDecimal("2000"), new BigDecimal("800"), 5L)));

            MonthlySeriesResponse series = reportService.monthlySeries(
                    USER_ID, YearMonth.of(2026, 1), YearMonth.of(2026, 4));

            assertThat(series.months()).hasSize(4);
            assertThat(series.months()).extracting(MonthlySummary::month)
                    .containsExactly("2026-01", "2026-02", "2026-03", "2026-04");

            // February and April had no rows at all.
            assertThat(series.months().get(1).totalIncome()).isEqualByComparingTo("0.00");
            assertThat(series.months().get(1).transactionCount()).isZero();
            assertThat(series.months().get(3).totalExpense()).isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("keeps months in chronological order")
        void chronologicalOrder() {
            when(reportRepository.monthlyTotals(anyLong(), any(), any(), any(), any()))
                    .thenReturn(List.of());

            MonthlySeriesResponse series = reportService.monthlySeries(
                    USER_ID, YearMonth.of(2025, 11), YearMonth.of(2026, 2));

            assertThat(series.months()).extracting(MonthlySummary::month)
                    .containsExactly("2025-11", "2025-12", "2026-01", "2026-02");
        }

        @Test
        @DisplayName("averages over months requested, not months with activity")
        void averagesUseRequestedSpan() {
            // "Average monthly spend over the last 12 months" means dividing by 12.
            // Dividing by the number of active months would overstate it, sometimes
            // dramatically for a sparse history.
            when(reportRepository.monthlyTotals(anyLong(), any(), any(), any(), any()))
                    .thenReturn(List.of(
                            new MonthlyTotalsRow(2026, 1, new BigDecimal("1200"), new BigDecimal("600"), 2L)));

            MonthlySeriesResponse series = reportService.monthlySeries(
                    USER_ID, YearMonth.of(2026, 1), YearMonth.of(2026, 4));   // 4 months

            assertThat(series.totalIncome()).isEqualByComparingTo("1200.00");
            assertThat(series.averageMonthlyIncome()).isEqualByComparingTo("300.00");   // 1200 / 4
            assertThat(series.averageMonthlyExpense()).isEqualByComparingTo("150.00");  // 600 / 4
        }

        @Test
        @DisplayName("handles a single-month span")
        void singleMonth() {
            when(reportRepository.monthlyTotals(anyLong(), any(), any(), any(), any()))
                    .thenReturn(List.of());

            MonthlySeriesResponse series = reportService.monthlySeries(
                    USER_ID, YearMonth.of(2026, 3), YearMonth.of(2026, 3));

            assertThat(series.months()).hasSize(1);
        }

        @Test
        @DisplayName("spans a year boundary correctly")
        void crossesYearBoundary() {
            when(reportRepository.monthlyTotals(anyLong(), any(), any(), any(), any()))
                    .thenReturn(List.of());

            MonthlySeriesResponse series = reportService.monthlySeries(
                    USER_ID, YearMonth.of(2025, 12), YearMonth.of(2026, 1));

            assertThat(series.months()).extracting(MonthlySummary::month)
                    .containsExactly("2025-12", "2026-01");
        }

        @Test
        @DisplayName("rejects an inverted month range")
        void rejectsInvertedMonths() {
            assertThatThrownBy(() -> reportService.monthlySeries(
                    USER_ID, YearMonth.of(2026, 6), YearMonth.of(2026, 1)))
                    .isInstanceOf(BusinessRuleException.class)
                    .hasMessageContaining("must not be after");
        }

        @Test
        @DisplayName("refuses a span wide enough to build thousands of buckets")
        void rejectsExcessiveSpan() {
            assertThatThrownBy(() -> reportService.monthlySeries(
                    USER_ID, YearMonth.of(1990, 1), YearMonth.of(2026, 1)))
                    .isInstanceOf(BusinessRuleException.class)
                    .hasMessageContaining("maximum");
        }
    }

    // ------------------------------------------------------ category breakdown --

    @Nested
    @DisplayName("category breakdown")
    class Breakdown {

        @Test
        @DisplayName("percentages are shares of the whole period and sum to 100")
        void percentagesSumTo100() {
            when(reportRepository.categoryTotals(anyLong(), any(), any(), any()))
                    .thenReturn(List.of(
                            new CategoryTotalRow(1L, "Groceries", new BigDecimal("5000"), 10L),
                            new CategoryTotalRow(2L, "Dining", new BigDecimal("3000"), 8L),
                            new CategoryTotalRow(3L, "Transport", new BigDecimal("2000"), 5L)));

            CategoryBreakdownResponse response = reportService.categoryBreakdown(
                    USER_ID, TransactionType.EXPENSE, MARCH_START, MARCH_END, 0, 20);

            assertThat(response.grandTotal()).isEqualByComparingTo("10000.00");
            assertThat(response.categories().content())
                    .extracting(item -> item.percentageOfTotal().toPlainString())
                    .containsExactly("50.00", "30.00", "20.00");
        }

        @Test
        @DisplayName("labels the null-category group as Uncategorized")
        void labelsUncategorised() {
            // The LEFT JOIN keeps transactions with no category as a NULL group.
            // Dropping them would make the breakdown fail to sum to the period total.
            when(reportRepository.categoryTotals(anyLong(), any(), any(), any()))
                    .thenReturn(List.of(
                            new CategoryTotalRow(1L, "Groceries", new BigDecimal("7000"), 10L),
                            new CategoryTotalRow(null, null, new BigDecimal("3000"), 4L)));

            CategoryBreakdownResponse response = reportService.categoryBreakdown(
                    USER_ID, TransactionType.EXPENSE, MARCH_START, MARCH_END, 0, 20);

            assertThat(response.categories().content().get(1).categoryId()).isNull();
            assertThat(response.categories().content().get(1).categoryName()).isEqualTo("Uncategorized");
            assertThat(response.grandTotal()).isEqualByComparingTo("10000.00");
        }

        @Test
        @DisplayName("keeps the grand total page-independent")
        void grandTotalIsNotPerPage() {
            // The whole reason the grouped set is fetched in full: a database-side
            // LIMIT would make page 2's percentages measured against a different
            // denominator, and they would not add up.
            when(reportRepository.categoryTotals(anyLong(), any(), any(), any()))
                    .thenReturn(List.of(
                            new CategoryTotalRow(1L, "A", new BigDecimal("5000"), 1L),
                            new CategoryTotalRow(2L, "B", new BigDecimal("3000"), 1L),
                            new CategoryTotalRow(3L, "C", new BigDecimal("2000"), 1L)));

            CategoryBreakdownResponse page2 = reportService.categoryBreakdown(
                    USER_ID, TransactionType.EXPENSE, MARCH_START, MARCH_END, 1, 2);

            assertThat(page2.grandTotal()).isEqualByComparingTo("10000.00");
            assertThat(page2.categories().content()).hasSize(1);
            assertThat(page2.categories().content().get(0).categoryName()).isEqualTo("C");
            assertThat(page2.categories().content().get(0).percentageOfTotal())
                    .isEqualByComparingTo("20.00");
        }

        @Test
        @DisplayName("paginates correctly")
        void pagination() {
            when(reportRepository.categoryTotals(anyLong(), any(), any(), any()))
                    .thenReturn(List.of(
                            new CategoryTotalRow(1L, "A", new BigDecimal("100"), 1L),
                            new CategoryTotalRow(2L, "B", new BigDecimal("100"), 1L),
                            new CategoryTotalRow(3L, "C", new BigDecimal("100"), 1L),
                            new CategoryTotalRow(4L, "D", new BigDecimal("100"), 1L),
                            new CategoryTotalRow(5L, "E", new BigDecimal("100"), 1L)));

            var page0 = reportService.categoryBreakdown(
                    USER_ID, TransactionType.EXPENSE, MARCH_START, MARCH_END, 0, 2).categories();

            assertThat(page0.content()).hasSize(2);
            assertThat(page0.totalElements()).isEqualTo(5);
            assertThat(page0.totalPages()).isEqualTo(3);
            assertThat(page0.first()).isTrue();
            assertThat(page0.last()).isFalse();

            var lastPage = reportService.categoryBreakdown(
                    USER_ID, TransactionType.EXPENSE, MARCH_START, MARCH_END, 2, 2).categories();

            assertThat(lastPage.content()).hasSize(1);
            assertThat(lastPage.last()).isTrue();
        }

        @Test
        @DisplayName("returns an empty page past the end rather than throwing")
        void pageBeyondEnd() {
            when(reportRepository.categoryTotals(anyLong(), any(), any(), any()))
                    .thenReturn(List.of(new CategoryTotalRow(1L, "A", new BigDecimal("100"), 1L)));

            var page = reportService.categoryBreakdown(
                    USER_ID, TransactionType.EXPENSE, MARCH_START, MARCH_END, 99, 20).categories();

            assertThat(page.content()).isEmpty();
            assertThat(page.empty()).isTrue();
        }

        @Test
        @DisplayName("handles a period with no transactions")
        void emptyBreakdown() {
            when(reportRepository.categoryTotals(anyLong(), any(), any(), any()))
                    .thenReturn(List.of());

            CategoryBreakdownResponse response = reportService.categoryBreakdown(
                    USER_ID, TransactionType.EXPENSE, MARCH_START, MARCH_END, 0, 20);

            assertThat(response.grandTotal()).isEqualByComparingTo("0.00");
            assertThat(response.categories().content()).isEmpty();
            assertThat(response.categories().totalPages()).isZero();
        }

        @Test
        @DisplayName("clamps an oversized page size")
        void clampsPageSize() {
            when(reportRepository.categoryTotals(anyLong(), any(), any(), any()))
                    .thenReturn(List.of());

            var page = reportService.categoryBreakdown(
                    USER_ID, TransactionType.EXPENSE, MARCH_START, MARCH_END, 0, 10_000).categories();

            assertThat(page.size()).isEqualTo(100);
        }

        @Test
        @DisplayName("treats a negative page index as page 0")
        void clampsNegativePage() {
            when(reportRepository.categoryTotals(anyLong(), any(), any(), any()))
                    .thenReturn(List.of(new CategoryTotalRow(1L, "A", new BigDecimal("100"), 1L)));

            var page = reportService.categoryBreakdown(
                    USER_ID, TransactionType.EXPENSE, MARCH_START, MARCH_END, -5, 20).categories();

            assertThat(page.page()).isZero();
            assertThat(page.content()).hasSize(1);
        }
    }
}
