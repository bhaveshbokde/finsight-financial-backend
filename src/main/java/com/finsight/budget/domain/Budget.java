package com.finsight.budget.domain;

import com.finsight.category.domain.Category;
import com.finsight.common.domain.AuditableEntity;
import com.finsight.common.util.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Objects;

/**
 * A monthly spending cap.
 *
 * <p>Two shapes share this table:
 * <ul>
 *   <li><strong>Overall</strong> ({@code category == null}) — a ceiling on all
 *       expenses in the month, including uncategorised ones.</li>
 *   <li><strong>Category</strong> ({@code category != null}) — a ceiling on one
 *       category.</li>
 * </ul>
 * They are not mutually exclusive: a user may hold an overall budget and several
 * category budgets for the same month, and the report evaluates each independently.
 *
 * <p>{@code periodMonth} always stores the first day of the month. Storing a
 * {@link YearMonth} directly would need a converter and give up the ability to
 * compare and range-scan with ordinary date predicates; the database CHECK
 * {@code ck_budgets_period} enforces the day-one invariant.
 */
@Entity
@Table(name = "budgets")
public class Budget extends AuditableEntity {

    /** Default percentage of the cap at which the report starts warning. */
    public static final short DEFAULT_ALERT_THRESHOLD_PCT = 80;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    /** Null for an overall budget. LAZY because the name is only needed when rendering. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;

    @Column(name = "period_month", nullable = false)
    private LocalDate periodMonth;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "alert_threshold_pct", nullable = false)
    private short alertThresholdPct = DEFAULT_ALERT_THRESHOLD_PCT;

    protected Budget() {
    }

    private Budget(Long userId, Category category, YearMonth month, BigDecimal amount,
                   short alertThresholdPct) {
        this.userId = userId;
        this.category = category;
        this.periodMonth = month.atDay(1);
        this.amount = Money.normalize(amount);
        this.alertThresholdPct = alertThresholdPct;
    }

    /** Creates an overall monthly budget. */
    public static Budget overall(Long userId, YearMonth month, BigDecimal amount, short threshold) {
        return new Budget(Objects.requireNonNull(userId, "userId"), null, month, amount, threshold);
    }

    /** Creates a budget scoped to one category. */
    public static Budget forCategory(Long userId, Category category, YearMonth month,
                                     BigDecimal amount, short threshold) {
        return new Budget(Objects.requireNonNull(userId, "userId"),
                Objects.requireNonNull(category, "category"), month, amount, threshold);
    }

    public YearMonth month() {
        return YearMonth.from(periodMonth);
    }

    /** True when this budget caps total expenses rather than one category. */
    public boolean isOverall() {
        return category == null;
    }

    public void changeAmount(BigDecimal newAmount) {
        this.amount = Money.normalize(newAmount);
    }

    public void changeThreshold(short newThreshold) {
        this.alertThresholdPct = newThreshold;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public Category getCategory() {
        return category;
    }

    public LocalDate getPeriodMonth() {
        return periodMonth;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public short getAlertThresholdPct() {
        return alertThresholdPct;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Budget budget)) {
            return false;
        }
        return id != null && id.equals(budget.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(Budget.class);
    }

    @Override
    public String toString() {
        return "Budget{id=%d, userId=%d, month=%s, amount=%s, overall=%s}"
                .formatted(id, userId, periodMonth, amount, isOverall());
    }
}
