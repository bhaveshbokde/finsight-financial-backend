package com.finsight.imports.csv;

import com.finsight.common.error.ApiException;
import com.finsight.common.error.ErrorCode;
import com.finsight.transaction.domain.TransactionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** CSV parsing and per-row validation. */
class CsvTransactionParserTest {

    private static final int MAX_ROWS = 10_000;
    private static final String HEADER = "date,type,amount,description,merchant,category\n";

    private CsvTransactionParser parser;

    @BeforeEach
    void setUp() {
        parser = new CsvTransactionParser();
    }

    // ------------------------------------------------------------- happy path --

    @Nested
    @DisplayName("well-formed input")
    class HappyPath {

        @Test
        @DisplayName("parses a complete row")
        void parsesCompleteRow() {
            CsvParseResult result = parser.parse(
                    HEADER + "2026-03-14,EXPENSE,1249.50,Weekly groceries,Big Bazaar,Groceries\n",
                    MAX_ROWS);

            assertThat(result.errors()).isEmpty();
            assertThat(result.rows()).hasSize(1);

            ParsedTransactionRow row = result.rows().get(0);
            assertThat(row.type()).isEqualTo(TransactionType.EXPENSE);
            assertThat(row.amount()).isEqualByComparingTo("1249.50");
            assertThat(row.txnDate()).isEqualTo(LocalDate.of(2026, 3, 14));
            assertThat(row.description()).isEqualTo("Weekly groceries");
            assertThat(row.merchant()).isEqualTo("Big Bazaar");
            assertThat(row.categoryName()).isEqualTo("Groceries");
            // Row 1 is the header, so the first data row is line 2 — matching what
            // a spreadsheet shows the user.
            assertThat(row.rowNumber()).isEqualTo(2);
        }

        @Test
        @DisplayName("accepts rows without the optional columns")
        void optionalColumnsMayBeBlank() {
            CsvParseResult result = parser.parse(
                    HEADER + "2026-03-14,EXPENSE,100.00,Lunch,,\n", MAX_ROWS);

            assertThat(result.errors()).isEmpty();
            assertThat(result.rows().get(0).merchant()).isNull();
            assertThat(result.rows().get(0).categoryName()).isNull();
        }

        @Test
        @DisplayName("numbers rows so the user can find them in the file")
        void rowNumbersAreOneBasedAndIncludeHeader() {
            CsvParseResult result = parser.parse(HEADER
                    + "2026-03-01,EXPENSE,10.00,First,,\n"
                    + "2026-03-02,EXPENSE,20.00,Second,,\n"
                    + "2026-03-03,EXPENSE,30.00,Third,,\n", MAX_ROWS);

            assertThat(result.rows()).extracting(ParsedTransactionRow::rowNumber)
                    .containsExactly(2, 3, 4);
        }

        @Test
        @DisplayName("skips blank lines without consuming a row number")
        void skipsBlankLines() {
            CsvParseResult result = parser.parse(HEADER
                    + "2026-03-01,EXPENSE,10.00,First,,\n"
                    + "\n"
                    + ",,,,,\n"
                    + "2026-03-02,EXPENSE,20.00,Second,,\n", MAX_ROWS);

            assertThat(result.rows()).hasSize(2);
            assertThat(result.errors()).isEmpty();
        }

        @Test
        @DisplayName("ignores a UTF-8 BOM, which spreadsheet exports commonly add")
        void handlesBom() {
            // Without BOM handling the first header becomes "﻿date" and the
            // required-column check fails on an otherwise perfect file.
            CsvParseResult result = parser.parse(
                    "﻿" + HEADER + "2026-03-14,EXPENSE,100.00,Lunch,,\n", MAX_ROWS);

            assertThat(result.errors()).isEmpty();
            assertThat(result.rows()).hasSize(1);
        }

        @Test
        @DisplayName("honours quoted fields containing commas")
        void handlesQuotedFields() {
            CsvParseResult result = parser.parse(
                    HEADER + "2026-03-14,EXPENSE,100.00,\"Lunch, dinner and snacks\",Cafe,Dining\n",
                    MAX_ROWS);

            assertThat(result.errors()).isEmpty();
            assertThat(result.rows().get(0).description()).isEqualTo("Lunch, dinner and snacks");
        }
    }

    // ---------------------------------------------------------------- aliases --

    @Nested
    @DisplayName("header and value aliases")
    class Aliases {

