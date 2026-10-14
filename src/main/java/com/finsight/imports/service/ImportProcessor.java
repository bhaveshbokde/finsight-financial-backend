package com.finsight.imports.service;

import com.finsight.category.domain.Category;
import com.finsight.category.repository.CategoryRepository;
import com.finsight.common.cache.UserCacheEvictor;
import com.finsight.common.util.Hashes;
import com.finsight.imports.csv.CsvParseResult;
import com.finsight.imports.csv.CsvRowError;
import com.finsight.imports.csv.CsvTransactionParser;
import com.finsight.imports.csv.ParsedTransactionRow;
import com.finsight.imports.domain.ImportJob;
import com.finsight.imports.domain.ImportPayload;
import com.finsight.imports.domain.ImportRowError;
import com.finsight.imports.repository.ImportJobRepository;
import com.finsight.imports.repository.ImportPayloadRepository;
import com.finsight.imports.repository.ImportRowErrorRepository;
import com.finsight.report.service.ReportService;
import com.finsight.transaction.domain.Transaction;
import com.finsight.transaction.domain.TransactionType;
import com.finsight.transaction.repository.TransactionRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns parsed CSV rows into transactions.
 *
 * <h2>Idempotency</h2>
 * Every row gets {@code externalRef = sha256(fileChecksum + ':' + rowNumber)}.
 * Combined with the partial unique index {@code uq_transactions_user_external_ref},
 * re-processing the same job — whether from a redelivered Kafka message or a
 * re-upload of the same file — can insert nothing new. Rows whose ref already
 * exists are counted as duplicates, not inserted again and not reported as errors.
 *
 * <p>Note this is <em>content-and-position</em> based. A file with the same rows in
 * a different order produces different refs and would import again. That is a
 * deliberate limit, stated here rather than glossed over: the alternative — hashing
 * only content — would reject the legitimate case of two identical transactions in
 * one file.
 *
 * <h2>Partial failure</h2>
 * Valid rows are imported even when others fail. The alternative, rejecting the
 * whole file over one bad date, means a 2 000-row statement with a single typo
 * imports nothing. Rejected rows are recorded individually so the user can fix and
 * re-upload just those.
 */
@Service
public class ImportProcessor {

    private static final Logger log = LoggerFactory.getLogger(ImportProcessor.class);

    /** Matches `spring.jpa.properties.hibernate.jdbc.batch_size`. */
    private static final int FLUSH_BATCH = 50;

    private final ImportJobRepository jobRepository;
    private final ImportPayloadRepository payloadRepository;
    private final ImportRowErrorRepository rowErrorRepository;
    private final TransactionRepository transactionRepository;
    private final CategoryRepository categoryRepository;
    private final CsvTransactionParser parser;
    private final UserCacheEvictor cacheEvictor;
    private final Counter importedCounter;
    private final Counter duplicateCounter;
    private final Counter failedRowCounter;

    public ImportProcessor(ImportJobRepository jobRepository,
                           ImportPayloadRepository payloadRepository,
                           ImportRowErrorRepository rowErrorRepository,
                           TransactionRepository transactionRepository,
                           CategoryRepository categoryRepository,
                           CsvTransactionParser parser,
                           UserCacheEvictor cacheEvictor,
                           MeterRegistry meterRegistry) {
        this.jobRepository = jobRepository;
        this.payloadRepository = payloadRepository;
        this.rowErrorRepository = rowErrorRepository;
        this.transactionRepository = transactionRepository;
        this.categoryRepository = categoryRepository;
        this.parser = parser;
        this.cacheEvictor = cacheEvictor;

        this.importedCounter = Counter.builder("finsight.import.rows")
                .tag("outcome", "imported").register(meterRegistry);
        this.duplicateCounter = Counter.builder("finsight.import.rows")
                .tag("outcome", "duplicate").register(meterRegistry);
        this.failedRowCounter = Counter.builder("finsight.import.rows")
                .tag("outcome", "failed").register(meterRegistry);
    }

