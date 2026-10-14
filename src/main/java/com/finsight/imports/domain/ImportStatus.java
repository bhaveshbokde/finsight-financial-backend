package com.finsight.imports.domain;

import java.util.Set;

/**
 * Lifecycle of a CSV import.
 *
 * <pre>
 *   ACCEPTED ──▶ PROCESSING ──▶ COMPLETED
 *                     │
 *                     ├────────▶ COMPLETED_WITH_ERRORS
 *                     └────────▶ FAILED
 * </pre>
 *
 * <p>The distinction the API makes, and that the requirements call for:
 * <ul>
 *   <li>{@code ACCEPTED} — the upload is stored and an event has been recorded.
 *       Nothing has been inserted yet. This is what a 202 response means.</li>
 *   <li>{@code PROCESSING} — a consumer has claimed the job and is inserting rows.</li>
 *   <li>{@code COMPLETED} — every data row was either imported or recognised as a
 *       duplicate. No row failed.</li>
 *   <li>{@code COMPLETED_WITH_ERRORS} — the file was processed to the end and some
 *       rows were rejected. The good rows <em>are</em> imported; the rejected ones
 *       are listed individually. This is the partial-failure outcome, and it is a
 *       deliberate design choice over all-or-nothing — see docs/08.</li>
 *   <li>{@code FAILED} — processing could not complete at all (unreadable file,
 *       missing header, or an unexpected error). Nothing from this job is imported.</li>
 * </ul>
 */
public enum ImportStatus {

    ACCEPTED,
    PROCESSING,
    COMPLETED,
    COMPLETED_WITH_ERRORS,
    FAILED;

    private static final Set<ImportStatus> TERMINAL =
            Set.of(COMPLETED, COMPLETED_WITH_ERRORS, FAILED);

    /** True when no further state change is expected. */
    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    /**
     * True when this job may be handed to a processor.
     *
     * <p>{@code PROCESSING} is included so that a job orphaned by a consumer crash
     * can be picked up again by a redelivered message. Re-processing is safe
     * because every row carries an idempotency key — see
     * {@link com.finsight.transaction.domain.Transaction#getExternalRef()}.
     */
    public boolean isProcessable() {
        return this == ACCEPTED || this == PROCESSING;
    }
}
