package com.finsight.budget.repository;

import com.finsight.budget.domain.Budget;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Data access for {@link Budget}. Every finder is scoped by {@code userId}. */
public interface BudgetRepository extends JpaRepository<Budget, Long> {

    /** All budgets for one month. The entity graph avoids an N+1 on category names. */
    @EntityGraph(attributePaths = "category")
    List<Budget> findByUserIdAndPeriodMonth(Long userId, LocalDate periodMonth);

    @EntityGraph(attributePaths = "category")
    Optional<Budget> findByIdAndUserId(Long id, Long userId);

    /** Budgets across a range of months, newest month first. */
    @EntityGraph(attributePaths = "category")
    @Query("""
            SELECT b FROM Budget b
            WHERE b.userId = :userId
              AND b.periodMonth >= :from
              AND b.periodMonth <= :to
            ORDER BY b.periodMonth DESC, b.category.name ASC NULLS FIRST
            """)
    List<Budget> findInMonthRange(@Param("userId") Long userId,
                                  @Param("from") LocalDate from,
                                  @Param("to") LocalDate to);

    /**
     * Uniqueness pre-check for an overall budget.
     *
     * <p>Split from the category variant because {@code category_id IS NULL} cannot
     * be expressed with an equality parameter — {@code = NULL} is never true in SQL.
     * This is also why the schema needs two partial unique indexes rather than one.
     */
    @Query("""
            SELECT COUNT(b) > 0 FROM Budget b
            WHERE b.userId = :userId AND b.periodMonth = :month AND b.category IS NULL
            """)
    boolean existsOverallFor(@Param("userId") Long userId, @Param("month") LocalDate month);

    @Query("""
            SELECT COUNT(b) > 0 FROM Budget b
            WHERE b.userId = :userId AND b.periodMonth = :month AND b.category.id = :categoryId
            """)
    boolean existsForCategory(@Param("userId") Long userId,
                              @Param("month") LocalDate month,
                              @Param("categoryId") Long categoryId);
}
