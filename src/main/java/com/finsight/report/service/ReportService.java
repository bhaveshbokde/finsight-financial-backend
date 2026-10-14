package com.finsight.report.service;

import com.finsight.common.dto.PageResponse;
import com.finsight.common.error.BusinessRuleException;
import com.finsight.common.error.ErrorCode;
import com.finsight.common.util.DateRanges;
import com.finsight.common.util.Money;
import com.finsight.report.dto.CategoryBreakdownItem;
import com.finsight.report.dto.CategoryBreakdownResponse;
import com.finsight.report.dto.CategoryTotalRow;
import com.finsight.report.dto.MonthlySeriesResponse;
import com.finsight.report.dto.MonthlySummary;
import com.finsight.report.dto.MonthlyTotalsRow;
import com.finsight.report.dto.PeriodTotals;
import com.finsight.report.dto.SummaryResponse;
import com.finsight.report.repository.ReportRepository;
import com.finsight.transaction.domain.TransactionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Financial reporting.
 *
 * <h2>Date semantics</h2>
 * Every range is <strong>inclusive on both ends</strong> and is expressed in
 * calendar dates, matching {@code transactions.txn_date DATE}. There is no
 * time-of-day and therefore no time-zone sensitivity at the boundaries: a
 * transaction dated 31 March is in March for every reader, everywhere.
 *
 * <h2>Caching</h2>
 * These are the right endpoints to cache — few distinct shapes, aggregate queries
 * that scan many rows, and read traffic that dominates writes. Keys always start
 * with the user id so entries cannot be shared across accounts, and every
 * transaction or budget write evicts that user's keys. A cache outage degrades to
 * reading PostgreSQL, never to a failed request. See docs/09.
 *
 * <h2>Empty periods</h2>
 * A period with no transactions yields zeros, not nulls and not an error. That is
 * handled once, in {@link PeriodTotals}' compact constructor, rather than in each
 * caller.
 */