        @Test
        @DisplayName("matches headers case-insensitively and with whitespace")
        void headerNormalisation() {
            CsvParseResult result = parser.parse(
                    " DATE , Type ,AMOUNT, Description ,Merchant,CATEGORY\n"
                            + "2026-03-14,EXPENSE,100.00,Lunch,Cafe,Dining\n", MAX_ROWS);

            assertThat(result.errors()).isEmpty();
            assertThat(result.rows()).hasSize(1);
        }

        @Test
        @DisplayName("accepts common bank-export column names")
        void headerAliases() {
            CsvParseResult result = parser.parse(
                    "txn_date,transaction_type,value,narration,payee,tag\n"
                            + "2026-03-14,DEBIT,100.00,ATM withdrawal,HDFC,Groceries\n", MAX_ROWS);

            assertThat(result.errors()).isEmpty();
            assertThat(result.rows().get(0).type()).isEqualTo(TransactionType.EXPENSE);
        }

        @ParameterizedTest(name = "type \"{0}\" -> {1}")
        @CsvSource({
                "INCOME,  INCOME", "credit, INCOME", "CR, INCOME", "deposit, INCOME", "IN, INCOME",
                "EXPENSE, EXPENSE", "debit, EXPENSE", "DR, EXPENSE", "withdrawal, EXPENSE", "out, EXPENSE"
        })
        void typeAliases(String raw, TransactionType expected) {
            CsvParseResult result = parser.parse(
                    HEADER + "2026-03-14," + raw + ",100.00,Test,,\n", MAX_ROWS);

            assertThat(result.errors()).isEmpty();
            assertThat(result.rows().get(0).type()).isEqualTo(expected);
        }
    }

    // ------------------------------------------------------------------ dates --

    @Nested
    @DisplayName("date parsing")
    class Dates {

        @ParameterizedTest(name = "\"{0}\" is accepted")
        @CsvSource({
                "2026-03-14", "14/03/2026", "14-03-2026", "2026/03/14"
        })
        void acceptsSupportedFormats(String raw) {
            CsvParseResult result = parser.parse(
                    HEADER + raw + ",EXPENSE,100.00,Test,,\n", MAX_ROWS);

            assertThat(result.errors()).isEmpty();
            assertThat(result.rows().get(0).txnDate()).isEqualTo(LocalDate.of(2026, 3, 14));
        }

        @Test
        @DisplayName("rejects an impossible calendar date")
        void rejectsImpossibleDate() {
            // 31 February. Lenient parsing would silently shift this to 3 March.
            CsvParseResult result = parser.parse(
                    HEADER + "31/02/2026,EXPENSE,100.00,Test,,\n", MAX_ROWS);

            assertThat(result.rows()).isEmpty();
            assertThat(result.errors()).singleElement()
                    .satisfies(e -> assertThat(e.code()).isEqualTo(CsvRowError.INVALID_DATE));
        }

        @Test
        @DisplayName("rejects US-style MM/dd/yyyy rather than guessing")
        void rejectsAmbiguousUsFormat() {
            // 03/25/2026 would parse as day 3 of month 25 under dd/MM/yyyy and fail.
            // That is the intended outcome: supporting both orders would make the
            // first twelve days of every month ambiguous, and quietly filing a
            // March transaction in December is far worse than a rejected row.
            CsvParseResult result = parser.parse(
                    HEADER + "03/25/2026,EXPENSE,100.00,Test,,\n", MAX_ROWS);

            assertThat(result.errors()).singleElement()
                    .satisfies(e -> assertThat(e.code()).isEqualTo(CsvRowError.INVALID_DATE));
        }

        @Test
        @DisplayName("accepts a leap day in a leap year")
        void leapDay() {
            CsvParseResult result = parser.parse(
                    HEADER + "2024-02-29,EXPENSE,100.00,Leap day,,\n", MAX_ROWS);

            assertThat(result.errors()).isEmpty();
            assertThat(result.rows().get(0).txnDate()).isEqualTo(LocalDate.of(2024, 2, 29));
        }

        @Test
        @DisplayName("rejects a leap day in a non-leap year")
        void nonLeapYearFebruary29() {
            CsvParseResult result = parser.parse(
                    HEADER + "2026-02-29,EXPENSE,100.00,Not a leap year,,\n", MAX_ROWS);

            assertThat(result.errors()).hasSize(1);
        }
    }

