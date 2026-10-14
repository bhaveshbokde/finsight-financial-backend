package com.finsight.common.util;

import com.finsight.common.error.BusinessRuleException;
import com.finsight.common.error.ErrorCode;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;

/**
 * Date-range semantics for reports, in one place so every endpoint agrees.
 *
 * <p><strong>All ranges in this application are inclusive on both ends.</strong>
 * A request for {@code from=2026-03-01&to=2026-03-31} includes every transaction
 * dated 31 March. {@code transactions.txn_date} is a {@code DATE}, so there is no
 * time-of-day component and therefore no time-zone ambiguity at the boundaries —
 * this is the main reason the column is {@code DATE} and not {@code TIMESTAMPTZ}.
 */
public final class DateRanges {

    /** Guard against a range so wide it would scan the whole table. */
    public static final long MAX_RANGE_DAYS = 3660; // ~10 years

    private DateRanges() {
        throw new AssertionError("Utility class — do not instantiate");
    }

    /**
     * Validates an inclusive {@code [from, to]} range.
     *
     * @throws BusinessRuleException if either bound is null, {@code from} is after
     *                               {@code to}, or the span exceeds {@link #MAX_RANGE_DAYS}
     */
    public static void validate(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw new BusinessRuleException(ErrorCode.INVALID_DATE_RANGE,
                    "Both 'from' and 'to' dates are required");
        }
        if (from.isAfter(to)) {
            throw new BusinessRuleException(ErrorCode.INVALID_DATE_RANGE,
                    "'from' (%s) must not be after 'to' (%s)".formatted(from, to));
        }
        long days = ChronoUnit.DAYS.between(from, to);
        if (days > MAX_RANGE_DAYS) {
            throw new BusinessRuleException(ErrorCode.INVALID_DATE_RANGE,
                    "Date range of %d days exceeds the maximum of %d".formatted(days, MAX_RANGE_DAYS));
        }
    }

    /** First day of the month — the canonical storage form of {@code budgets.period_month}. */
    public static LocalDate firstDayOf(YearMonth month) {
        return month.atDay(1);
    }

    /** Last day of the month, honouring leap years. */
    public static LocalDate lastDayOf(YearMonth month) {
        return month.atEndOfMonth();
    }

    /**
     * Parses {@code "YYYY-MM"} into a {@link YearMonth}.
     *
     * @throws BusinessRuleException with {@link ErrorCode#INVALID_PARAMETER} on bad input,
     *                               so the caller gets a 400 with a useful message rather
     *                               than a {@code DateTimeParseException} stack trace
     */
    public static YearMonth parseMonth(String value) {
        try {
            return YearMonth.parse(value);
        } catch (RuntimeException e) {
            throw new BusinessRuleException(ErrorCode.INVALID_PARAMETER,
                    "Invalid month '%s'; expected format YYYY-MM (for example 2026-03)".formatted(value));
        }
    }
}
