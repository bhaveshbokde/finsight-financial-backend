package com.finsight.messaging.event;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;
import java.util.UUID;

/**
 * Published when a CSV upload is accepted for asynchronous processing.
 *
 * <p><strong>Topic:</strong> {@code finsight.imports.requested.v1}<br>
 * <strong>Key:</strong> the import job id, so all events for one job share a
 * partition and keep their relative order<br>
 * <strong>Value:</strong> this record, JSON-encoded
 *
 * <h2>Schema</h2>
 * <pre>{@code
 * {
 *   "eventId":     "9d6b6a1e-5a4c-4f0e-9a7c-2f1b3d4e5f60",
 *   "eventType":   "ImportRequested",
 *   "importJobId": 42,
 *   "userId":      7,
 *   "fileChecksum":"3b1f...e9",
 *   "totalRows":   1200,
 *   "occurredAt":  "2026-10-14T06:12:44.182Z"
 * }
 * }</pre>
 *
 * <h2>Why the version is in the topic name</h2>
 * {@code ...v1} rather than a version field inside the payload. A breaking change
 * gets a new topic, so old and new consumers can run side by side during a
 * rollout instead of one of them failing to deserialise. Backwards-compatible
 * changes — adding an optional field — stay on v1, which is safe because the
 * consumer's deserialiser ignores unknown properties.
 *
 * <h2>What is deliberately absent</h2>
 * The CSV content. It lives in {@code import_payloads} and the consumer reads it by
 * id. Kafka's default 1 MiB message limit makes shipping files through the broker
 * fragile, and the payload would then exist in two places with no way to keep them
 * consistent.
 *
 * @param eventId      stable identity; the consumer stores it in {@code processed_events}
 *                     so a redelivery is recognised and skipped
 * @param eventType    discriminator, allowing several event types on one topic later
 * @param importJobId  the job to process
 * @param userId       owner; re-checked against the job row, never trusted on its own
 * @param fileChecksum SHA-256 of the upload, used to derive per-row idempotency keys
 * @param totalRows    data-row count, for logging and metrics
 * @param occurredAt   when the upload was accepted (UTC)
 */
public record ImportRequestedEvent(
        UUID eventId,
        String eventType,
        Long importJobId,
        Long userId,
        String fileChecksum,
        int totalRows,
        Instant occurredAt
) {

    public static final String TYPE = "ImportRequested";
    public static final String AGGREGATE_TYPE = "ImportJob";

    public static ImportRequestedEvent of(UUID eventId, Long importJobId, Long userId,
                                          String fileChecksum, int totalRows) {
        return new ImportRequestedEvent(eventId, TYPE, importJobId, userId,
                fileChecksum, totalRows, Instant.now());
    }

    /**
     * Rejects a structurally invalid message.
     *
     * <p>A consumer must never assume a message is well-formed just because it
     * deserialised: it may come from an older producer, a hand-crafted test, or a
     * replayed dead-letter record. An invalid message is a poison pill and is sent
     * straight to the DLT rather than retried.
     *
     * <p>{@code @JsonIgnore} because Jackson treats an {@code isX()} method as a
     * bean property and would otherwise publish a {@code "valid": true} field on
     * the wire — a derived value that is not part of the event schema, that a
     * consumer might start trusting, and that would be written by the producer
     * rather than checked by the consumer.
     */
    @JsonIgnore
    public boolean isValid() {
        return eventId != null
                && importJobId != null && importJobId > 0
                && userId != null && userId > 0
                && fileChecksum != null && fileChecksum.matches("^[0-9a-f]{64}$")
                && TYPE.equals(eventType);
    }
}