    /**
     * Loads, parses and applies a job identified only by id — the entry point used
     * by the Kafka consumer.
     *
     * <p>Runs in its own transaction. If anything here throws, the whole job's work
     * rolls back and the listener's error handler decides whether to retry or
     * dead-letter; a partially applied job is never left behind.
     *
     * @param expectedUserId the owner claimed by the event, re-checked against the
     *                       stored job. An event is a message from another process,
     *                       not a trusted assertion about ownership.
     */
    @Transactional
    public void processJob(Long jobId, Long expectedUserId, int maxRows) {
        ImportJob job = jobRepository.findById(jobId).orElse(null);
        if (job == null) {
            // The job was deleted after the event was published. Nothing to do, and
            // retrying will never help — treat it as successfully handled.
            log.warn("Import job id={} no longer exists; ignoring event", jobId);
            return;
        }
        if (!job.getUserId().equals(expectedUserId)) {
            // Would mean a corrupted or forged event. Refuse rather than import
            // one user's rows into another user's account.
            throw new IllegalArgumentException(
                    "Event userId=%d does not match import job id=%d".formatted(expectedUserId, jobId));
        }
        if (job.getStatus().isTerminal()) {
            log.info("Import job id={} is already {}; skipping redelivered event",
                    jobId, job.getStatus());
            return;
        }

        ImportPayload payload = payloadRepository.findById(jobId).orElse(null);
        if (payload == null) {
            job.markFailed("The stored file content is no longer available");
            log.error("Import job id={} has no payload; marking FAILED", jobId);
            return;
        }

        job.markProcessing();

        CsvParseResult parsed;
        try {
            parsed = parser.parse(payload.getContent(), maxRows);
        } catch (RuntimeException e) {
            // The file could not be parsed at all. This is terminal: a retry will
            // produce the identical failure, so fail the job rather than looping.
            job.markFailed("The file could not be parsed: " + e.getMessage());
            log.error("Import job id={} failed to parse: {}", jobId, e.toString());
            return;
        }

        applyParsedRows(job, parsed);
        cacheEvictor.evictUser(job.getUserId(), ReportService.REPORT_CACHES);
    }

    /**
     * Inserts the valid rows, records the invalid ones and finalises the job.
     *
     * <p>Called both from {@link #processJob} (asynchronous path) and directly from
     * {@code ImportService.upload} (small files, synchronous path), so both paths
     * produce byte-identical results and counters.
     *
     * <p>Participates in the caller's transaction rather than starting its own:
     * for the synchronous path, the job row, the transactions and the error rows
     * must all commit together with the upload.
     */
    @Transactional
    public void applyParsedRows(ImportJob job, CsvParseResult parsed) {
        Long userId = job.getUserId();

        // Re-processing must not append a second copy of every error.
        rowErrorRepository.deleteByImportJobId(job.getId());

        job.setTotalRows(parsed.totalRows());

        List<ImportRowError> errors = new ArrayList<>(parsed.errors().size());
        for (CsvRowError error : parsed.errors()) {
            errors.add(new ImportRowError(job.getId(), error.rowNumber(), error.rawRow(),
                    error.code(), error.message()));
        }

        // One query for every category the user can use, instead of one per row.
        Map<String, Category> categoriesByName = loadCategoryIndex(userId);

        // One query to discover which idempotency keys already exist, instead of
        // 5 000 existence checks for a 5 000-row file.
        List<String> allRefs = parsed.rows().stream()
                .map(row -> externalRef(job.getFileChecksum(), row.rowNumber()))
                .toList();
        Set<String> existingRefs = allRefs.isEmpty()
                ? Set.of()
                : transactionRepository.findExistingExternalRefs(userId, allRefs);

        int imported = 0;
        int duplicates = 0;
        List<Transaction> pending = new ArrayList<>(FLUSH_BATCH);

        for (ParsedTransactionRow row : parsed.rows()) {
            String externalRef = externalRef(job.getFileChecksum(), row.rowNumber());

            if (existingRefs.contains(externalRef)) {
                duplicates++;
                continue;
            }

            Category category = resolveCategory(categoriesByName, row, errors, job.getId());
            if (category == null && row.categoryName() != null) {
                // resolveCategory already recorded a row error; skip this row.
                continue;
            }

            pending.add(Transaction.imported(
                    userId,
                    row.type(),
                    row.amount(),
                    row.txnDate(),
                    row.description(),
                    row.merchant(),
                    category,
                    job.getId(),
                    externalRef));
            imported++;

            if (pending.size() >= FLUSH_BATCH) {
                transactionRepository.saveAll(pending);
                pending.clear();
            }
        }
        if (!pending.isEmpty()) {
            transactionRepository.saveAll(pending);
        }

        if (!errors.isEmpty()) {
            rowErrorRepository.saveAll(errors);
        }

        job.markCompleted(imported, duplicates, errors.size());

        importedCounter.increment(imported);
        duplicateCounter.increment(duplicates);
        failedRowCounter.increment(errors.size());

        // The parsed transactions are the durable result; the raw upload is no
        // longer needed and holding the user's statement indefinitely is a
        // data-retention liability.
        payloadRepository.deleteByImportJobId(job.getId());

        log.info("Import job id={} finished: status={} imported={} duplicates={} failed={}",
                job.getId(), job.getStatus(), imported, duplicates, errors.size());
    }

