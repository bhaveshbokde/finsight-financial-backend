package com.finsight.transaction.domain;

/** How a transaction entered the system. Drives nothing functionally; useful for support and reporting. */
public enum TransactionSource {
    /** Created through {@code POST /api/v1/transactions}. */
    MANUAL,

    /** Created by the CSV import pipeline. Carries a non-null {@code externalRef} and {@code importJobId}. */
    CSV_IMPORT
}
