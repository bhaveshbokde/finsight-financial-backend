package com.finsight.transaction.dto;

import com.finsight.transaction.domain.TransactionType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Create/update payload for a transaction.
 *
 * <p>{@code amount} is a positive magnitude — see {@link TransactionType} for why
 * the sign lives in {@code type}. {@code @DecimalMin(inclusive = false)} rejects
 * zero as well as negatives, matching the database's {@code CHECK (amount > 0)};
 * validating in both places means a bad value is reported as a readable 400 rather
 * than surfacing as a constraint violation.
 *
 * <p>{@code @Digits(fraction = 4)} mirrors {@code NUMERIC(19,4)}. Without it, a
 * client sending {@code 10.123456} would be silently rounded on the way in.
 */
@Schema(name = "TransactionRequest")
public record TransactionRequest(

        @NotNull(message = "type is required")
        @Schema(example = "EXPENSE", allowableValues = {"INCOME", "EXPENSE"})
        TransactionType type,

        @NotNull(message = "amount is required")
        @DecimalMin(value = "0.0", inclusive = false, message = "must be greater than 0")
        @DecimalMax(value = "999999999999999.9999", message = "exceeds the maximum supported amount")
        @Digits(integer = 15, fraction = 4, message = "must have at most 4 decimal places")
        @Schema(example = "1249.50")
        BigDecimal amount,

        @NotNull(message = "txnDate is required")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        @Schema(example = "2026-03-14", description = "Calendar date; no time component")
        LocalDate txnDate,

        @NotBlank(message = "description is required")
        @Size(max = 255, message = "must not exceed 255 characters")
        @Schema(example = "Weekly groceries")
        String description,

        @Size(max = 120, message = "must not exceed 120 characters")
        @Schema(example = "Big Bazaar")
        String merchant,

        @Size(max = 500, message = "must not exceed 500 characters")
        @Schema(example = "Shared with flatmates")
        String notes,

        @Schema(example = "5", description = "Must be a category visible to the caller and of a matching type")
        Long categoryId
) {
}