    // ---------------------------------------------------------------- amounts --

    @Nested
    @DisplayName("amount parsing")
    class Amounts {

        @Test
        @DisplayName("strips thousands separators")
        void thousandsSeparators() {
            CsvParseResult result = parser.parse(
                    HEADER + "2026-03-14,EXPENSE,\"1,249.50\",Test,,\n", MAX_ROWS);

            assertThat(result.errors()).isEmpty();
            assertThat(result.rows().get(0).amount()).isEqualByComparingTo("1249.50");
        }

        @Test
        @DisplayName("strips currency symbols")
        void currencySymbols() {
            CsvParseResult result = parser.parse(
                    HEADER + "2026-03-14,EXPENSE,₹1249.50,Test,,\n", MAX_ROWS);

            assertThat(result.errors()).isEmpty();
            assertThat(result.rows().get(0).amount()).isEqualByComparingTo("1249.50");
        }

        @Test
        @DisplayName("reads a negative as a magnitude, because direction comes from type")
        void negativeBecomesMagnitude() {
            // Bank exports routinely write every expense as a negative number.
            // Storing it as -1249.50 would break CHECK (amount > 0) and every
            // aggregation that assumes a positive magnitude.
            CsvParseResult result = parser.parse(
                    HEADER + "2026-03-14,EXPENSE,-1249.50,Test,,\n", MAX_ROWS);

            assertThat(result.errors()).isEmpty();
            assertThat(result.rows().get(0).amount()).isEqualByComparingTo("1249.50");
        }

        @Test
        @DisplayName("reads accounting parentheses as a magnitude too")
        void parenthesesBecomeMagnitude() {
            CsvParseResult result = parser.parse(
                    HEADER + "2026-03-14,EXPENSE,(1249.50),Test,,\n", MAX_ROWS);

            assertThat(result.errors()).isEmpty();
            assertThat(result.rows().get(0).amount()).isEqualByComparingTo("1249.50");
        }

        @Test
        @DisplayName("normalises to the storage scale of four decimals")
        void storageScale() {
            CsvParseResult result = parser.parse(
                    HEADER + "2026-03-14,EXPENSE,100.5,Test,,\n", MAX_ROWS);

            assertThat(result.rows().get(0).amount().scale()).isEqualTo(4);
        }

        @Test
        @DisplayName("rejects zero")
        void rejectsZero() {
            CsvParseResult result = parser.parse(
                    HEADER + "2026-03-14,EXPENSE,0.00,Test,,\n", MAX_ROWS);

            assertThat(result.errors()).singleElement()
                    .satisfies(e -> {
                        assertThat(e.code()).isEqualTo(CsvRowError.INVALID_AMOUNT);
                        assertThat(e.message()).contains("greater than zero");
                    });
        }

        @ParameterizedTest(name = "rejects \"{0}\"")
        @ValueSource(strings = {"not-a-number", "abc", "--", "...", "1.2.3"})
        void rejectsNonNumeric(String raw) {
            CsvParseResult result = parser.parse(
                    HEADER + "2026-03-14,EXPENSE," + raw + ",Test,,\n", MAX_ROWS);

            assertThat(result.rows()).isEmpty();
            assertThat(result.errors()).singleElement()
                    .satisfies(e -> assertThat(e.code()).isEqualTo(CsvRowError.INVALID_AMOUNT));
        }

        @Test
        @DisplayName("rejects a value too large for NUMERIC(19,4)")
        void rejectsOverflow() {
            // Catching this here produces a useful row error; letting it reach the
            // database produces a numeric-overflow SQLException for the whole batch.
            CsvParseResult result = parser.parse(
                    HEADER + "2026-03-14,EXPENSE,99999999999999999999.00,Test,,\n", MAX_ROWS);

            assertThat(result.errors()).singleElement()
                    .satisfies(e -> assertThat(e.code()).isEqualTo(CsvRowError.INVALID_AMOUNT));
        }
    }

    // ------------------------------------------------------ required fields --

    @Nested
    @DisplayName("required fields")
    class RequiredFields {

        @Test
        @DisplayName("rejects a blank description")
        void blankDescription() {
            CsvParseResult result = parser.parse(
                    HEADER + "2026-03-14,EXPENSE,100.00,,Cafe,Dining\n", MAX_ROWS);

            assertThat(result.errors()).singleElement()
                    .satisfies(e -> assertThat(e.code()).isEqualTo(CsvRowError.MISSING_DESCRIPTION));
        }

