package com.finsight.imports.service;

import com.finsight.common.cache.UserCacheEvictor;
import com.finsight.common.dto.PageResponse;
import com.finsight.common.error.ApiException;
import com.finsight.common.error.ErrorCode;
import com.finsight.common.error.ResourceNotFoundException;
import com.finsight.common.util.Hashes;
import com.finsight.config.CsvImportProperties;
import com.finsight.config.KafkaSettings;
import com.finsight.imports.csv.CsvParseResult;
import com.finsight.imports.csv.CsvTransactionParser;
import com.finsight.imports.domain.ImportJob;
import com.finsight.imports.domain.ImportPayload;
import com.finsight.imports.domain.ImportRowError;
import com.finsight.imports.domain.ImportStatus;
import com.finsight.imports.dto.ImportJobResponse;
import com.finsight.imports.dto.ImportRowErrorResponse;
import com.finsight.imports.repository.ImportJobRepository;
import com.finsight.imports.repository.ImportPayloadRepository;
import com.finsight.imports.repository.ImportRowErrorRepository;
import com.finsight.outbox.service.OutboxRecorder;
import com.finsight.report.service.ReportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Accepts CSV uploads and reports on their progress.
 *
 * <p>Row insertion itself lives in {@link ImportProcessor}; this class owns the
 * request-side concerns — validation, storage, the synchronous/asynchronous
 * decision, and the status API.
 *
 * <h2>Synchronous or asynchronous?</h2>
 * Files at or below {@code finsight.csv-import.async-threshold-rows} are processed
 * inline and the caller gets a finished result in one round trip. Larger files are
 * handed to Kafka and the caller gets 202 Accepted with a job to poll. The
 * threshold exists because holding an HTTP connection open while inserting
 * thousands of rows ties up a request thread, risks a client or proxy timeout, and
 * gives the user no way to find out what happened if the connection drops.
 *
 * <h2>The upload transaction</h2>
 * Creating the job, storing the payload and recording the outbox event all happen
 * in one transaction. Either the upload is fully durable and an event exists to
 * drive it, or nothing happened at all — there is no state where a job waits for
 * an event that was never recorded.
 */
@Service
public class ImportService {

    private static final Logger log = LoggerFactory.getLogger(ImportService.class);

    private final ImportJobRepository jobRepository;
    private final ImportPayloadRepository payloadRepository;
    private final ImportRowErrorRepository rowErrorRepository;
    private final CsvTransactionParser parser;
    private final ImportProcessor processor;
    private final OutboxRecorder outboxRecorder;
    private final CsvImportProperties properties;
    private final KafkaSettings kafkaSettings;
    private final UserCacheEvictor cacheEvictor;

    public ImportService(ImportJobRepository jobRepository,
                         ImportPayloadRepository payloadRepository,
                         ImportRowErrorRepository rowErrorRepository,
                         CsvTransactionParser parser,
                         ImportProcessor processor,
                         OutboxRecorder outboxRecorder,
                         CsvImportProperties properties,
                         KafkaSettings kafkaSettings,
                         UserCacheEvictor cacheEvictor) {
        this.jobRepository = jobRepository;
        this.payloadRepository = payloadRepository;
        this.rowErrorRepository = rowErrorRepository;
        this.parser = parser;
        this.processor = processor;
        this.outboxRecorder = outboxRecorder;
        this.properties = properties;
        this.kafkaSettings = kafkaSettings;
        this.cacheEvictor = cacheEvictor;
    }

    /**
     * Validates and accepts an upload.
     *
     * <p>Returns a job whose status is either a terminal one (small file, processed
     * inline) or {@code ACCEPTED} (large file, queued). The controller chooses 200
     * or 202 from that status.
     */
    @Transactional
    public ImportJobResponse upload(Long userId, MultipartFile file) {
        validateUpload(file);

        byte[] bytes = readBytes(file);
        String checksum = Hashes.sha256Hex(bytes);
        String content = new String(bytes, StandardCharsets.UTF_8);

        // Parse before persisting anything: a structurally broken file should be a
        // clean 400, not a FAILED job row the user has to go and look at.
        CsvParseResult parsed = parser.parse(content, properties.maxRows());

        boolean previouslyUploaded = !jobRepository
                .findByUserIdAndFileChecksumOrderByCreatedAtDesc(userId, checksum).isEmpty();

        ImportJob job = jobRepository.save(ImportJob.accepted(
                userId,
                sanitiseFilename(file.getOriginalFilename()),
                checksum,
                bytes.length,
                parsed.totalRows()));

        payloadRepository.save(new ImportPayload(job.getId(), content));

        if (parsed.totalRows() <= properties.asyncThresholdRows()) {
            // Small file: finish the work inside this same transaction so the
            // caller gets a complete answer and there is nothing left to poll.
            processor.applyParsedRows(job, parsed);
            cacheEvictor.evictUser(userId, ReportService.REPORT_CACHES);

            log.info("Processed import id={} inline: {} rows for user id={}",
                    job.getId(), parsed.totalRows(), userId);
            return ImportJobResponse.from(job, previouslyUploaded);
        }

        // Large file: record the event in THIS transaction. The poller publishes it
        // afterwards; see OutboxPublisher for the at-least-once guarantee.
        outboxRecorder.recordImportRequested(
                job.getId(), userId, checksum, parsed.totalRows(),
                kafkaSettings.topics().importRequested());

        log.info("Accepted import id={} for asynchronous processing: {} rows for user id={}",
                job.getId(), parsed.totalRows(), userId);
        return ImportJobResponse.from(job, previouslyUploaded);
    }

