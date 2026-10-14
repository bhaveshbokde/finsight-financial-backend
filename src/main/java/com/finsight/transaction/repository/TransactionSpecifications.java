package com.finsight.transaction.repository;

import com.finsight.transaction.domain.Transaction;
import com.finsight.transaction.dto.TransactionFilter;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Builds the dynamic WHERE clause for transaction searches.
 *
 * <p>The Criteria API is used rather than string concatenation, so every value
 * becomes a bound JDBC parameter. A {@code search} term of
 * {@code "'; DROP TABLE transactions; --"} is compared as a literal string — SQL
 * injection is structurally impossible here, not merely filtered against.
 *
 * <p>Each helper returns {@code null} when its input is absent, and
 * {@link #build} drops the nulls. That keeps "which filters were supplied" in one
 * place instead of scattering null checks through the service.
 */
public final class TransactionSpecifications {

    private TransactionSpecifications() {
        throw new AssertionError("Utility class — do not instantiate");
    }

    /**
     * Composes the full predicate for one user.
     *
     * <p>{@code ownedBy} is applied unconditionally and first. Ownership is not one
     * filter among many that a caller might omit — it is the invariant that makes
     * everything else safe.
     */
    public static Specification<Transaction> forUser(Long userId, TransactionFilter filter) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("userId"), userId));

            if (filter != null) {
                if (filter.from() != null) {
                    predicates.add(cb.greaterThanOrEqualTo(root.get("txnDate"), filter.from()));
                }
                if (filter.to() != null) {
                    predicates.add(cb.lessThanOrEqualTo(root.get("txnDate"), filter.to()));
                }
                if (filter.type() != null) {
                    predicates.add(cb.equal(root.get("type"), filter.type()));
                }
                if (filter.source() != null) {
                    predicates.add(cb.equal(root.get("source"), filter.source()));
                }
                if (filter.categoryIds() != null && !filter.categoryIds().isEmpty()) {
                    predicates.add(root.get("category").get("id").in(filter.categoryIds()));
                }
                if (Boolean.TRUE.equals(filter.uncategorized())) {
                    predicates.add(cb.isNull(root.get("category")));
                }
                if (filter.minAmount() != null) {
                    predicates.add(cb.greaterThanOrEqualTo(root.get("amount"), filter.minAmount()));
                }
                if (filter.maxAmount() != null) {
                    predicates.add(cb.lessThanOrEqualTo(root.get("amount"), filter.maxAmount()));
                }
                if (filter.search() != null && !filter.search().isBlank()) {
                    // Escape the LIKE wildcards so a term containing '%' matches a
                    // literal percent sign instead of becoming a full-table wildcard.
                    String term = "%" + escapeLike(filter.search().trim().toLowerCase(Locale.ROOT)) + "%";
                    predicates.add(cb.or(
                            cb.like(cb.lower(root.get("description")), term, '\\'),
                            cb.like(cb.lower(root.get("merchant")), term, '\\')));
                }
            }

            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    /** Ownership predicate on its own, for callers that need nothing else. */
    public static Specification<Transaction> ownedBy(Long userId) {
        return (root, query, cb) -> cb.equal(root.get("userId"), userId);
    }

    private static String escapeLike(String raw) {
        return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
