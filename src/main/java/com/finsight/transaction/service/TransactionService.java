package com.finsight.transaction.service;

import com.finsight.category.domain.Category;
import com.finsight.category.domain.CategoryType;
import com.finsight.category.service.CategoryService;
import com.finsight.common.cache.UserCacheEvictor;
import com.finsight.common.dto.PageResponse;
import com.finsight.common.error.BusinessRuleException;
import com.finsight.common.error.ResourceNotFoundException;
import com.finsight.common.util.DateRanges;
import com.finsight.report.service.ReportService;
import com.finsight.transaction.domain.Transaction;
import com.finsight.transaction.domain.TransactionType;
import com.finsight.transaction.dto.TransactionFilter;
import com.finsight.transaction.dto.TransactionRequest;
import com.finsight.transaction.dto.TransactionResponse;
import com.finsight.transaction.repository.TransactionRepository;
import com.finsight.transaction.repository.TransactionSpecifications;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * Transaction business logic.
 *
 * <h2>User-data isolation</h2>
 * Every method takes a {@code userId} that originates from the verified JWT, and
 * every query puts it in the WHERE clause. There is no code path that loads a
 * transaction by id alone and checks ownership afterwards — that pattern is one
 * forgotten {@code if} away from an IDOR vulnerability, and it leaks existence
 * through the difference between 403 and 404.
 *
 * <h2>Cache invalidation</h2>
 * Any write invalidates this user's report caches. Reports are derived entirely
 * from transactions, so a stale report after a write would be visibly wrong.
 */
@Service
public class TransactionService {

    private static final Logger log = LoggerFactory.getLogger(TransactionService.class);

    /** A single page may not exceed this, however large a {@code size} the client asks for. */
    public static final int MAX_PAGE_SIZE = 200;

    private final TransactionRepository transactionRepository;
    private final CategoryService categoryService;
    private final UserCacheEvictor cacheEvictor;

    public TransactionService(TransactionRepository transactionRepository,
                              CategoryService categoryService,
                              UserCacheEvictor cacheEvictor) {
        this.transactionRepository = transactionRepository;
        this.categoryService = categoryService;
        this.cacheEvictor = cacheEvictor;
    }

    // ------------------------------------------------------------------ reads --

    /**
     * Filtered, paginated listing.
     *
     * <p>Not cached. The filter space is effectively unbounded (any date range, any
     * amount window, any search term), so the hit rate would be near zero while the
     * memory cost and the invalidation surface would be large. Reports — few shapes,
     * expensive to compute — are the right thing to cache; list endpoints are not.
     */
    @Transactional(readOnly = true)
    public PageResponse<TransactionResponse> list(Long userId, TransactionFilter filter, Pageable pageable) {
        validateFilter(filter);

        Page<Transaction> page = transactionRepository.findAll(
                TransactionSpecifications.forUser(userId, filter), capPageSize(pageable));

        return PageResponse.from(page, TransactionResponse::from);
    }

    @Transactional(readOnly = true)
    public TransactionResponse get(Long transactionId, Long userId) {
        return TransactionResponse.from(requireOwned(transactionId, userId));
    }

    // ----------------------------------------------------------------- writes --

    @Transactional
    public TransactionResponse create(Long userId, TransactionRequest request) {
        Category category = resolveCategory(request.categoryId(), userId, request.type());

        Transaction transaction = Transaction.manual(
                userId,
                request.type(),
                request.amount(),
                request.txnDate(),
                request.description().trim(),
                trimToNull(request.merchant()),
                category);
        transaction.setNotes(trimToNull(request.notes()));

        transaction = transactionRepository.save(transaction);

        invalidateReports(userId);
        log.info("Created transaction id={} type={} for user id={}",
                transaction.getId(), transaction.getType(), userId);

        return TransactionResponse.from(transaction);
    }

