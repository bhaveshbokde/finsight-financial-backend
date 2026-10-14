package com.finsight.transaction.domain;

/**
 * Direction of money movement.
 *
 * <p>{@code amount} on a transaction is always a positive magnitude; this enum
 * supplies the sign. Keeping the sign out of the number means
 * {@code CHECK (amount > 0)} can be enforced in the database, and aggregation
 * queries read as {@code SUM(...) FILTER (WHERE type = 'INCOME')} rather than
 * relying on every writer having got the sign right.
 */
public enum TransactionType {
    /** Money in. Increases net cash flow. */
    INCOME,

    /** Money out. Decreases net cash flow and counts against budgets. */
    EXPENSE
}