    // ------------------------------------------------------------------ status --

    @Transactional(readOnly = true)
    public ImportJobResponse getStatus(Long jobId, Long userId) {
        ImportJob job = jobRepository.findByIdAndUserId(jobId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Import job", jobId));
        return ImportJobResponse.from(job);
    }

    @Transactional(readOnly = true)
    public PageResponse<ImportJobResponse> listJobs(Long userId, Pageable pageable) {
        Page<ImportJob> page = jobRepository.findByUserIdOrderByCreatedAtDescIdDesc(userId, pageable);
        return PageResponse.from(page, ImportJobResponse::from);
    }

    /**
     * Rejected rows for one job.
     *
     * <p>The job is looked up first, scoped by {@code userId}, so the error list
     * cannot be read for someone else's import by guessing an id.
     */
    @Transactional(readOnly = true)
    public PageResponse<ImportRowErrorResponse> listErrors(Long jobId, Long userId, Pageable pageable) {
        ImportJob job = jobRepository.findByIdAndUserId(jobId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Import job", jobId));

        Page<ImportRowError> page =
                rowErrorRepository.findByImportJobIdOrderByRowNumberAsc(job.getId(), pageable);
        return PageResponse.from(page, ImportRowErrorResponse::from);
    }

    // -------------------------------------------------------------- validation --

    /**
     * Rejects an upload before any of it is read into memory or stored.
     *
     * <p>The size check duplicates the servlet container's multipart limit on
     * purpose: the container's limit is configuration that someone may raise, and
     * a second check in the code that actually allocates the buffer is cheap
     * defence in depth.
     */
    private void validateUpload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_CSV, "No file was uploaded, or the file is empty");
        }
        if (file.getSize() > properties.maxFileSizeBytes()) {
            throw new ApiException(ErrorCode.FILE_TOO_LARGE,
                    "File is %d bytes; the maximum is %d".formatted(
                            file.getSize(), properties.maxFileSizeBytes()));
        }

        String contentType = file.getContentType();
        if (contentType != null) {
            String base = contentType.split(";")[0].trim().toLowerCase(java.util.Locale.ROOT);
            if (!properties.allowedContentTypes().contains(base)) {
                throw new ApiException(ErrorCode.UNSUPPORTED_MEDIA_TYPE,
                        "Content type '%s' is not accepted; upload a CSV file".formatted(base));
            }
        }

        String name = file.getOriginalFilename();
        if (name != null && !name.toLowerCase(java.util.Locale.ROOT).endsWith(".csv")) {
            throw new ApiException(ErrorCode.INVALID_CSV, "The file must have a .csv extension");
        }
    }

    private byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new ApiException(ErrorCode.INVALID_CSV, "The uploaded file could not be read", e);
        }
    }

    /**
     * Keeps only the base name and strips anything path-like.
     *
     * <p>A client controls {@code Content-Disposition: filename}, and values such
     * as {@code ../../etc/passwd} or a 4 KB string are entirely possible. Nothing
     * here ever opens a file by this name, but storing an unsanitised, attacker-
     * controlled string that is later rendered in a UI is a trap worth closing at
     * the boundary.
     */
    private String sanitiseFilename(String original) {
        if (original == null || original.isBlank()) {
            return "upload.csv";
        }
        String base = original.replace('\\', '/');
        int slash = base.lastIndexOf('/');
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        base = base.replaceAll("[\\p{Cntrl}]", "").trim();
        if (base.isEmpty()) {
            return "upload.csv";
        }
        return base.length() > 255 ? base.substring(0, 255) : base;
    }

    /** Exposed for tests and for the troubleshooting runbook. */
    @Transactional(readOnly = true)
    public List<ImportJob> findByChecksum(Long userId, String checksum) {
        return jobRepository.findByUserIdAndFileChecksumOrderByCreatedAtDesc(userId, checksum);
    }

    /** True when the given status can still change. Used by the controller to pick 200 vs 202. */
    public boolean isPending(ImportStatus status) {
        return !status.isTerminal();
    }
}
