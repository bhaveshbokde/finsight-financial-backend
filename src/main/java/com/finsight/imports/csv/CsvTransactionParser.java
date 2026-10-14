package com.finsight.imports.csv;

import com.finsight.common.error.ApiException;
import com.finsight.common.error.ErrorCode;
import com.finsight.common.util.Money;
import com.finsight.transaction.domain.TransactionType;
import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Parses and validates an uploaded CSV into transaction rows.
 *
 * <h2>Expected format</h2>
 * <pre>
 * date,type,amount,description,merchant,category
 * 2026-03-01,EXPENSE,1249.50,Weekly groceries,Big Bazaar,Groceries
 * </pre>
 * {@code date}, {@code type}, {@code amount} and {@code description} are required;
 * {@code merchant} and {@code category} are optional. Header matching is
 * case-insensitive, tolerates surrounding whitespace and a UTF-8 BOM, and accepts
 * several aliases ({@code txn_date}, {@code memo}, {@code payee}, …) so a file
 * exported from a bank or spreadsheet usually works unmodified.
 *
 * <h2>Why the header is resolved once, not per row</h2>
 * OpenCSV's {@code CSVReaderHeaderAware} throws as soon as any line has a
 * different column count from the header, which aborts the whole file over one
 * malformed line. Resolving column positions once and indexing into each row
 * instead means a short or ragged line becomes a single reported row error while
 * every other row still imports.
 *
 * <h2>Accepted date formats</h2>
 * ISO {@code yyyy-MM-dd} first, then {@code dd/MM/yyyy}, {@code dd-MM-yyyy} and
 * {@code yyyy/MM/dd}, all resolved {@link ResolverStyle#STRICT}. Strict resolution
 * is essential: the default {@code SMART} style silently rewrites 31 February as
 * 28 February rather than rejecting it, which would file a transaction on a date
 * the user never wrote.
 *
 * <p>{@code MM/dd/yyyy} is deliberately absent. It is indistinguishable from
 * {@code dd/MM/yyyy} for the first twelve days of a month, and quietly filing a
 * March transaction in December is far worse than rejecting the row. Ambiguity is
 * resolved by refusing to guess.
 *
 * <h2>Amounts</h2>
 * Parsed with {@link BigDecimal}, never {@code double}. Thousands separators,
 * currency symbols and accounting parentheses are stripped. A negative or
 * parenthesised amount is read as a <em>magnitude</em> — direction always comes
 * from {@code type} — so a bank export that writes every expense as a negative
 * number imports correctly.
 *
 * <h2>Error handling</h2>
 * A bad row is recorded and parsing continues. Only a structural problem — an
 * unreadable file, or a header missing a required column — aborts, because in that
 * case no row can be interpreted.
 */
@Component
public class CsvTransactionParser {

    private static final Logger log = LoggerFactory.getLogger(CsvTransactionParser.class);

    /** Column aliases, compared lower-case and trimmed. */
    private static final Set<String> DATE_HEADERS =
            Set.of("date", "txn_date", "transaction_date", "transactiondate", "posted_date", "value_date");
    private static final Set<String> TYPE_HEADERS =
            Set.of("type", "txn_type", "transaction_type", "direction");
    private static final Set<String> AMOUNT_HEADERS =
            Set.of("amount", "value", "txn_amount", "transaction_amount");
    private static final Set<String> DESCRIPTION_HEADERS =
            Set.of("description", "memo", "narration", "particulars", "details");
    private static final Set<String> MERCHANT_HEADERS =
            Set.of("merchant", "payee", "vendor", "counterparty");
    private static final Set<String> CATEGORY_HEADERS =
            Set.of("category", "category_name", "tag");

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,                                        // 2026-03-14
            strict("dd/MM/uuuu"),                                                    // 14/03/2026
            strict("dd-MM-uuuu"),                                                    // 14-03-2026
            strict("uuuu/MM/dd")                                                     // 2026/03/14
    );

    /** Matches the column widths declared in migration V4. */
    private static final int MAX_DESCRIPTION = 255;
    private static final int MAX_MERCHANT = 120;

    /** Largest integer part NUMERIC(19,4) can hold. */
    private static final int MAX_INTEGER_DIGITS = 15;

    private static final String BOM = "﻿";

    /**
     * Parses the whole file.
     *
     * @param csvContent the decoded file text
     * @param maxRows    refuse files with more data rows than this
     * @throws ApiException {@link ErrorCode#INVALID_CSV} when the file is structurally
     *                      unusable, or {@link ErrorCode#FILE_TOO_LARGE} when it has too many rows
     */
    public CsvParseResult parse(String csvContent, int maxRows) {
        if (csvContent == null || csvContent.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_CSV, "The uploaded file is empty");
        }

        String normalised = csvContent.startsWith(BOM) ? csvContent.substring(BOM.length()) : csvContent;

        List<ParsedTransactionRow> rows = new ArrayList<>();
        List<CsvRowError> errors = new ArrayList<>();

        try (CSVReader reader = new CSVReader(new StringReader(normalised))) {

            String[] headerCells = reader.readNext();
            if (headerCells == null) {
                throw new ApiException(ErrorCode.INVALID_CSV, "The uploaded file is empty");
            }

            Map<String, Integer> headerIndex = indexHeaders(headerCells);
            requireHeaders(headerIndex);

            int rowNumber = 1;   // the header occupies line 1
            String[] cells;

            while ((cells = reader.readNext()) != null) {
                if (isBlankRow(cells)) {
                    // Trailing newlines and spacer rows are normal in exported
                    // files; they are not data and must not consume a row number,
                    // or every subsequent error would point at the wrong line.
                    continue;
                }
                rowNumber++;

                if (rowNumber > maxRows + 1) {
                    throw new ApiException(ErrorCode.FILE_TOO_LARGE,
                            "The file contains more than %d data rows".formatted(maxRows));
                }

                String raw = String.join(",", cells);
                try {
                    rows.add(parseRow(cells, headerIndex, rowNumber));
                } catch (RowValidationException e) {
                    errors.add(new CsvRowError(rowNumber, raw, e.code(), e.getMessage()));
                }
            }

            if (rows.isEmpty() && errors.isEmpty()) {
                throw new ApiException(ErrorCode.INVALID_CSV,
                        "The file contains a header but no data rows");
            }

        } catch (IOException | CsvValidationException e) {
            // A structural failure: the file could not be tokenised at all.
            log.warn("CSV could not be tokenised: {}", e.getMessage());
            throw new ApiException(ErrorCode.INVALID_CSV,
                    "The file could not be read as CSV. Check the delimiter and quoting.");
        }

        return new CsvParseResult(List.copyOf(rows), List.copyOf(errors));
    }

    // ------------------------------------------------------------------ header --

    /** Maps each normalised header name to its column position. */
    private Map<String, Integer> indexHeaders(String[] headerCells) {
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < headerCells.length; i++) {
            String name = headerCells[i];
            if (name == null) {
                continue;
            }
            String key = name.replace(BOM, "").trim().toLowerCase(Locale.ROOT);
            if (!key.isEmpty()) {
                // First occurrence wins, so a duplicated column does not shadow the
                // one the rest of the file actually uses.
                index.putIfAbsent(key, i);
            }
        }
        return index;
    }

    /**
     * Fails the whole file when a required column is absent.
     *
     * <p>Structural, not per-row: if there is no {@code amount} column, every one of
     * 5 000 rows would produce the identical error. One clear message naming the
     * expected columns is far more useful.
     */
    private void requireHeaders(Map<String, Integer> headerIndex) {
        List<String> missing = new ArrayList<>();
        if (resolve(headerIndex, DATE_HEADERS) < 0) {
            missing.add("date");
        }
        if (resolve(headerIndex, TYPE_HEADERS) < 0) {
            missing.add("type");
        }
        if (resolve(headerIndex, AMOUNT_HEADERS) < 0) {
            missing.add("amount");
        }
        if (resolve(headerIndex, DESCRIPTION_HEADERS) < 0) {
            missing.add("description");
        }
        if (!missing.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_CSV,
                    "The header is missing required column(s): %s. Expected: "
                            .formatted(String.join(", ", missing))
                            + "date, type, amount, description[, merchant, category]");
        }
    }

    private int resolve(Map<String, Integer> headerIndex, Set<String> aliases) {
        for (String alias : aliases) {
            Integer position = headerIndex.get(alias);
            if (position != null) {
                return position;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------- row parsing --

    private ParsedTransactionRow parseRow(String[] cells, Map<String, Integer> headerIndex,
                                          int rowNumber) {
        LocalDate date = parseDate(require(cells, headerIndex, DATE_HEADERS, "date"));
        TransactionType type = parseType(require(cells, headerIndex, TYPE_HEADERS, "type"));
        BigDecimal amount = parseAmount(require(cells, headerIndex, AMOUNT_HEADERS, "amount"));

        String description = find(cells, headerIndex, DESCRIPTION_HEADERS);
        if (description == null) {
            throw new RowValidationException(CsvRowError.MISSING_DESCRIPTION,
                    "Column 'description' is required and must not be blank");
        }
        if (description.length() > MAX_DESCRIPTION) {
            throw new RowValidationException(CsvRowError.FIELD_TOO_LONG,
                    "Column 'description' exceeds %d characters".formatted(MAX_DESCRIPTION));
        }

        String merchant = find(cells, headerIndex, MERCHANT_HEADERS);
        if (merchant != null && merchant.length() > MAX_MERCHANT) {
            throw new RowValidationException(CsvRowError.FIELD_TOO_LONG,
                    "Column 'merchant' exceeds %d characters".formatted(MAX_MERCHANT));
        }

        String category = find(cells, headerIndex, CATEGORY_HEADERS);

        return new ParsedTransactionRow(rowNumber, type, amount, date, description, merchant, category);
    }

    private LocalDate parseDate(String value) {
        for (DateTimeFormatter format : DATE_FORMATS) {
            try {
                return LocalDate.parse(value, format);
            } catch (DateTimeParseException ignored) {
                // Try the next supported format.
            }
        }
        throw new RowValidationException(CsvRowError.INVALID_DATE,
                "Could not read '%s' as a date. Use yyyy-MM-dd (for example 2026-03-14)."
                        .formatted(value));
    }

    private TransactionType parseType(String value) {
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "INCOME", "CREDIT", "CR", "IN", "DEPOSIT" -> TransactionType.INCOME;
            case "EXPENSE", "DEBIT", "DR", "OUT", "WITHDRAWAL" -> TransactionType.EXPENSE;
            default -> throw new RowValidationException(CsvRowError.INVALID_TYPE,
                    "Could not read '%s' as a transaction type. Use INCOME or EXPENSE.".formatted(value));
        };
    }

    /**
     * Parses a monetary value.
     *
     * <p>Accepts {@code 1,249.50}, {@code ₹1249.50}, {@code -1249.50} and
     * {@code (1249.50)}. The sign is discarded — direction belongs to {@code type}.
     */
    private BigDecimal parseAmount(String value) {
        String cleaned = value.trim()
                .replace("(", "").replace(")", "")   // accounting negatives
                .replaceAll("[^0-9.\\-]", "");       // currency symbols, separators, spaces

        if (cleaned.isEmpty() || cleaned.equals("-") || cleaned.equals(".")) {
            throw new RowValidationException(CsvRowError.INVALID_AMOUNT,
                    "Could not read '%s' as an amount".formatted(value));
        }

        BigDecimal parsed;
        try {
            parsed = new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            throw new RowValidationException(CsvRowError.INVALID_AMOUNT,
                    "Could not read '%s' as an amount".formatted(value));
        }

        BigDecimal magnitude = parsed.abs();
        if (magnitude.compareTo(BigDecimal.ZERO) == 0) {
            throw new RowValidationException(CsvRowError.INVALID_AMOUNT,
                    "Amount must be greater than zero");
        }
        // Reject here rather than letting PostgreSQL raise a numeric-overflow error
        // that would abort the whole batch instead of one row.
        if (magnitude.precision() - magnitude.scale() > MAX_INTEGER_DIGITS) {
            throw new RowValidationException(CsvRowError.INVALID_AMOUNT,
                    "Amount '%s' exceeds the maximum supported value".formatted(value));
        }

        // Scale to storage precision now, so the persisted value and the value used
        // for the duplicate fingerprint are byte-identical.
        return magnitude.setScale(Money.STORAGE_SCALE, Money.ROUNDING);
    }

    // ------------------------------------------------------------------ helpers --

    /**
     * Reads one column, returning null when absent, out of range or blank.
     *
     * <p>The length check is what makes a short row survivable: a line with four
     * cells against a six-column header yields nulls for the missing two rather
     * than an {@link ArrayIndexOutOfBoundsException}.
     */
    private String find(String[] cells, Map<String, Integer> headerIndex, Set<String> aliases) {
        int position = resolve(headerIndex, aliases);
        if (position < 0 || position >= cells.length) {
            return null;
        }
        String value = cells[position];
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String require(String[] cells, Map<String, Integer> headerIndex,
                           Set<String> aliases, String columnName) {
        String value = find(cells, headerIndex, aliases);
        if (value == null) {
            throw new RowValidationException(CsvRowError.MISSING_COLUMN,
                    "Column '%s' is required and must not be blank".formatted(columnName));
        }
        return value;
    }

    private boolean isBlankRow(String[] cells) {
        for (String cell : cells) {
            if (cell != null && !cell.isBlank()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Builds a strict formatter.
     *
     * <p>{@code uuuu} rather than {@code yyyy} because {@link ResolverStyle#STRICT}
     * rejects {@code yyyy} (year-of-era) without an era field. {@code uuuu} is the
     * proleptic year and resolves cleanly.
     */
    private static DateTimeFormatter strict(String pattern) {
        return DateTimeFormatter.ofPattern(pattern, Locale.ROOT).withResolverStyle(ResolverStyle.STRICT);
    }

    /**
     * Internal control flow for a single bad row.
     *
     * <p>Not an {@link ApiException}: one bad row must not fail the request. It is
     * caught by {@link #parse} and turned into a {@link CsvRowError}. The stack
     * trace is suppressed because thousands of these may be created while parsing a
     * messy file and none of them is a programming error — filling in a stack trace
     * is the expensive part of constructing an exception.
     */
    private static final class RowValidationException extends RuntimeException {

        private final transient String code;

        RowValidationException(String code, String message) {
            super(message, null, false, false);   // no suppression, no writable stack trace
            this.code = code;
        }

        String code() {
            return code;
        }
    }
}
