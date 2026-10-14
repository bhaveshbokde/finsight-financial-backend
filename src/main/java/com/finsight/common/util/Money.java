package com.finsight.common.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Monetary helpers. Every amount in FinSight is a {@link BigDecimal}; binary
 * floating point is never used for money because values such as 0.1 have no exact
 * {@code double} representation and the error compounds across aggregation.
 *
 * <p>Storage scale is 4 (matching {@code NUMERIC(19,4)} in the schema), which
 * leaves room for sub-cent intermediate values; presentation rounds to 2 at the
 * edge. {@link RoundingMode#HALF_UP} is used consistently — it matches ordinary
 * financial rounding expectations and, unlike {@code HALF_EVEN}, is what a user
 * checking the arithmetic by hand will reproduce.
 */
public final class Money {

    /** Scale used for persistence and intermediate arithmetic. */
    public static final int STORAGE_SCALE = 4;

    /** Scale used when presenting a figure in a report. */
    public static final int DISPLAY_SCALE = 2;

    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    public static final BigDecimal ZERO = BigDecimal.ZERO.setScale(DISPLAY_SCALE, ROUNDING);

    private Money() {
        throw new AssertionError("Utility class — do not instantiate");
    }

    /** Normalises to storage scale. {@code null} becomes zero. */
    public static BigDecimal normalize(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(STORAGE_SCALE, ROUNDING)
                             : value.setScale(STORAGE_SCALE, ROUNDING);
    }

    /** Rounds to display scale. {@code null} becomes {@code 0.00}. */
    public static BigDecimal display(BigDecimal value) {
        return value == null ? ZERO : value.setScale(DISPLAY_SCALE, ROUNDING);
    }

    /** Null-safe addition at display scale. */
    public static BigDecimal add(BigDecimal a, BigDecimal b) {
        return display(nz(a).add(nz(b)));
    }

    /** Null-safe subtraction at display scale. */
    public static BigDecimal subtract(BigDecimal a, BigDecimal b) {
        return display(nz(a).subtract(nz(b)));
    }

    /**
     * {@code part} as a percentage of {@code whole}, rounded to 2 decimals.
     *
     * <p>Returns {@code 0.00} when {@code whole} is null or zero rather than
     * throwing {@link ArithmeticException}: a category with no budget is a normal
     * state in a report, not an error. Callers that must distinguish "0% used" from
     * "no budget set" check the budget for null themselves.
     */
    public static BigDecimal percentageOf(BigDecimal part, BigDecimal whole) {
        if (whole == null || whole.compareTo(BigDecimal.ZERO) == 0) {
            return ZERO;
        }
        return nz(part)
                .multiply(BigDecimal.valueOf(100))
                .divide(whole, DISPLAY_SCALE, ROUNDING);
    }

    /** True when the value is null or numerically zero (scale-insensitive). */
    public static boolean isZero(BigDecimal value) {
        return value == null || value.compareTo(BigDecimal.ZERO) == 0;
    }

    /** True when the value is non-null and strictly greater than zero. */
    public static boolean isPositive(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) > 0;
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
