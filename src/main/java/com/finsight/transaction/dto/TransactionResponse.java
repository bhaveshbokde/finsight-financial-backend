package com.finsight.transaction.dto;

import com.finsight.common.util.Money;
import com.finsight.transaction.domain.Transaction;
import com.finsight.transaction.domain.TransactionSource;
import com.finsight.transaction.domain.TransactionType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * A transaction as returned by the API.
 *
 * <p>The category is flattened to {@code categoryId} / {@code categoryName} rather
 * than nested. Clients rendering a list want the name, not the colour, the
 * archived flag and the audit columns of a second entity; flattening also makes
 * the N+1 risk explicit — the only association touched is one already loaded by
 * the repository's entity graph.
 *
 * <p>Amounts are rounded to 2 decimals for presentation while the stored value
 * keeps 4. {@code dedupeHash} and the internal {@code version} are not exposed.
 */
@Schema(name = "TransactionResponse")
public record TransactionResponse(
        Long id,
        TransactionType type,

        @Schema(example = "1249.50", description = "Always positive; direction is given by `type`")
        BigDecimal amount,

        @Schema(example = "INR")
        String currency,

        @Schema(example = "2026-03-14")
        LocalDate txnDate,

        String description,
        String merchant,
        String notes,

        Long categoryId,
        String categoryName,

        @Schema(description = "MANUAL or CSV_IMPORT")
        TransactionSource source,

        @Schema(description = "Set when this row came from a CSV import")
        Long importJobId,

        Instant createdAt,
        Instant updatedAt
) {
    /**
     * Maps an entity to its response form.
     *
     * <p>Reads {@code transaction.getCategory()}, which is a LAZY association — safe
     * only because list queries fetch it with an entity graph and single fetches use
     * {@code @EntityGraph} too. With {@code spring.jpa.open-in-view=false} a missing
     * fetch plan would fail loudly here rather than quietly issuing an extra query
     * per row, which is exactly the feedback we want.
     */
    public static TransactionResponse from(Transaction transaction) {
        var category = transaction.getCategory();
        return new TransactionResponse(
                transaction.getId(),
                transaction.getType(),
                Money.display(transaction.getAmount()),
                transaction.getCurrency(),
                transaction.getTxnDate(),
                transaction.getDescription(),
                transaction.getMerchant(),
                transaction.getNotes(),
                category == null ? null : category.getId(),
                category == null ? null : category.getName(),
                transaction.getSource(),
                transaction.getImportJobId(),
                transaction.getCreatedAt(),
                transaction.getUpdatedAt());
    }
}
