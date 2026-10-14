package com.finsight.common.util;

import com.finsight.common.error.BusinessRuleException;
import com.finsight.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/** Date-range rules shared by every report endpoint. */
class DateRangesTest {

    @Test
    @DisplayName("accepts a normal range")
    void acceptsValidRange() {
        assertThatCode(() -> DateRanges.validate(
                LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("accepts a single-day range, because bounds are inclusive")
    void acceptsSameDay() {
        LocalDate day = LocalDate.of(2026, 3, 14);
        assertThatCode(() -> DateRanges.validate(day, day)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("rejects from after to")
    void rejectsInvertedRange() {
        assertThatThrownBy(() -> DateRanges.validate(
                LocalDate.of(2026, 3, 31), LocalDate.of(2026, 3, 1)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("must not be after")
                .extracting(e -> ((BusinessRuleException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_DATE_RANGE);
    }

    @Test
    @DisplayName("rejects a null bound")
    void rejectsNulls() {
        assertThatThrownBy(() -> DateRanges.validate(null, LocalDate.of(2026, 3, 1)))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> DateRanges.validate(LocalDate.of(2026, 3, 1), null))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    @DisplayName("rejects a span wide enough to scan the whole table")
    void rejectsExcessiveSpan() {
        // Without this guard, ?from=0001-01-01&to=9999-12-31 is a one-request
        // table scan that any authenticated user could issue repeatedly.
        LocalDate from = LocalDate.of(1900, 1, 1);
        LocalDate to = LocalDate.of(2100, 1, 1);
        assertThatThrownBy(() -> DateRanges.validate(from, to))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("exceeds the maximum");
    }

    @Test
    @DisplayName("month boundaries honour leap years")
    void leapYear() {
        assertThat(DateRanges.lastDayOf(YearMonth.of(2024, 2))).isEqualTo(LocalDate.of(2024, 2, 29));
        assertThat(DateRanges.lastDayOf(YearMonth.of(2026, 2))).isEqualTo(LocalDate.of(2026, 2, 28));
        // 2100 is divisible by 100 but not 400, so it is not a leap year.
        assertThat(DateRanges.lastDayOf(YearMonth.of(2100, 2))).isEqualTo(LocalDate.of(2100, 2, 28));
    }

    @Test
    @DisplayName("firstDayOf returns day 1, matching the budgets CHECK constraint")
    void firstDay() {
        assertThat(DateRanges.firstDayOf(YearMonth.of(2026, 3))).isEqualTo(LocalDate.of(2026, 3, 1));
    }

    @Test
    @DisplayName("parses a well-formed YYYY-MM")
    void parsesMonth() {
        assertThat(DateRanges.parseMonth("2026-03")).isEqualTo(YearMonth.of(2026, 3));
    }

    @Test
    @DisplayName("turns a bad month into a 400, not a DateTimeParseException")
    void rejectsMalformedMonth() {
        // The point of wrapping: the caller gets an ApiError naming the expected
        // format rather than a stack trace from java.time.
        assertThatThrownBy(() -> DateRanges.parseMonth("March 2026"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("YYYY-MM")
                .extracting(e -> ((BusinessRuleException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_PARAMETER);

        assertThatThrownBy(() -> DateRanges.parseMonth("2026-13"))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> DateRanges.parseMonth(""))
                .isInstanceOf(BusinessRuleException.class);
    }
}
