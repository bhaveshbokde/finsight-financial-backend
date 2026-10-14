package com.finsight.imports.service;

import com.finsight.category.domain.Category;
import com.finsight.category.domain.CategoryType;
import com.finsight.category.repository.CategoryRepository;
import com.finsight.common.cache.UserCacheEvictor;
import com.finsight.common.util.Hashes;
import com.finsight.imports.csv.CsvParseResult;
import com.finsight.imports.csv.CsvRowError;
import com.finsight.imports.csv.CsvTransactionParser;
import com.finsight.imports.csv.ParsedTransactionRow;
import com.finsight.imports.domain.ImportJob;
import com.finsight.imports.domain.ImportRowError;
import com.finsight.imports.domain.ImportStatus;
import com.finsight.imports.repository.ImportJobRepository;
import com.finsight.imports.repository.ImportPayloadRepository;
import com.finsight.imports.repository.ImportRowErrorRepository;
import com.finsight.transaction.domain.Transaction;
import com.finsight.transaction.domain.TransactionSource;
import com.finsight.transaction.domain.TransactionType;
import com.finsight.transaction.repository.TransactionRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Import row application: idempotency, duplicate accounting, partial failure and
 * category resolution.
 *
 * <p>Mocks stand in for the repositories so the <em>decisions</em> can be
 * inspected — which rows become transactions, which are counted as duplicates,
 * which are rejected. That the database then refuses a genuinely duplicate insert
 * is verified separately, against real PostgreSQL, in {@code CsvImportIT}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ImportProcessorTest {

    private static final Long USER_ID = 7L;
    private static final Long JOB_ID = 100L;
    private static final String CHECKSUM = "a".repeat(64);

    @Mock private ImportJobRepository jobRepository;
    @Mock private ImportPayloadRepository payloadRepository;
    @Mock private ImportRowErrorRepository rowErrorRepository;
    @Mock private TransactionRepository transactionRepository;
    @Mock private CategoryRepository categoryRepository;
    @Mock private UserCacheEvictor cacheEvictor;

    private ImportProcessor processor;
    private ImportJob job;

    @BeforeEach
    void setUp() {
        processor = new ImportProcessor(
                jobRepository, payloadRepository, rowErrorRepository, transactionRepository,
                categoryRepository, new CsvTransactionParser(), cacheEvictor,
                new SimpleMeterRegistry());

        job = ImportJob.accepted(USER_ID, "statement.csv", CHECKSUM, 1024, 0);
        setId(job, JOB_ID);

        when(categoryRepository.findAllUsableBy(USER_ID)).thenReturn(List.of(
                categoryWithId(1L, "Groceries", CategoryType.EXPENSE),
                categoryWithId(2L, "Salary", CategoryType.INCOME)));
    }

    private ParsedTransactionRow expenseRow(int rowNumber, String amount, String category) {
        return new ParsedTransactionRow(rowNumber, TransactionType.EXPENSE,
                new BigDecimal(amount), LocalDate.of(2026, 3, rowNumber),
                "Row " + rowNumber, "Merchant", category);
    }

    // ------------------------------------------------------------ idempotency --

    @Nested
    @DisplayName("idempotency")
    class Idempotency {

        @Test
        @DisplayName("the row key depends only on file content and row position")
        void externalRefIsDeterministic() {
            // This is the property the whole guarantee rests on: the same file
            // processed again produces the same keys, so the unique index on
            // (user_id, external_ref) refuses the second insert.
            String first = ImportProcessor.externalRef(CHECKSUM, 5);
            String second = ImportProcessor.externalRef(CHECKSUM, 5);

            assertThat(first).isEqualTo(second).matches("^[0-9a-f]{64}$");
        }

        @Test
        @DisplayName("different rows of the same file get different keys")
        void distinctPerRow() {
            assertThat(ImportProcessor.externalRef(CHECKSUM, 5))
                    .isNotEqualTo(ImportProcessor.externalRef(CHECKSUM, 6));
        }

        @Test
        @DisplayName("the same row position in a different file gets a different key")
        void distinctPerFile() {
            assertThat(ImportProcessor.externalRef(CHECKSUM, 5))
                    .isNotEqualTo(ImportProcessor.externalRef("b".repeat(64), 5));
        }

        @Test
        @DisplayName("skips rows whose key already exists and counts them as duplicates")
        void skipsAlreadyImportedRows() {
            // Simulates a redelivered Kafka message or a re-uploaded file: row 2's
            // key is already in the database, row 3's is not.
            String existingRef = ImportProcessor.externalRef(CHECKSUM, 2);
            when(transactionRepository.findExistingExternalRefs(anyLong(), anyCollection()))
                    .thenReturn(Set.of(existingRef));

            processor.applyParsedRows(job, new CsvParseResult(
                    List.of(expenseRow(2, "100.00", null), expenseRow(3, "200.00", null)),
                    List.of()));

            assertThat(job.getImportedRows()).isEqualTo(1);
            assertThat(job.getDuplicateRows()).isEqualTo(1);
            assertThat(job.getFailedRows()).isZero();
            // A duplicate is not an error — the file was fine, the work was simply
            // already done.
            assertThat(job.getStatus()).isEqualTo(ImportStatus.COMPLETED);
        }

        @Test
        @DisplayName("imports nothing when every key already exists")
        void fullyDuplicateFile() {
            when(transactionRepository.findExistingExternalRefs(anyLong(), anyCollection()))
                    .thenReturn(Set.of(
                            ImportProcessor.externalRef(CHECKSUM, 2),
                            ImportProcessor.externalRef(CHECKSUM, 3)));

            processor.applyParsedRows(job, new CsvParseResult(
                    List.of(expenseRow(2, "100.00", null), expenseRow(3, "200.00", null)),
                    List.of()));

            assertThat(job.getImportedRows()).isZero();
            assertThat(job.getDuplicateRows()).isEqualTo(2);
            verify(transactionRepository, never()).saveAll(anyCollection());
        }

        @Test
        @DisplayName("checks existing keys in one query, not one per row")
        void bulkIdempotencyProbe() {
            // Per-row existence checks would mean 5 000 round trips for a
            // 5 000-row file. One IN query returns the whole set.
            when(transactionRepository.findExistingExternalRefs(anyLong(), anyCollection()))
                    .thenReturn(Set.of());

            processor.applyParsedRows(job, new CsvParseResult(
                    List.of(expenseRow(2, "10.00", null), expenseRow(3, "20.00", null),
                            expenseRow(4, "30.00", null)),
                    List.of()));

            verify(transactionRepository).findExistingExternalRefs(anyLong(), anyCollection());
            verify(transactionRepository, never()).existsByUserIdAndExternalRef(anyLong(), any());
        }

        @Test
        @DisplayName("assigns counters rather than incrementing them, so a retry does not double")
        void countersAreAssignedNotAccumulated() {
            when(transactionRepository.findExistingExternalRefs(anyLong(), anyCollection()))
                    .thenReturn(Set.of());

            CsvParseResult parsed = new CsvParseResult(
                    List.of(expenseRow(2, "100.00", null)), List.of());

            processor.applyParsedRows(job, parsed);
            assertThat(job.getImportedRows()).isEqualTo(1);

            // Second pass, as a redelivery would do. Now the key exists.
            when(transactionRepository.findExistingExternalRefs(anyLong(), anyCollection()))
                    .thenReturn(Set.of(ImportProcessor.externalRef(CHECKSUM, 2)));

            processor.applyParsedRows(job, parsed);

            assertThat(job.getImportedRows()).isZero();         // not 1 + 0
            assertThat(job.getDuplicateRows()).isEqualTo(1);    // not 0 + 1 added to a stale value
        }

        @Test
        @DisplayName("clears previous row errors before re-processing")
        void clearsStaleErrorsOnRetry() {
            // Without this, a redelivery would append a second copy of every error
            // and the job would appear to have twice the problems the file has.
            when(transactionRepository.findExistingExternalRefs(anyLong(), anyCollection()))
                    .thenReturn(Set.of());

            processor.applyParsedRows(job, new CsvParseResult(List.of(), List.of(
                    new CsvRowError(2, "bad", CsvRowError.INVALID_DATE, "bad date"))));

            verify(rowErrorRepository).deleteByImportJobId(JOB_ID);
        }
    }

    // ------------------------------------------------------- transaction shape --

    @Nested
    @DisplayName("created transactions")
    class CreatedTransactions {

        @Test
        @DisplayName("are marked as imported and carry their job and key")
        void importedMetadata() {
            when(transactionRepository.findExistingExternalRefs(anyLong(), anyCollection()))
                    .thenReturn(Set.of());

            processor.applyParsedRows(job, new CsvParseResult(
                    List.of(expenseRow(2, "1249.50", null)), List.of()));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<Transaction>> captor = ArgumentCaptor.forClass(List.class);
            verify(transactionRepository).saveAll(captor.capture());

            Transaction saved = captor.getValue().get(0);
            assertThat(saved.getSource()).isEqualTo(TransactionSource.CSV_IMPORT);
            assertThat(saved.getImportJobId()).isEqualTo(JOB_ID);
            assertThat(saved.getExternalRef()).isEqualTo(ImportProcessor.externalRef(CHECKSUM, 2));
            assertThat(saved.getUserId()).isEqualTo(USER_ID);
            assertThat(saved.getAmount()).isEqualByComparingTo("1249.50");
        }

        @Test
        @DisplayName("resolve a category name case-insensitively")
        void resolvesCategoryByName() {
            when(transactionRepository.findExistingExternalRefs(anyLong(), anyCollection()))
                    .thenReturn(Set.of());

            processor.applyParsedRows(job, new CsvParseResult(
                    List.of(expenseRow(2, "100.00", "groceries")), List.of()));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<Transaction>> captor = ArgumentCaptor.forClass(List.class);
            verify(transactionRepository).saveAll(captor.capture());

            assertThat(captor.getValue().get(0).getCategory().getName()).isEqualTo("Groceries");
        }

        @Test
        @DisplayName("are left uncategorised when the row names no category")
        void allowsUncategorised() {
            when(transactionRepository.findExistingExternalRefs(anyLong(), anyCollection()))
                    .thenReturn(Set.of());

            processor.applyParsedRows(job, new CsvParseResult(
                    List.of(expenseRow(2, "100.00", null)), List.of()));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<Transaction>> captor = ArgumentCaptor.forClass(List.class);
            verify(transactionRepository).saveAll(captor.capture());

            assertThat(captor.getValue().get(0).getCategory()).isNull();
            assertThat(job.getImportedRows()).isEqualTo(1);
        }

        @Test
        @DisplayName("look up the user's categories once, not once per row")
        void categoryIndexLoadedOnce() {
            when(transactionRepository.findExistingExternalRefs(anyLong(), anyCollection()))
                    .thenReturn(Set.of());

            processor.applyParsedRows(job, new CsvParseResult(
                    List.of(expenseRow(2, "10.00", "Groceries"),
                            expenseRow(3, "20.00", "Groceries"),
                            expenseRow(4, "30.00", "Groceries")),
                    List.of()));

            verify(categoryRepository).findAllUsableBy(USER_ID);
        }
    }

    // ---------------------------------------------------------- partial failure --

    @Nested
    @DisplayName("partial failure")
    class PartialFailure {

        @Test
        @DisplayName("imports good rows and records bad ones individually")
        void importsGoodRowsDespiteBadOnes() {
            when(transactionRepository.findExistingExternalRefs(anyLong(), anyCollection()))
                    .thenReturn(Set.of());

            processor.applyParsedRows(job, new CsvParseResult(
                    List.of(expenseRow(2, "100.00", null), expenseRow(4, "200.00", null)),
                    List.of(new CsvRowError(3, "31/02/2026,...", CsvRowError.INVALID_DATE,
                            "Could not read '31/02/2026' as a date"))));

            assertThat(job.getImportedRows()).isEqualTo(2);
            assertThat(job.getFailedRows()).isEqualTo(1);
            assertThat(job.getStatus()).isEqualTo(ImportStatus.COMPLETED_WITH_ERRORS);
            assertThat(job.getTotalRows()).isEqualTo(3);
        }

        @Test
        @DisplayName("rejects a row naming a category that does not exist")
        void unknownCategoryBecomesRowError() {
            when(transactionRepository.findExistingExternalRefs(anyLong(), anyCollection()))
                    .thenReturn(Set.of());

            processor.applyParsedRows(job, new CsvParseResult(
                    List.of(expenseRow(2, "100.00", "Cryptocurrency")), List.of()));

            assertThat(job.getImportedRows()).isZero();
            assertThat(job.getFailedRows()).isEqualTo(1);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<ImportRowError>> captor = ArgumentCaptor.forClass(List.class);
            verify(rowErrorRepository).saveAll(captor.capture());
            assertThat(captor.getValue().get(0).getErrorCode())
                    .isEqualTo(CsvRowError.UNKNOWN_CATEGORY);
        }

        @Test
        @DisplayName("rejects an INCOME row filed under an EXPENSE category")
        void categoryTypeMismatchBecomesRowError() {
            // Allowing this would make the category breakdown disagree with the
            // income/expense totals, which is very hard to spot in a chart.
            when(transactionRepository.findExistingExternalRefs(anyLong(), anyCollection()))
                    .thenReturn(Set.of());

            ParsedTransactionRow incomeUnderExpenseCategory = new ParsedTransactionRow(
                    2, TransactionType.INCOME, new BigDecimal("5000.00"),
                    LocalDate.of(2026, 3, 1), "Client payment", null, "Groceries");

            processor.applyParsedRows(job, new CsvParseResult(
                    List.of(incomeUnderExpenseCategory), List.of()));

            assertThat(job.getFailedRows()).isEqualTo(1);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<ImportRowError>> captor = ArgumentCaptor.forClass(List.class);
            verify(rowErrorRepository).saveAll(captor.capture());
            assertThat(captor.getValue().get(0).getErrorCode())
                    .isEqualTo(CsvRowError.CATEGORY_TYPE_MISMATCH);
        }

        @Test
        @DisplayName("counters always satisfy total = imported + duplicates + failed")
        void countersReconcile() {
            when(transactionRepository.findExistingExternalRefs(anyLong(), anyCollection()))
                    .thenReturn(Set.of(ImportProcessor.externalRef(CHECKSUM, 5)));

            processor.applyParsedRows(job, new CsvParseResult(
                    List.of(expenseRow(2, "100.00", null),              // imported
                            expenseRow(3, "200.00", "Nonexistent"),     // failed (unknown category)
                            expenseRow(5, "300.00", null)),             // duplicate
                    List.of(new CsvRowError(4, "bad", CsvRowError.INVALID_DATE, "bad"))));  // failed

            assertThat(job.getTotalRows())
                    .isEqualTo(job.getImportedRows() + job.getDuplicateRows() + job.getFailedRows());
            assertThat(job.getImportedRows()).isEqualTo(1);
            assertThat(job.getDuplicateRows()).isEqualTo(1);
            assertThat(job.getFailedRows()).isEqualTo(2);
        }
    }

    // --------------------------------------------------------------- lifecycle --

    @Nested
    @DisplayName("lifecycle")
    class Lifecycle {

        @Test
        @DisplayName("deletes the stored upload once the job is finished")
        void discardsPayloadWhenDone() {
            // The parsed transactions are the durable result; keeping the user's
            // raw statement indefinitely is a data-retention liability.
            when(transactionRepository.findExistingExternalRefs(anyLong(), anyCollection()))
                    .thenReturn(Set.of());

            processor.applyParsedRows(job, new CsvParseResult(
                    List.of(expenseRow(2, "100.00", null)), List.of()));

            verify(payloadRepository).deleteByImportJobId(JOB_ID);
        }

        @Test
        @DisplayName("marks COMPLETED when no row failed")
        void completedWithoutErrors() {
            when(transactionRepository.findExistingExternalRefs(anyLong(), anyCollection()))
                    .thenReturn(Set.of());

            processor.applyParsedRows(job, new CsvParseResult(
                    List.of(expenseRow(2, "100.00", null)), List.of()));

            assertThat(job.getStatus()).isEqualTo(ImportStatus.COMPLETED);
            assertThat(job.getStatus().isTerminal()).isTrue();
            assertThat(job.getCompletedAt()).isNotNull();
        }

        @Test
        @DisplayName("drops the caller's cached reports after an asynchronous run")
        void invalidatesCacheAfterProcessing() {
            when(jobRepository.findById(JOB_ID)).thenReturn(java.util.Optional.of(job));
            when(payloadRepository.findById(JOB_ID)).thenReturn(java.util.Optional.of(
                    new com.finsight.imports.domain.ImportPayload(JOB_ID,
                            "date,type,amount,description\n2026-03-01,EXPENSE,100.00,Test\n")));
            when(transactionRepository.findExistingExternalRefs(anyLong(), anyCollection()))
                    .thenReturn(Set.of());

            processor.processJob(JOB_ID, USER_ID, 10_000);

            verify(cacheEvictor).evictUser(anyLong(), any(String[].class));
        }

        @Test
        @DisplayName("ignores an event for a job that no longer exists")
        void missingJobIsNotAnError() {
            // The job was deleted after the event was published. Throwing would
            // send the message round the retry loop and then to the DLT for
            // nothing — there is no work to do and no retry that would help.
            when(jobRepository.findById(999L)).thenReturn(java.util.Optional.empty());

            processor.processJob(999L, USER_ID, 10_000);

            verify(transactionRepository, never()).saveAll(anyCollection());
        }

        @Test
        @DisplayName("refuses an event whose userId disagrees with the stored job")
        void rejectsOwnershipMismatch() {
            // An event is a message from another process, not a trusted assertion
            // about ownership. Importing one user's rows into another's account
            // would be the worst possible outcome here.
            when(jobRepository.findById(JOB_ID)).thenReturn(java.util.Optional.of(job));

            org.assertj.core.api.Assertions
                    .assertThatThrownBy(() -> processor.processJob(JOB_ID, 999L, 10_000))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("does not match");
        }

        @Test
        @DisplayName("skips a job that already reached a terminal state")
        void skipsTerminalJob() {
            job.markCompleted(10, 0, 0);
            when(jobRepository.findById(JOB_ID)).thenReturn(java.util.Optional.of(job));

            processor.processJob(JOB_ID, USER_ID, 10_000);

            verify(transactionRepository, never()).saveAll(anyCollection());
            assertThat(job.getImportedRows()).isEqualTo(10);   // untouched
        }

        @Test
        @DisplayName("fails the job when the stored upload has already been discarded")
        void missingPayloadFailsJob() {
            when(jobRepository.findById(JOB_ID)).thenReturn(java.util.Optional.of(job));
            when(payloadRepository.findById(JOB_ID)).thenReturn(java.util.Optional.empty());

            processor.processJob(JOB_ID, USER_ID, 10_000);

            assertThat(job.getStatus()).isEqualTo(ImportStatus.FAILED);
            assertThat(job.getErrorMessage()).contains("no longer available");
        }

        @Test
        @DisplayName("fails the job rather than retrying forever when the file cannot be parsed")
        void unparseablePayloadFailsJob() {
            // A retry would produce the identical failure, so looping is pure waste.
            when(jobRepository.findById(JOB_ID)).thenReturn(java.util.Optional.of(job));
            when(payloadRepository.findById(JOB_ID)).thenReturn(java.util.Optional.of(
                    new com.finsight.imports.domain.ImportPayload(JOB_ID, "   ")));

            processor.processJob(JOB_ID, USER_ID, 10_000);

            assertThat(job.getStatus()).isEqualTo(ImportStatus.FAILED);
            assertThat(job.getErrorMessage()).contains("could not be parsed");
        }
    }

    // ----------------------------------------------------------------- helpers --

    private static Category categoryWithId(Long id, String name, CategoryType type) {
        Category category = Category.forUser(USER_ID, name, type, null);
        setField(category, "id", id);
        return category;
    }

    private static void setId(Object entity, Long id) {
        setField(entity, "id", id);
    }

    /** Sets a generated id that only the persistence provider would normally assign. */
    private static void setField(Object target, String fieldName, Object value) {
        try {
            var field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not set " + fieldName, e);
        }
    }
}
