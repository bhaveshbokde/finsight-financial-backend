package com.finsight.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Monetary arithmetic.
 *
 * <p>These tests exist because money is the one place where a rounding mistake is
 * both easy to make and invisible until someone reconciles a statement by hand.
 */
class MoneyTest {

    @Nested
    @DisplayName("display()")
    class Display {

        @Test
        @DisplayName("rounds to two decimals using HALF_UP")
        void roundsHalfUp() {
            // HALF_UP, not HALF_EVEN: 2.345 must become 2.35, which is what a user
            // checking the arithmetic on paper expects. HALF_EVEN would give 2.34.
            assertThat(Money.display(new BigDecimal("2.345"))).isEqualByComparingTo("2.35");
            assertThat(Money.display(new BigDecimal("2.355"))).isEqualByComparingTo("2.36");
            assertThat(Money.display(new BigDecimal("2.344"))).isEqualByComparingTo("2.34");
        }

        @Test
        @DisplayName("always carries exactly two fraction digits")
        void fixesScale() {
            // Scale matters for the JSON representation: 10 and 10.00 are equal
            // numerically but serialise differently, and a client formatting a
            // currency column needs the second.
            assertThat(Money.display(new BigDecimal("10")).scale()).isEqualTo(2);
            assertThat(Money.display(new BigDecimal("10")).toPlainString()).isEqualTo("10.00");
        }

        @Test
        @DisplayName("treats null as zero")
        void nullIsZero() {
            assertThat(Money.display(null)).isEqualByComparingTo("0.00");
        }
    }

    @Nested
    @DisplayName("normalize()")
    class Normalize {

        @Test
        @DisplayName("scales to the four digits the NUMERIC(19,4) column stores")
        void storageScale() {
            assertThat(Money.normalize(new BigDecimal("1.5")).scale()).isEqualTo(4);
            assertThat(Money.normalize(new BigDecimal("1.5")).toPlainString()).isEqualTo("1.5000");
        }

        @Test
        @DisplayName("rounds beyond four digits rather than overflowing the column")
        void roundsExcessPrecision() {
            assertThat(Money.normalize(new BigDecimal("1.123456")))
                    .isEqualByComparingTo("1.1235");
        }
    }

    @Nested
    @DisplayName("percentageOf()")
    class PercentageOf {

        @ParameterizedTest(name = "{0} of {1} = {2}%")
        @CsvSource({
                "50.00,  200.00, 25.00",
                "200.00, 200.00, 100.00",
                "300.00, 200.00, 150.00",   // over budget is allowed to exceed 100
                "0.00,   200.00, 0.00",
                "1.00,   3.00,   33.33"     // repeating decimal is rounded, not thrown
        })
        void computesPercentage(BigDecimal part, BigDecimal whole, BigDecimal expected) {
            assertThat(Money.percentageOf(part, whole)).isEqualByComparingTo(expected);
        }

        @Test
        @DisplayName("returns zero instead of dividing by zero")
        void zeroWholeIsNotAnError() {
            // A category with no budget, or a period with no income, is a normal
            // state in a report — not an exceptional one. Throwing here would make
            // every caller wrap the call in a try/catch.
            assertThat(Money.percentageOf(new BigDecimal("50"), BigDecimal.ZERO))
                    .isEqualByComparingTo("0.00");
            assertThat(Money.percentageOf(new BigDecimal("50"), null))
                    .isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("treats a null part as zero")
        void nullPart() {
            assertThat(Money.percentageOf(null, new BigDecimal("100")))
                    .isEqualByComparingTo("0.00");
        }
    }

    @Nested
    @DisplayName("add() and subtract()")
    class Arithmetic {

        @Test
        @DisplayName("are exact where binary floating point is not")
        void exactDecimalArithmetic() {
            // 0.1 + 0.2 == 0.30000000000000004 as a double. This is the single
            // clearest reason the whole application uses BigDecimal for money.
            assertThat(Money.add(new BigDecimal("0.10"), new BigDecimal("0.20")))
                    .isEqualByComparingTo("0.30");

            assertThat(0.1 + 0.2).isNotEqualTo(0.3);   // the behaviour being avoided
        }

        @Test
        @DisplayName("handle nulls as zero on either side")
        void nullSafe() {
            assertThat(Money.add(null, new BigDecimal("5.00"))).isEqualByComparingTo("5.00");
            assertThat(Money.add(new BigDecimal("5.00"), null)).isEqualByComparingTo("5.00");
            assertThat(Money.add(null, null)).isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("produce a negative result when expenses exceed income")
        void negativeNetCashFlow() {
            assertThat(Money.subtract(new BigDecimal("100.00"), new BigDecimal("250.00")))
                    .isEqualByComparingTo("-150.00");
        }

        @Test
        @DisplayName("stay exact across many additions")
        void noDriftOverManyAdditions() {
            // A year of daily transactions. With doubles the error is visible by
            // the end; with BigDecimal the total is exact.
            BigDecimal total = BigDecimal.ZERO;
            for (int i = 0; i < 365; i++) {
                total = Money.add(total, new BigDecimal("0.01"));
            }
            assertThat(total).isEqualByComparingTo("3.65");
        }
    }

    @Nested
    @DisplayName("predicates")
    class Predicates {

        @Test
        void isZeroIgnoresScale() {
            // 0.00 and 0.0000 are numerically equal; equals() on BigDecimal would
            // say otherwise, which is why compareTo is used throughout.
            assertThat(Money.isZero(new BigDecimal("0.00"))).isTrue();
            assertThat(Money.isZero(new BigDecimal("0.0000"))).isTrue();
            assertThat(Money.isZero(null)).isTrue();
            assertThat(Money.isZero(new BigDecimal("0.01"))).isFalse();
        }

        @Test
        void isPositiveRejectsZeroAndNull() {
            assertThat(Money.isPositive(new BigDecimal("0.01"))).isTrue();
            assertThat(Money.isPositive(BigDecimal.ZERO)).isFalse();
            assertThat(Money.isPositive(new BigDecimal("-1"))).isFalse();
            assertThat(Money.isPositive(null)).isFalse();
        }
    }
}
