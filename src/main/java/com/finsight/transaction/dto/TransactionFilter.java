package com.finsight.transaction.dto;

import com.finsight.transaction.domain.TransactionSource;
import com.finsight.transaction.domain.TransactionType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Query parameters for {@code GET /api/v1/transactions}.
 *
 * <p>Every field is optional; a null means "do not constrain on this". Spring binds
 * query parameters onto this record by name, so the endpoint signature stays one
 * parameter wide instead of eight.
 *
 * <p>Note what is <em>not</em> here: a {@code userId}. The owner comes from the
 * verified token, never from the query string.
 */
@Schema(name = "TransactionFilter", description = "Optional filters; omit a field to leave it unconstrained")
public record TransactionFilter(

        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        @Schema(example = "2026-03-01", description = "Inclusive lower bound on txnDate")
        LocalDate from,

        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        @Schema(example = "2026-03-31", description = "Inclusive upper bound on txnDate")
        LocalDate to,

        @Schema(example = "EXPENSE")
        TransactionType type,

        @Schema(description = "Match any of these category ids")
        List<Long> categoryIds,

        @Schema(description = "Match transactions with no category assigned")
        Boolean uncategorized,

        @DecimalMin(value = "0.0", message = "must not be negative")
        @Digits(integer = 15, fraction = 4, message = "must have at most 4 decimal places")
        @Schema(example = "100.00", description = "Inclusive lower bound on amount")
        BigDecimal minAmount,

        @DecimalMin(value = "0.0", message = "must not be negative")
        @Digits(integer = 15, fraction = 4, message = "must have at most 4 decimal places")
        @Schema(example = "5000.00", description = "Inclusive upper bound on amount")
        BigDecimal maxAmount,

        @Size(max = 120, message = "must not exceed 120 characters")
        @Schema(example = "amazon",
                description = "Case-insensitive substring match against description and merchant")
        String search,

        @Schema(example = "CSV_IMPORT")
        TransactionSource source
) {
    /** True when no filter at all was supplied, so the caller gets an unconstrained page. */
    public boolean isEmpty() {
        return from == null && to == null && type == null
                && (categoryIds == null || categoryIds.isEmpty())
                && uncategorized == null && minAmount == null && maxAmount == null
                && (search == null || search.isBlank()) && source == null;
    }
}
