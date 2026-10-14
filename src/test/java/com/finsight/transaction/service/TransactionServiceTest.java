package com.finsight.transaction.service;

import com.finsight.category.domain.Category;
import com.finsight.category.domain.CategoryType;
import com.finsight.category.service.CategoryService;
import com.finsight.common.cache.UserCacheEvictor;
import com.finsight.common.error.BusinessRuleException;
import com.finsight.common.error.ResourceNotFoundException;
import com.finsight.report.service.ReportService;
import com.finsight.transaction.domain.Transaction;
import com.finsight.transaction.domain.TransactionType;
import com.finsight.transaction.dto.TransactionFilter;
import com.finsight.transaction.dto.TransactionRequest;
import com.finsight.transaction.repository.TransactionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Transaction business rules, isolated from the database with Mockito.
 *
 * <p>The repository is mocked because these tests are about <em>decisions</em> —
 * which query is issued, what is rejected, when the cache is dropped — not about
 * whether JPA can write a row. Persistence correctness is covered by the
 * integration tests, where a real PostgreSQL enforces the constraints.
 */
@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

    private static final Long USER_ID = 7L;
    private static final Long OTHER_USER_ID = 8L;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private CategoryService categoryService;

    @Mock
    private UserCacheEvictor cacheEvictor;

    @InjectMocks
    private TransactionService transactionService;

    private TransactionRequest validRequest(Long categoryId) {
        return new TransactionRequest(
                TransactionType.EXPENSE,
                new BigDecimal("1249.50"),
                LocalDate.of(2026, 3, 14),
                "Weekly groceries",
                "Big Bazaar",
                null,
                categoryId);
    }

    private Category expenseCategory() {
        return Category.forUser(USER_ID, "Groceries", CategoryType.EXPENSE, "#EF6C00");
    }

    // ------------------------------------------------------------------ create --

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("persists the transaction against the authenticated user")
        void persistsForCaller() {
            when(transactionRepository.save(any(Transaction.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            transactionService.create(USER_ID, validRequest(null));

            ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
            verify(transactionRepository).save(captor.capture());

            Transaction saved = captor.getValue();
            // The owner comes from the method argument (which the controller takes
            // from the verified token), never from the request body.
            assertThat(saved.getUserId()).isEqualTo(USER_ID);
            assertThat(saved.getAmount()).isEqualByComparingTo("1249.50");
            assertThat(saved.getType()).isEqualTo(TransactionType.EXPENSE);
        }

        @Test
        @DisplayName("computes a duplicate fingerprint on creation")
        void computesDedupeHash() {
            when(transactionRepository.save(any(Transaction.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            transactionService.create(USER_ID, validRequest(null));

            ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
            verify(transactionRepository).save(captor.capture());

            assertThat(captor.getValue().getDedupeHash()).matches("^[0-9a-f]{64}$");
        }

        @Test
        @DisplayName("leaves externalRef null for a manual entry")
        void manualEntriesHaveNoIdempotencyKey() {
            // Only imported rows get an idempotency key. Setting one here would
            // make two genuinely identical manual entries impossible to record.
            when(transactionRepository.save(any(Transaction.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            transactionService.create(USER_ID, validRequest(null));

            ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
            verify(transactionRepository).save(captor.capture());

            assertThat(captor.getValue().getExternalRef()).isNull();
        }

        @Test
        @DisplayName("checks that the category is visible to the caller")
        void validatesCategoryOwnership() {
            when(categoryService.requireUsable(5L, USER_ID, CategoryType.EXPENSE))
                    .thenReturn(expenseCategory());
            when(transactionRepository.save(any(Transaction.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            transactionService.create(USER_ID, validRequest(5L));

            // The important assertion: the caller's id is passed down, so a category
            // belonging to someone else cannot be attached.
            verify(categoryService).requireUsable(5L, USER_ID, CategoryType.EXPENSE);
        }

        @Test
        @DisplayName("asks for an INCOME category when the transaction is income")
        void matchesCategoryTypeToTransactionType() {
            when(categoryService.requireUsable(anyLong(), anyLong(), eq(CategoryType.INCOME)))
                    .thenReturn(Category.forUser(USER_ID, "Salary", CategoryType.INCOME, null));
            when(transactionRepository.save(any(Transaction.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            transactionService.create(USER_ID, new TransactionRequest(
                    TransactionType.INCOME, new BigDecimal("95000.00"),
                    LocalDate.of(2026, 3, 1), "Salary", null, null, 3L));

            verify(categoryService).requireUsable(3L, USER_ID, CategoryType.INCOME);
        }

        @Test
        @DisplayName("allows an uncategorised transaction")
        void categoryIsOptional() {
            when(transactionRepository.save(any(Transaction.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            transactionService.create(USER_ID, validRequest(null));

            verify(categoryService, never()).requireUsable(anyLong(), anyLong(), any());
        }

        @Test
        @DisplayName("drops the caller's cached reports")
        void invalidatesReportCache() {
            // Reports are derived entirely from transactions, so a cached report
            // that survives a write is visibly wrong to the user.
            when(transactionRepository.save(any(Transaction.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            transactionService.create(USER_ID, validRequest(null));

            // Every report cache must be dropped, not just one — a new report cache
            // that nobody evicted would serve stale figures indefinitely.
            verify(cacheEvictor).evictUser(USER_ID, ReportService.REPORT_CACHES);
        }

        @Test
        @DisplayName("trims a blank merchant to null rather than storing whitespace")
        void trimsBlankOptionalFields() {
            when(transactionRepository.save(any(Transaction.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            transactionService.create(USER_ID, new TransactionRequest(
                    TransactionType.EXPENSE, new BigDecimal("10.00"),
                    LocalDate.of(2026, 3, 14), "  Lunch  ", "   ", "   ", null));

            ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
            verify(transactionRepository).save(captor.capture());

            assertThat(captor.getValue().getMerchant()).isNull();
            assertThat(captor.getValue().getNotes()).isNull();
            assertThat(captor.getValue().getDescription()).isEqualTo("Lunch");
        }
    }

    // ----------------------------------------------------------- data isolation --

    @Nested
    @DisplayName("user-data isolation")
    class DataIsolation {

        @Test
        @DisplayName("looks a transaction up by id AND owner")
        void getScopesByUser() {
            when(transactionRepository.findByIdAndUserId(99L, USER_ID))
                    .thenReturn(Optional.of(Transaction.manual(USER_ID, TransactionType.EXPENSE,
                            new BigDecimal("10.00"), LocalDate.of(2026, 3, 1), "Test", null, null)));

            transactionService.get(99L, USER_ID);

            // Ownership is part of the query, not a check applied to a loaded row.
            // A developer cannot forget the second half of this call.
            verify(transactionRepository).findByIdAndUserId(99L, USER_ID);
            verify(transactionRepository, never()).findById(anyLong());
        }

        @Test
        @DisplayName("reports another user's transaction as 404, not 403")
        void foreignTransactionIsNotFound() {
            // Returning 403 would confirm that id 99 exists, turning the endpoint
            // into an existence oracle an attacker can enumerate.
            when(transactionRepository.findByIdAndUserId(99L, OTHER_USER_ID))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> transactionService.get(99L, OTHER_USER_ID))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("refuses to update another user's transaction")
        void cannotUpdateForeignTransaction() {
            when(transactionRepository.findByIdAndUserId(99L, OTHER_USER_ID))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    transactionService.update(99L, OTHER_USER_ID, validRequest(null)))
                    .isInstanceOf(ResourceNotFoundException.class);

            verify(transactionRepository, never()).save(any(Transaction.class));
        }

        @Test
        @DisplayName("refuses to delete another user's transaction")
        void cannotDeleteForeignTransaction() {
            when(transactionRepository.findByIdAndUserId(99L, OTHER_USER_ID))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> transactionService.delete(99L, OTHER_USER_ID))
                    .isInstanceOf(ResourceNotFoundException.class);

            verify(transactionRepository, never()).delete(any(Transaction.class));
        }
    }

    // -------------------------------------------------------- filter validation --

    @Nested
    @DisplayName("filter validation")
    class FilterValidation {

        private final Pageable page = PageRequest.of(0, 20);

        @SuppressWarnings("unchecked")
        private void stubEmptyPage() {
            when(transactionRepository.findAll(any(Specification.class), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of()));
        }

        @Test
        @DisplayName("accepts a null filter")
        void nullFilterIsUnconstrained() {
            stubEmptyPage();
            assertThat(transactionService.list(USER_ID, null, page).content()).isEmpty();
        }

        @Test
        @DisplayName("rejects from after to")
        void rejectsInvertedDateRange() {
            TransactionFilter filter = new TransactionFilter(
                    LocalDate.of(2026, 3, 31), LocalDate.of(2026, 3, 1),
                    null, null, null, null, null, null, null);

            assertThatThrownBy(() -> transactionService.list(USER_ID, filter, page))
                    .isInstanceOf(BusinessRuleException.class)
                    .hasMessageContaining("must not be after");
        }

        @Test
        @DisplayName("rejects minAmount greater than maxAmount")
        void rejectsInvertedAmountRange() {
            TransactionFilter filter = new TransactionFilter(
                    null, null, null, null, null,
                    new BigDecimal("500"), new BigDecimal("100"), null, null);

            assertThatThrownBy(() -> transactionService.list(USER_ID, filter, page))
                    .isInstanceOf(BusinessRuleException.class)
                    .hasMessageContaining("minAmount");
        }

        @Test
        @DisplayName("rejects uncategorized=true combined with categoryIds")
        void rejectsContradictoryFilters() {
            // These two can never both hold, so the request is a client bug.
            // Silently returning an empty page would hide it.
            TransactionFilter filter = new TransactionFilter(
                    null, null, null, List.of(1L, 2L), Boolean.TRUE,
                    null, null, null, null);

            assertThatThrownBy(() -> transactionService.list(USER_ID, filter, page))
                    .isInstanceOf(BusinessRuleException.class)
                    .hasMessageContaining("cannot be combined");
        }

        @Test
        @DisplayName("allows a single-day range")
        void singleDayRangeIsValid() {
            stubEmptyPage();
            LocalDate day = LocalDate.of(2026, 3, 14);
            TransactionFilter filter = new TransactionFilter(
                    day, day, null, null, null, null, null, null, null);

            assertThat(transactionService.list(USER_ID, filter, page)).isNotNull();
        }
    }

    // ----------------------------------------------------------- page size cap --

    @Nested
    @DisplayName("page size cap")
    class PageSizeCap {

        @Test
        @DisplayName("caps an oversized page request")
        @SuppressWarnings("unchecked")
        void capsOversizedPage() {
            // ?size=1000000 would otherwise materialise a million entities and
            // serialise them — a one-request denial of service.
            when(transactionRepository.findAll(any(Specification.class), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of()));

            transactionService.list(USER_ID, null, PageRequest.of(0, 1_000_000));

            ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
            verify(transactionRepository).findAll(any(Specification.class), captor.capture());

            assertThat(captor.getValue().getPageSize())
                    .isEqualTo(TransactionService.MAX_PAGE_SIZE);
        }

        @Test
        @DisplayName("leaves a reasonable page size alone")
        @SuppressWarnings("unchecked")
        void preservesReasonablePageSize() {
            when(transactionRepository.findAll(any(Specification.class), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of()));

            transactionService.list(USER_ID, null, PageRequest.of(2, 50));

            ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
            verify(transactionRepository).findAll(any(Specification.class), captor.capture());

            assertThat(captor.getValue().getPageSize()).isEqualTo(50);
            assertThat(captor.getValue().getPageNumber()).isEqualTo(2);
        }
    }
}
