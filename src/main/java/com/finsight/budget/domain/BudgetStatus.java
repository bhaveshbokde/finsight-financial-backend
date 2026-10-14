package com.finsight.budget.domain;

/**
 * How a budget is tracking against actual spending.
 *
 * <p>Computed on read from the current spend, never stored. A stored status would
 * have to be recomputed on every transaction write, delete and category change —
 * and would be wrong in exactly the windows where it mattered.
 */
public enum BudgetStatus {
    /** Spend is below the alert threshold. */
    ON_TRACK,

    /** Spend has reached {@code alertThresholdPct} of the cap but not exceeded it. */
    WARNING,

    /** Spend equals or exceeds the cap. */
    EXCEEDED
}