        @Test
        @DisplayName("rejects a missing date")
        void missingDate() {
            CsvParseResult result = parser.parse(
                    HEADER + ",EXPENSE,100.00,Test,,\n", MAX_ROWS);

            assertThat(result.errors()).singleElement()
                    .satisfies(e -> assertThat(e.code()).isEqualTo(CsvRowError.MISSING_COLUMN));
        }

        @Test
        @DisplayName("rejects an unknown transaction type")
        void unknownType() {
            CsvParseResult result = parser.parse(
                    HEADER + "2026-03-14,TRANSFER,100.00,Test,,\n", MAX_ROWS);

            assertThat(result.errors()).singleElement()
                    .satisfies(e -> {
                        assertThat(e.code()).isEqualTo(CsvRowError.INVALID_TYPE);
                        assertThat(e.message()).contains("INCOME or EXPENSE");
                    });
        }

        @Test
        @DisplayName("rejects a description longer than the column allows")
        void descriptionTooLong() {
            String longText = "x".repeat(256);   // column is VARCHAR(255)
            CsvParseResult result = parser.parse(
                    HEADER + "2026-03-14,EXPENSE,100.00," + longText + ",,\n", MAX_ROWS);

            assertThat(result.errors()).singleElement()
                    .satisfies(e -> assertThat(e.code()).isEqualTo(CsvRowError.FIELD_TOO_LONG));
        }
    }

    // ------------------------------------------------------- partial failure --

    @Nested
    @DisplayName("partial failure")
    class PartialFailure {

        @Test
        @DisplayName("keeps good rows and reports every bad one in a single pass")
        void collectsAllErrors() {
            // The behaviour that matters for usability: a 2 000-row statement with
            // three typos must not import zero rows, and the user must learn about
            // all three problems at once rather than one per re-upload.
            CsvParseResult result = parser.parse(HEADER
                    + "2026-03-01,EXPENSE,100.00,Good one,,\n"
                    + "31/02/2026,EXPENSE,100.00,Bad date,,\n"
                    + "2026-03-03,EXPENSE,200.00,Good two,,\n"
                    + "2026-03-04,TRANSFER,100.00,Bad type,,\n"
                    + "2026-03-05,EXPENSE,abc,Bad amount,,\n"
                    + "2026-03-06,EXPENSE,300.00,Good three,,\n", MAX_ROWS);

            assertThat(result.rows()).hasSize(3);
            assertThat(result.errors()).hasSize(3);
            assertThat(result.totalRows()).isEqualTo(6);
            assertThat(result.hasErrors()).isTrue();

            assertThat(result.errors()).extracting(CsvRowError::code)
                    .containsExactlyInAnyOrder(
                            CsvRowError.INVALID_DATE,
                            CsvRowError.INVALID_TYPE,
                            CsvRowError.INVALID_AMOUNT);

            // Error row numbers point at the actual file lines.
            assertThat(result.errors()).extracting(CsvRowError::rowNumber)
                    .containsExactlyInAnyOrder(3, 5, 6);
        }
    }

    // ------------------------------------------------- structural rejection --

    @Nested
    @DisplayName("structurally unusable files")
    class StructuralFailures {

        @Test
        @DisplayName("rejects an empty file")
        void emptyFile() {
            assertThatThrownBy(() -> parser.parse("", MAX_ROWS))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).errorCode())
                    .isEqualTo(ErrorCode.INVALID_CSV);
        }

        @Test
        @DisplayName("rejects a null payload")
        void nullContent() {
            assertThatThrownBy(() -> parser.parse(null, MAX_ROWS))
                    .isInstanceOf(ApiException.class);
        }

        @Test
        @DisplayName("rejects a header with no data rows")
        void headerOnly() {
            assertThatThrownBy(() -> parser.parse(HEADER, MAX_ROWS))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("no data rows");
        }

        @Test
        @DisplayName("rejects a file with more rows than the configured maximum")
        void tooManyRows() {
            StringBuilder csv = new StringBuilder(HEADER);
            for (int i = 0; i < 12; i++) {
                csv.append("2026-03-01,EXPENSE,10.00,Row ").append(i).append(",,\n");
            }

            assertThatThrownBy(() -> parser.parse(csv.toString(), 10))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).errorCode())
                    .isEqualTo(ErrorCode.FILE_TOO_LARGE);
        }
    }
}