@Service
public class ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);

    /**
     * Cache names — one per return type.
     *
     * <p>Split rather than a single "reports" cache because each cache is bound to
     * a concrete value type in {@code RedisCacheConfig}; that is what lets a cache
     * hit deserialise back into the right record instead of a {@code LinkedHashMap}.
     * See the note there.
     */
    public static final String CACHE_REPORT_SUMMARY = "report-summary";
    public static final String CACHE_REPORT_MONTHLY = "report-monthly";
    public static final String CACHE_REPORT_CATEGORIES = "report-categories";

    /**
     * Every report cache, for invalidation.
     *
     * <p>Callers evict through this constant rather than listing names, so adding
     * a report cache cannot leave a stale-data bug behind in three other services.
     */
    public static final String[] REPORT_CACHES = {
            CACHE_REPORT_SUMMARY, CACHE_REPORT_MONTHLY, CACHE_REPORT_CATEGORIES
    };

    /** Upper bound on a monthly series, so one request cannot ask for 500 years of buckets. */
    public static final int MAX_MONTHS_IN_SERIES = 120;

    private final ReportRepository reportRepository;

    public ReportService(ReportRepository reportRepository) {
        this.reportRepository = reportRepository;
    }

    // ---------------------------------------------------------------- summary --

    /**
     * Income, expense and net cash flow for an inclusive range.
     *
     * <p>The cache key is {@code <userId>:summary:<from>:<to>}. The user id comes
     * first because {@link com.finsight.common.cache.UserCacheEvictor} evicts by
     * that prefix; putting the dates first would make per-user invalidation
     * impossible without scanning the whole keyspace.
     */
    @Transactional(readOnly = true)
    @Cacheable(cacheNames = CACHE_REPORT_SUMMARY, key = "#userId + ':summary:' + #from + ':' + #to")
    public SummaryResponse summary(Long userId, LocalDate from, LocalDate to) {
        DateRanges.validate(from, to);

        PeriodTotals totals = reportRepository.totals(
                userId, from, to, TransactionType.INCOME, TransactionType.EXPENSE);

        BigDecimal income = Money.display(totals.totalIncome());
        BigDecimal expense = Money.display(totals.totalExpense());

        return new SummaryResponse(
                from,
                to,
                income,
                expense,
                Money.subtract(income, expense),
                totals.transactionCount(),
                // percentageOf returns 0 rather than dividing by zero when there was
                // no income in the period — a real and common case, not an error.
                Money.percentageOf(expense, income));
    }

    // ---------------------------------------------------------------- monthly --

    /**
     * A dense month-by-month series over an inclusive month range.
     *
     * <p>SQL returns nothing for a month with no transactions. Rather than leaving
     * the client to detect gaps, the series is densified here: every month between
     * the bounds appears exactly once, with zeros where there was no activity.
     */
    @Transactional(readOnly = true)
    @Cacheable(cacheNames = CACHE_REPORT_MONTHLY, key = "#userId + ':monthly:' + #fromMonth + ':' + #toMonth")
    public MonthlySeriesResponse monthlySeries(Long userId, YearMonth fromMonth, YearMonth toMonth) {
        if (fromMonth.isAfter(toMonth)) {
            throw new BusinessRuleException(ErrorCode.INVALID_DATE_RANGE,
                    "'from' month (%s) must not be after 'to' month (%s)".formatted(fromMonth, toMonth));
        }
        long monthCount = ChronoUnit.MONTHS.between(fromMonth, toMonth) + 1;
        if (monthCount > MAX_MONTHS_IN_SERIES) {
            throw new BusinessRuleException(ErrorCode.INVALID_DATE_RANGE,
                    "Requested %d months; the maximum is %d".formatted(monthCount, MAX_MONTHS_IN_SERIES));
        }

        LocalDate from = DateRanges.firstDayOf(fromMonth);
        LocalDate to = DateRanges.lastDayOf(toMonth);

        Map<YearMonth, MonthlyTotalsRow> byMonth = new HashMap<>();
        for (MonthlyTotalsRow row : reportRepository.monthlyTotals(
                userId, from, to, TransactionType.INCOME, TransactionType.EXPENSE)) {
            byMonth.put(YearMonth.of(row.year(), row.month()), row);
        }

        List<MonthlySummary> months = new ArrayList<>((int) monthCount);
        BigDecimal totalIncome = BigDecimal.ZERO;
        BigDecimal totalExpense = BigDecimal.ZERO;

        for (YearMonth cursor = fromMonth; !cursor.isAfter(toMonth); cursor = cursor.plusMonths(1)) {
            MonthlyTotalsRow row = byMonth.get(cursor);

            BigDecimal income = Money.display(row == null ? BigDecimal.ZERO : row.totalIncome());
            BigDecimal expense = Money.display(row == null ? BigDecimal.ZERO : row.totalExpense());

            months.add(new MonthlySummary(
                    cursor.toString(),
                    income,
                    expense,
                    Money.subtract(income, expense),
                    row == null ? 0L : row.transactionCount()));

            totalIncome = totalIncome.add(income);
            totalExpense = totalExpense.add(expense);
        }

        // Averages divide by the number of months *requested*, not the number that
        // had activity. "Average monthly spend over the last 12 months" means
        // dividing by 12; dividing by the 7 active months would overstate it.
        BigDecimal divisor = BigDecimal.valueOf(monthCount);

        return new MonthlySeriesResponse(
                fromMonth.toString(),
                toMonth.toString(),
                months,
                Money.display(totalIncome),
                Money.display(totalExpense),
                Money.subtract(totalIncome, totalExpense),
                totalIncome.divide(divisor, Money.DISPLAY_SCALE, Money.ROUNDING),
                totalExpense.divide(divisor, Money.DISPLAY_SCALE, Money.ROUNDING));
    }

    // ------------------------------------------------------- category breakdown --

    /**
     * Spending (or income) grouped by category, paginated.
     *
     * <p>The grouped set is fetched in full and then sliced in memory. That is
     * deliberate and is not the usual "load everything and paginate in Java"
     * mistake: the number of groups is bounded by the user's category count — tens,
     * not thousands — and the complete set is required to compute each category's
     * percentage share. A database-side {@code LIMIT} would produce percentages
     * measured against only the current page.
     *
     * @param page zero-based page index
     * @param size page size, capped at 100
     */
    @Transactional(readOnly = true)
    @Cacheable(cacheNames = CACHE_REPORT_CATEGORIES,
               key = "#userId + ':categories:' + #type + ':' + #from + ':' + #to + ':' + #page + ':' + #size")
    public CategoryBreakdownResponse categoryBreakdown(Long userId, TransactionType type,
                                                       LocalDate from, LocalDate to,
                                                       int page, int size) {
        DateRanges.validate(from, to);
        int pageSize = Math.clamp(size, 1, 100);
        int pageIndex = Math.max(page, 0);

        List<CategoryTotalRow> rows = reportRepository.categoryTotals(userId, type, from, to);

        BigDecimal grandTotal = rows.stream()
                .map(CategoryTotalRow::total)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<CategoryBreakdownItem> all = rows.stream()
                .map(row -> new CategoryBreakdownItem(
                        row.categoryId(),
                        row.categoryName() == null ? CategoryBreakdownItem.UNCATEGORIZED : row.categoryName(),
                        Money.display(row.total()),
                        Money.percentageOf(row.total(), grandTotal),
                        row.transactionCount()))
                .toList();

        int fromIndex = Math.min(pageIndex * pageSize, all.size());
        int toIndex = Math.min(fromIndex + pageSize, all.size());
        List<CategoryBreakdownItem> slice = all.subList(fromIndex, toIndex);

        int totalPages = all.isEmpty() ? 0 : (int) Math.ceil((double) all.size() / pageSize);

        PageResponse<CategoryBreakdownItem> pageResponse = new PageResponse<>(
                slice,
                pageIndex,
                pageSize,
                all.size(),
                totalPages,
                pageIndex == 0,
                toIndex >= all.size(),
                slice.isEmpty());

        return new CategoryBreakdownResponse(from, to, type, Money.display(grandTotal), pageResponse);
    }
}