    /**
     * Full replacement of a transaction's mutable fields.
     *
     * <p>The entity is loaded inside the transaction and mutated; Hibernate's dirty
     * checking issues the UPDATE at flush. Because {@code Transaction} carries a
     * {@code @Version}, that UPDATE includes {@code AND version = ?}: if another
     * request changed the row since this one read it, zero rows match and the
     * resulting {@code OptimisticLockingFailureException} becomes a 409 telling the
     * client to re-read and retry — rather than one write silently overwriting the
     * other.
     */
    @Transactional
    public TransactionResponse update(Long transactionId, Long userId, TransactionRequest request) {
        Transaction transaction = requireOwned(transactionId, userId);
        Category category = resolveCategory(request.categoryId(), userId, request.type());

        // applyEdit also recomputes dedupeHash, so the fingerprint cannot drift
        // away from the values it describes.
        transaction.applyEdit(
                request.type(),
                request.amount(),
                request.txnDate(),
                request.description().trim(),
                trimToNull(request.merchant()),
                trimToNull(request.notes()),
                category);

        invalidateReports(userId);
        return TransactionResponse.from(transaction);
    }

    @Transactional
    public void delete(Long transactionId, Long userId) {
        Transaction transaction = requireOwned(transactionId, userId);
        transactionRepository.delete(transaction);

        invalidateReports(userId);
        log.info("Deleted transaction id={} for user id={}", transactionId, userId);
    }

    // ---------------------------------------------------------------- helpers --

    /**
     * Loads a transaction the user owns, or throws 404.
     *
     * <p>The single place in this class that turns an id into an entity, so the
     * ownership predicate exists exactly once.
     */
    private Transaction requireOwned(Long transactionId, Long userId) {
        return transactionRepository.findByIdAndUserId(transactionId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction", transactionId));
    }

    /**
     * Resolves and checks a category reference.
     *
     * @return {@code null} when no category was supplied — an uncategorised
     *         transaction is legitimate and shows up under "Uncategorized" in reports
     */
    private Category resolveCategory(Long categoryId, Long userId, TransactionType transactionType) {
        if (categoryId == null) {
            return null;
        }
        CategoryType expected = transactionType == TransactionType.INCOME
                ? CategoryType.INCOME : CategoryType.EXPENSE;
        return categoryService.requireUsable(categoryId, userId, expected);
    }

    private void validateFilter(TransactionFilter filter) {
        if (filter == null) {
            return;
        }
        if (filter.from() != null && filter.to() != null) {
            DateRanges.validate(filter.from(), filter.to());
        }
        if (filter.minAmount() != null && filter.maxAmount() != null
                && filter.minAmount().compareTo(filter.maxAmount()) > 0) {
            throw new BusinessRuleException("minAmount must not be greater than maxAmount");
        }
        if (Boolean.TRUE.equals(filter.uncategorized())
                && filter.categoryIds() != null && !filter.categoryIds().isEmpty()) {
            throw new BusinessRuleException(
                    "uncategorized=true cannot be combined with categoryIds");
        }
    }

    /**
     * Caps the page size.
     *
     * <p>Without this, {@code ?size=1000000} is a one-request denial of service:
     * the database builds the whole result set, Hibernate materialises a million
     * entities and the JSON writer serialises them.
     */
    private Pageable capPageSize(Pageable pageable) {
        if (pageable.isUnpaged() || pageable.getPageSize() <= MAX_PAGE_SIZE) {
            return pageable;
        }
        return org.springframework.data.domain.PageRequest.of(
                pageable.getPageNumber(), MAX_PAGE_SIZE, pageable.getSort());
    }

    /** Drops this user's cached reports after any write that could change them. */
    private void invalidateReports(Long userId) {
        cacheEvictor.evictUser(userId, ReportService.REPORT_CACHES);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Exposed for the import pipeline, which needs the same ownership-scoped count. */
    @Transactional(readOnly = true)
    public long countInRange(Long userId, LocalDate from, LocalDate to) {
        DateRanges.validate(from, to);
        return transactionRepository.countInRange(userId, from, to);
    }
}
