package com.finsight.report.api;

import com.finsight.auth.security.UserPrincipal;
import com.finsight.budget.dto.BudgetResponse;
import com.finsight.budget.service.BudgetService;
import com.finsight.common.error.ApiError;
import com.finsight.common.util.DateRanges;
import com.finsight.report.dto.CategoryBreakdownResponse;
import com.finsight.report.dto.MonthlySeriesResponse;
import com.finsight.report.dto.SummaryResponse;
import com.finsight.report.service.ReportService;
import com.finsight.transaction.domain.TransactionType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Reporting endpoints.
 *
 * <p><strong>Date semantics:</strong> every {@code from}/{@code to} pair is
 * inclusive on both ends and refers to calendar dates. Defaults are resolved in
 * UTC, consistent with the rest of the application.
 *
 * <p>These are the cached endpoints. A response may be up to the configured TTL
 * (10 minutes) stale only if a write happened while Redis was unavailable;
 * otherwise every write evicts the caller's entries immediately.
 */
@RestController
@RequestMapping("/api/v1/reports")
@Tag(name = "Reports", description = "Cash-flow summaries, monthly series and category breakdowns")
@SecurityRequirement(name = "bearerAuth")
public class ReportController {

    private final ReportService reportService;
    private final BudgetService budgetService;

    public ReportController(ReportService reportService, BudgetService budgetService) {
        this.reportService = reportService;
        this.budgetService = budgetService;
    }

    @GetMapping("/summary")
    @Operation(
            summary = "Totals for a date range",
            description = "Income, expense, net cash flow and transaction count. Defaults to the current UTC month.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Totals; zeros when the period has no transactions"),
            @ApiResponse(responseCode = "400", description = "from is after to, or the span is too wide",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<SummaryResponse> summary(
            @AuthenticationPrincipal UserPrincipal principal,
            @Parameter(description = "Inclusive start date", example = "2026-03-01")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "Inclusive end date", example = "2026-03-31")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        YearMonth currentMonth = YearMonth.now(ZoneOffset.UTC);
        LocalDate start = from != null ? from : currentMonth.atDay(1);
        LocalDate end = to != null ? to : currentMonth.atEndOfMonth();

        return ResponseEntity.ok(reportService.summary(principal.getId(), start, end));
    }

    @GetMapping("/monthly")
    @Operation(
            summary = "Month-by-month series",
            description = """
                    Returns one entry per month in the requested span, including months
                    with no activity (zeros). Defaults to the trailing 12 months.
                    Maximum span is 120 months.
                    """)
    public ResponseEntity<MonthlySeriesResponse> monthly(
            @AuthenticationPrincipal UserPrincipal principal,
            @Parameter(description = "First month, YYYY-MM", example = "2026-01")
            @RequestParam(required = false) String from,
            @Parameter(description = "Last month, YYYY-MM", example = "2026-12")
            @RequestParam(required = false) String to) {

        YearMonth end = (to == null || to.isBlank())
                ? YearMonth.now(ZoneOffset.UTC) : DateRanges.parseMonth(to);
        YearMonth start = (from == null || from.isBlank())
                ? end.minusMonths(11) : DateRanges.parseMonth(from);

        return ResponseEntity.ok(reportService.monthlySeries(principal.getId(), start, end));
    }

    @GetMapping("/categories")
    @Operation(
            summary = "Totals grouped by category",
            description = """
                    Paginated. `percentageOfTotal` is each category's share of the whole
                    period, not of the current page, so the values across all pages add up
                    to 100. Transactions with no category appear as "Uncategorized".
                    """)
    public ResponseEntity<CategoryBreakdownResponse> categories(
            @AuthenticationPrincipal UserPrincipal principal,
            @Parameter(description = "EXPENSE (default) or INCOME")
            @RequestParam(defaultValue = "EXPENSE") TransactionType type,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Capped at 100") @RequestParam(defaultValue = "20") int size) {

        YearMonth currentMonth = YearMonth.now(ZoneOffset.UTC);
        LocalDate start = from != null ? from : currentMonth.atDay(1);
        LocalDate end = to != null ? to : currentMonth.atEndOfMonth();

        return ResponseEntity.ok(
                reportService.categoryBreakdown(principal.getId(), type, start, end, page, size));
    }

    @GetMapping("/budget-utilisation")
    @Operation(
            summary = "Budget versus actual for a month",
            description = """
                    One entry per budget configured for the month, each with measured spend,
                    remaining amount, utilisation percentage and a status of
                    ON_TRACK / WARNING / EXCEEDED. Returns an empty list when no budgets exist.
                    """)
    public ResponseEntity<List<BudgetResponse>> budgetUtilisation(
            @AuthenticationPrincipal UserPrincipal principal,
            @Parameter(description = "Month in YYYY-MM form; defaults to the current UTC month")
            @RequestParam(required = false) String month) {

        YearMonth target = (month == null || month.isBlank())
                ? YearMonth.now(ZoneOffset.UTC) : DateRanges.parseMonth(month);

        // Delegates to BudgetService rather than duplicating the evaluation logic,
        // so /api/v1/budgets and this report can never disagree.
        return ResponseEntity.ok(budgetService.listForMonth(principal.getId(), target));
    }
}
