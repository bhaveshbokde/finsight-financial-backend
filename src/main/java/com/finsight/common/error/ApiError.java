package com.finsight.common.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/**
 * The single error body shape returned by every endpoint in this application.
 *
 * <p>Example:
 * <pre>{@code
 * {
 *   "timestamp": "2026-10-14T09:31:22.481Z",
 *   "status": 400,
 *   "code": "VALIDATION_FAILED",
 *   "message": "One or more fields are invalid",
 *   "path": "/api/v1/transactions",
 *   "traceId": "0f6b1c2d9a3e4f58",
 *   "fieldErrors": [
 *     { "field": "amount", "rejectedValue": "-10", "message": "must be greater than 0" }
 *   ]
 * }
 * }</pre>
 *
 * <p>{@code traceId} is echoed so a user-reported failure can be correlated with a
 * server log line without exposing a stack trace.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "ApiError", description = "Standard error payload")
public record ApiError(

        @Schema(example = "2026-10-14T09:31:22.481Z")
        Instant timestamp,

        @Schema(example = "400")
        int status,

        @Schema(example = "VALIDATION_FAILED")
        String code,

        @Schema(example = "One or more fields are invalid")
        String message,

        @Schema(example = "/api/v1/transactions")
        String path,

        @Schema(description = "Correlation id; quote this when reporting a problem")
        String traceId,

        @Schema(description = "Present only for validation failures")
        List<FieldViolation> fieldErrors
) {

    /** One rejected field. {@code rejectedValue} is rendered as a string and never includes passwords. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(name = "FieldViolation")
    public record FieldViolation(String field, String rejectedValue, String message) {
    }

    public static ApiError of(ErrorCode code, String message, String path, String traceId) {
        return new ApiError(Instant.now(), code.status().value(), code.name(), message, path, traceId, null);
    }

    public static ApiError of(ErrorCode code, String message, String path, String traceId,
                              List<FieldViolation> fieldErrors) {
        return new ApiError(Instant.now(), code.status().value(), code.name(), message, path, traceId,
                fieldErrors == null || fieldErrors.isEmpty() ? null : fieldErrors);
    }
}