    // ----------------------------------------------------------------- helpers --

    /**
     * The per-row idempotency key.
     *
     * <p>Depends only on the file's content hash and the row's position, so it is
     * stable across retries and across re-uploads of the identical file.
     */
    static String externalRef(String fileChecksum, int rowNumber) {
        // Truncated to 64 hex chars to fit external_ref VARCHAR(100) comfortably;
        // 256 bits of a SHA-256 is far beyond any realistic collision risk here.
        return Hashes.sha256Hex(fileChecksum + ":" + rowNumber);
    }

    /** Case-insensitive name → category, covering both the user's own and system categories. */
    private Map<String, Category> loadCategoryIndex(Long userId) {
        Map<String, Category> index = new HashMap<>();
        for (Category category : categoryRepository.findAllUsableBy(userId)) {
            // A user category with the same name as a system one wins, because the
            // user created it deliberately. findAllUsableBy returns system entries
            // first, so a later user entry overwrites.
            index.put(category.getName().toLowerCase(Locale.ROOT), category);
        }
        return index;
    }

    /**
     * Resolves a row's category name.
     *
     * @return the matching category, or null when the row named none. When the row
     *         named a category that does not exist or whose type disagrees, a row
     *         error is recorded and null is returned — the caller then skips the row.
     */
    private Category resolveCategory(Map<String, Category> index, ParsedTransactionRow row,
                                     List<ImportRowError> errors, Long jobId) {
        if (row.categoryName() == null) {
            return null;   // uncategorised is legitimate
        }

        Category category = index.get(row.categoryName().toLowerCase(Locale.ROOT));
        if (category == null) {
            errors.add(new ImportRowError(jobId, row.rowNumber(), row.description(),
                    CsvRowError.UNKNOWN_CATEGORY,
                    "No category named '%s' exists for this account".formatted(row.categoryName())));
            return null;
        }

        boolean typesAgree = (row.type() == TransactionType.INCOME
                && category.getType() == com.finsight.category.domain.CategoryType.INCOME)
                || (row.type() == TransactionType.EXPENSE
                && category.getType() == com.finsight.category.domain.CategoryType.EXPENSE);

        if (!typesAgree) {
            errors.add(new ImportRowError(jobId, row.rowNumber(), row.description(),
                    CsvRowError.CATEGORY_TYPE_MISMATCH,
                    "Category '%s' is %s but the row is %s"
                            .formatted(category.getName(), category.getType(), row.type())));
            return null;
        }
        return category;
    }
}
