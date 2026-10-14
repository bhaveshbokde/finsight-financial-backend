package com.finsight.imports.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The raw CSV text of an upload, keyed by its job id.
 *
 * <p>Kept in its own table so {@code import_jobs} — which is polled for status and
 * updated several times per job — stays narrow and cheap to update. Postgres would
 * TOAST a large text column out of line anyway, but separating it also keeps the
 * payload off every {@code SELECT *} and out of the status endpoint's result set.
 *
 * <p>Storing the bytes in the database is a deliberate simplification: it makes the
 * upload and the outbox event a single atomic write, with no second system to keep
 * consistent. A production system handling large files would put the object in S3
 * and store a key here. The 5 MiB upload cap is what makes this acceptable; see
 * docs/12-DESIGN-DECISIONS-AND-TRADEOFFS.md.
 *
 * <p>Uses a shared primary key rather than its own identity column: there is exactly
 * one payload per job, so the job's id <em>is</em> the natural key.
 */
@Entity
@Table(name = "import_payloads")
public class ImportPayload {

    @Id
    @Column(name = "import_job_id")
    private Long importJobId;

    @Column(name = "content", nullable = false, columnDefinition = "text")
    private String content;

    protected ImportPayload() {
    }

    public ImportPayload(Long importJobId, String content) {
        this.importJobId = importJobId;
        this.content = content;
    }

    public Long getImportJobId() {
        return importJobId;
    }

    public String getContent() {
        return content;
    }

    /** Never prints the content — it is the user's financial data. */
    @Override
    public String toString() {
        return "ImportPayload{importJobId=%d, length=%d}"
                .formatted(importJobId, content == null ? 0 : content.length());
    }
}
