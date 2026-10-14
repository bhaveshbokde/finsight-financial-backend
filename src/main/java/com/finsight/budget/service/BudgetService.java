package com.finsight.budget.service;

import com.finsight.budget.domain.Budget;
import com.finsight.budget.dto.BudgetRequest;
import com.finsight.budget.dto.BudgetResponse;
import com.finsight.budget.repository.BudgetRepository;
import com.finsight.category.domain.Category;
import com.finsight.category.domain.CategoryType;
import com.finsight.category.service.CategoryService;
import com.finsight.common.cache.UserCacheEvictor;
import com.finsight.common.error.BusinessRuleException;
import com.finsight.common.error.DuplicateResourceException;
import com.finsight.common.error.ResourceNotFoundException;
import com.finsight.common.util.DateRanges;
import com.finsight.report.dto.CategoryTotalRow;
import com.finsight.report.repository.ReportRepository;
import com.finsight.report.service.ReportService;
import com.finsight.transaction.domain.TransactionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Budget management and budget-versus-actual evaluation.
 *
 * <h2>Spend is measured, not accumulated</h2>
 * Nothing here maintains a running "spent so far" column. Every read recomputes it
 * from the month's transactions. That costs one indexed aggregate query and
 * removes an entire class of bugs: a counter would have to be adjusted on create,
 * update (where the amount, date <em>or</em> category may change), delete, category
 * archival and import rollback — and any missed path silently corrupts the figure
 * with no way to detect it. It also means two concurrent transaction writes cannot
 * race on a shared counter. See docs/12.
 */
@Service
public class BudgetService {

    private static final Logger log = LoggerFactory.getLogger(BudgetService.class);

    private final BudgetRepository budgetRepository;
    private final ReportRepository reportRepository;
    private final CategoryService categoryService;
    private final UserCacheEvictor cacheEvictor;

    public BudgetService(BudgetRepository budgetRepository,
                         ReportRepository reportRepository,
                         CategoryService categoryService,
                         UserCacheEvictor cacheEvictor) {
        this.budgetRepository = budgetRepository;
        this.reportRepository = reportRepository;
        this.categoryService = categoryService;
        this.cacheEvictor = cacheEvictor;
    }

    // ------------------------------------------------------------------ reads --

    /** Every budget for one month, each with its measured spend. */
    @Transactional(readOnly = true)
    public List<BudgetResponse> listForMonth(Long userId, YearMonth month) {
        List<Budget> budgets = budgetRepository.findByUserIdAndPeriodMonth(userId, month.atDay(1));
        if (budgets.isEmpty()) {
            return List.of();
        }
        return evaluate(userId, month, budgets);
    }

    @Transactional(readOnly = true)
    public BudgetResponse get(Long budgetId, Long userId) {
        Budget budget = requireOwned(budgetId, userId);
        return evaluate(userId, budget.month(), List.of(budget)).get(0);
    }

    /**
     * Attaches actual spend to a set of budgets that all belong to the same month.
     *
     * <p>Two queries total, regardless of how many budgets there are: one for the
     * month's per-category expenses and one for the month's overall expense total.
     * Doing it per budget would be N+1 at the service layer, which is just as slow
     * as the ORM version and easier to miss in review.
     */
    private List<BudgetResponse> evaluate(Long userId, YearMonth month, List<Budget> budgets) {
        LocalDate from = DateRanges.firstDayOf(month);
        LocalDate to = DateRanges.lastDayOf(month);

        boolean needsOverall = budgets.stream().anyMatch(Budget::isOverall);
        boolean needsPerCategory = budgets.stream().anyMatch(b -> !b.isOverall());

        Map<Long, BigDecimal> spentByCategory = new HashMap<>();
        if (needsPerCategory) {
            for (CategoryTotalRow row : reportRepository.categorisedTotals(
                    userId, TransactionType.EXPENSE, from, to)) {
                spentByCategory.put(row.categoryId(), row.total());
            }
        }

        BigDecimal overallSpent = BigDecimal.ZERO;
        if (needsOverall) {
            // Uses the same totals query the summary report uses, so an overall
            // budget and the summary can never disagree about the month's expenses.
            overallSpent = reportRepository.totals(userId, from, to,
                    TransactionType.INCOME, TransactionType.EXPENSE).totalExpense();
        }

        BigDecimal finalOverallSpent = overallSpent;
        return budgets.stream()
                .map(b -> BudgetResponse.of(b, b.isOverall()
                        ? finalOverallSpent
                        : spentByCategory.getOrDefault(b.getCategory().getId(), BigDecimal.ZERO)))
                .toList();
    }

