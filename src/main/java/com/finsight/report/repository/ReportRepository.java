package com.finsight.report.repository;

import com.finsight.report.dto.CategoryTotalRow;
import com.finsight.report.dto.MonthlyTotalsRow;
import com.finsight.report.dto.PeriodTotals;
import com.finsight.transaction.domain.Transaction;
import com.finsight.transaction.domain.TransactionType;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * Read-only aggregate queries behind the reporting endpoints.
 *
 * <p>Extends the bare {@link Repository} marker rather than {@code JpaRepository}:
 * reports never save or delete, and inheriting 20 mutating methods onto a
 * read-only interface is an invitation to misuse.
 *
 * <h2>Why aggregate in SQL</h2>
 * Each of these could be done by loading every transaction and folding in Java.
 * That would move megabytes over the wire to produce three numbers, and would make
 * the cost grow with the user's history rather than with the answer. Aggregation
 * belongs where the data already is.
 *
 * <h2>Index support</h2>
 * Every query leads with {@code user_id} and a {@code txn_date} range, matching
 * {@code idx_transactions_user_date} and {@code idx_transactions_user_type_date}
 * from migration V4. The category breakdown additionally groups by category,
 * which {@code idx_transactions_user_category_date} covers.
 *
 * <p>All date ranges are inclusive on both ends — {@code BETWEEN} in SQL, and
 * {@code txn_date} is a {@code DATE}, so there is no midnight-boundary ambiguity.
 */
public interface ReportRepository extends Repository<Transaction, Long> {

    /**
     * Income, expense and row count over one period.
     *
     * <p>{@code SUM(CASE WHEN ...)} computes both directions in a single pass
     * instead of running two queries over the same rows. No {@code ELSE} branch is
     * needed: the CASE yields NULL for the other type and {@code SUM} skips NULLs.
     * The resulting NULL for an empty period is normalised to zero by
     * {@link PeriodTotals}' compact constructor.
     */
    @Query("""
            SELECT new com.finsight.report.dto.PeriodTotals(
                SUM(CASE WHEN t.type = :income  THEN t.amount END),
                SUM(CASE WHEN t.type = :expense THEN t.amount END),
                COUNT(t))
            FROM Transaction t
            WHERE t.userId = :userId
              AND t.txnDate >= :from
              AND t.txnDate <= :to
            """)
    PeriodTotals totals(@Param("userId") Long userId,
                        @Param("from") LocalDate from,
                        @Param("to") LocalDate to,
                        @Param("income") TransactionType income,
                        @Param("expense") TransactionType expense);

    /**
     * Per-month totals, ordered chronologically.
     *
     * <p>{@code year()} / {@code month()} are HQL functions that Hibernate renders
     * to the dialect's own date extraction, so this stays portable rather than
     * embedding {@code EXTRACT(... FROM ...)} directly.
     *
     * <p>Months with no transactions produce no row; the service densifies the series.
     */
    @Query("""
            SELECT new com.finsight.report.dto.MonthlyTotalsRow(
                year(t.txnDate),
                month(t.txnDate),
                SUM(CASE WHEN t.type = :income  THEN t.amount END),
                SUM(CASE WHEN t.type = :expense THEN t.amount END),
                COUNT(t))
            FROM Transaction t
            WHERE t.userId = :userId
              AND t.txnDate >= :from
              AND t.txnDate <= :to
            GROUP BY year(t.txnDate), month(t.txnDate)
            ORDER BY year(t.txnDate), month(t.txnDate)
            """)
    List<MonthlyTotalsRow> monthlyTotals(@Param("userId") Long userId,
                                         @Param("from") LocalDate from,
                                         @Param("to") LocalDate to,
                                         @Param("income") TransactionType income,
                                         @Param("expense") TransactionType expense);

    /**
     * Totals grouped by category for one transaction type.
     *
     * <p>{@code LEFT JOIN} so uncategorised transactions survive as a {@code NULL}
     * group; an INNER JOIN would drop them and the breakdown would no longer sum to
     * the period total.
     *
     * <p>Returns the complete grouped set rather than a {@code Page}. The number of
     * groups is bounded by the user's category count (tens, not thousands), and the
     * full set is needed anyway to compute each category's percentage share — a
     * database-side LIMIT would make that percentage wrong. The endpoint paginates
     * the computed list; see {@code ReportService#categoryBreakdown}.
     */
    @Query("""
            SELECT new com.finsight.report.dto.CategoryTotalRow(
                c.id,
                c.name,
                SUM(t.amount),
                COUNT(t))
            FROM Transaction t
            LEFT JOIN t.category c
            WHERE t.userId = :userId
              AND t.type = :type
              AND t.txnDate >= :from
              AND t.txnDate <= :to
            GROUP BY c.id, c.name
            ORDER BY SUM(t.amount) DESC
            """)
    List<CategoryTotalRow> categoryTotals(@Param("userId") Long userId,
                                          @Param("type") TransactionType type,
                                          @Param("from") LocalDate from,
                                          @Param("to") LocalDate to);

    /**
     * Expense totals per category for one month, used by budget-versus-actual.
     *
     * <p>Separate from {@link #categoryTotals} because budget utilisation needs the
     * uncategorised group excluded — an overall budget covers it, a category budget
     * cannot.
     */
    @Query("""
            SELECT new com.finsight.report.dto.CategoryTotalRow(
                c.id,
                c.name,
                SUM(t.amount),
                COUNT(t))
            FROM Transaction t
            JOIN t.category c
            WHERE t.userId = :userId
              AND t.type = :type
              AND t.txnDate >= :from
              AND t.txnDate <= :to
            GROUP BY c.id, c.name
            """)
    List<CategoryTotalRow> categorisedTotals(@Param("userId") Long userId,
                                             @Param("type") TransactionType type,
                                             @Param("from") LocalDate from,
                                             @Param("to") LocalDate to);
}
