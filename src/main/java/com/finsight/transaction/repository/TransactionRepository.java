package com.finsight.transaction.repository;

import com.finsight.transaction.domain.Transaction;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Data access for {@link Transaction}.
 *
 * <p>Extends {@link JpaSpecificationExecutor} so the filter endpoint can build a
 * dynamic WHERE clause from whichever parameters the caller supplied, instead of
 * this interface carrying a combinatorial explosion of
 * {@code findByUserIdAndTypeAndTxnDateBetweenAnd...} methods.
 */
public interface TransactionRepository
        extends JpaRepository<Transaction, Long>, JpaSpecificationExecutor<Transaction> {

    /**
     * Paginated, filtered listing.
     *
     * <p>The {@code @EntityGraph} is the important part: {@code Transaction.category}
     * is {@code LAZY}, and the response DTO reads the category name, so without a
     * fetch plan a 20-row page would issue 1 + 20 queries. The entity graph turns
     * that into a single LEFT JOIN. (The graph is safe to combine with paging here
     * because the association is to-one; fetching a to-one collection with a
     * {@code Pageable} would force Hibernate to page in memory.)
     */
    @Override
    @EntityGraph(attributePaths = "category")
    Page<Transaction> findAll(Specification<Transaction> spec, Pageable pageable);

    /**
     * Ownership-scoped single fetch.
     *
     * <p>{@code userId} is in the query, not checked afterwards — a caller cannot
     * forget it, and someone else's id is simply "not found".
     */
    @EntityGraph(attributePaths = "category")
    Optional<Transaction> findByIdAndUserId(Long id, Long userId);

    /**
     * Pessimistic variant used where a read-modify-write must not interleave.
     *
     * <p>{@code PESSIMISTIC_WRITE} issues {@code SELECT ... FOR UPDATE}, holding a
     * row lock until the transaction ends. Used sparingly: optimistic locking via
     * {@code @Version} is the default everywhere else, because a row lock held
     * across a web request serialises unrelated work.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Transaction t WHERE t.id = :id AND t.userId = :userId")
    Optional<Transaction> findByIdAndUserIdForUpdate(@Param("id") Long id, @Param("userId") Long userId);

    /** Idempotency probe used by the import pipeline before inserting a row. */
    boolean existsByUserIdAndExternalRef(Long userId, String externalRef);

    /**
     * Bulk idempotency probe.
     *
     * <p>Importing 5 000 rows with {@code existsByUserIdAndExternalRef} per row
     * would mean 5 000 round trips. One {@code IN} query returns the refs that are
     * already present, and the importer skips those.
     */
    @Query("SELECT t.externalRef FROM Transaction t WHERE t.userId = :userId AND t.externalRef IN :refs")
    Set<String> findExistingExternalRefs(@Param("userId") Long userId,
                                         @Param("refs") Collection<String> refs);

    /** Duplicate <em>detection</em> for the import summary; the hash is not unique. */
    @Query("SELECT t.dedupeHash FROM Transaction t WHERE t.userId = :userId AND t.dedupeHash IN :hashes")
    Set<String> findExistingDedupeHashes(@Param("userId") Long userId,
                                         @Param("hashes") Collection<String> hashes);

    /** Everything a given import created — used to show results and to support a rollback. */
    List<Transaction> findByImportJobIdAndUserId(Long importJobId, Long userId);

    long countByUserId(Long userId);

    @Query("SELECT COUNT(t) FROM Transaction t WHERE t.userId = :userId AND t.txnDate BETWEEN :from AND :to")
    long countInRange(@Param("userId") Long userId,
                      @Param("from") LocalDate from,
                      @Param("to") LocalDate to);

    /**
     * Removes every transaction created by one import.
     *
     * <p>{@code @Modifying} is required for a write query, and
     * {@code clearAutomatically}/{@code flushAutomatically} keep the persistence
     * context honest: a bulk JPQL DELETE bypasses the first-level cache, so without
     * these flags an entity loaded earlier in the same transaction would still look
     * alive after being deleted in the database.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM Transaction t WHERE t.importJobId = :importJobId AND t.userId = :userId")
    int deleteByImportJob(@Param("importJobId") Long importJobId, @Param("userId") Long userId);
}
