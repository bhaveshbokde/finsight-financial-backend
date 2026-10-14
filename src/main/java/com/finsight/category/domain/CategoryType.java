package com.finsight.category.domain;

/**
 * Whether a category describes money coming in or going out.
 *
 * <p>Mirrors {@link com.finsight.transaction.domain.TransactionType}; a transaction
 * may only be filed under a category of the matching type, which is enforced in
 * {@code TransactionService}. Keeping them as two enums rather than one shared type
 * reflects that they are independent concepts that happen to align today — a
 * TRANSFER transaction type, for instance, would need no corresponding category type.
 */
public enum CategoryType {
    INCOME,
    EXPENSE
}