    // ----------------------------------------------------------------- writes --

    @Transactional
    public BudgetResponse create(Long userId, BudgetRequest request) {
        YearMonth month = DateRanges.parseMonth(request.month());
        short threshold = request.alertThresholdPct() == null
                ? Budget.DEFAULT_ALERT_THRESHOLD_PCT : request.alertThresholdPct();

        Budget budget;
        if (request.categoryId() == null) {
            if (budgetRepository.existsOverallFor(userId, month.atDay(1))) {
                throw new DuplicateResourceException(
                        "An overall budget already exists for %s".formatted(month));
            }
            budget = Budget.overall(userId, month, request.amount(), threshold);
        } else {
            // A budget caps spending, so it only makes sense against an EXPENSE
            // category. requireUsable also enforces visibility and the archived check.
            Category category = categoryService.requireUsable(
                    request.categoryId(), userId, CategoryType.EXPENSE);

            if (budgetRepository.existsForCategory(userId, month.atDay(1), category.getId())) {
                throw new DuplicateResourceException(
                        "A budget for '%s' already exists for %s".formatted(category.getName(), month));
            }
            budget = Budget.forCategory(userId, category, month, request.amount(), threshold);
        }

        try {
            budget = budgetRepository.saveAndFlush(budget);
        } catch (DataIntegrityViolationException race) {
            // Lost the race against a concurrent create; the partial unique indexes
            // uq_budgets_user_overall_month / uq_budgets_user_category_month decided it.
            throw new DuplicateResourceException(
                    "A budget for this scope already exists for %s".formatted(month));
        }

        cacheEvictor.evictUser(userId, ReportService.REPORT_CACHES);
        log.info("Created budget id={} month={} overall={} for user id={}",
                budget.getId(), month, budget.isOverall(), userId);

        return evaluate(userId, month, List.of(budget)).get(0);
    }

    /**
     * Changes the cap and/or the alert threshold.
     *
     * <p>Neither the month nor the category can be changed. Moving a budget to a
     * different month or category is semantically a different budget, and allowing
     * it in place would need the same uniqueness checks as a create while making
     * the audit trail meaningless — so the API makes you delete and recreate.
     *
     * <p>{@code @Version} on the entity means two concurrent edits do not silently
     * overwrite one another: the second UPDATE matches zero rows and the caller
     * gets a 409.
     */
    @Transactional
    public BudgetResponse update(Long budgetId, Long userId, BudgetRequest request) {
        Budget budget = requireOwned(budgetId, userId);

        YearMonth requestedMonth = DateRanges.parseMonth(request.month());
        if (!requestedMonth.equals(budget.month())) {
            throw new BusinessRuleException(
                    "A budget's month cannot be changed; delete it and create one for %s"
                            .formatted(requestedMonth));
        }
        Long requestedCategoryId = request.categoryId();
        Long currentCategoryId = budget.isOverall() ? null : budget.getCategory().getId();
        if (!java.util.Objects.equals(requestedCategoryId, currentCategoryId)) {
            throw new BusinessRuleException(
                    "A budget's category cannot be changed; delete it and create a new one");
        }

        budget.changeAmount(request.amount());
        if (request.alertThresholdPct() != null) {
            budget.changeThreshold(request.alertThresholdPct());
        }

        cacheEvictor.evictUser(userId, ReportService.REPORT_CACHES);
        return evaluate(userId, budget.month(), List.of(budget)).get(0);
    }

    @Transactional
    public void delete(Long budgetId, Long userId) {
        Budget budget = requireOwned(budgetId, userId);
        budgetRepository.delete(budget);

        cacheEvictor.evictUser(userId, ReportService.REPORT_CACHES);
        log.info("Deleted budget id={} for user id={}", budgetId, userId);
    }

    private Budget requireOwned(Long budgetId, Long userId) {
        return budgetRepository.findByIdAndUserId(budgetId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Budget", budgetId));
    }
}
