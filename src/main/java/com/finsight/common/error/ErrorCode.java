package com.finsight.common.error;

import org.springframework.http.HttpStatus;

/**
 * Stable, machine-readable error identifiers returned in every error body.
 *
 * <p>Clients branch on {@code code}; the human-readable {@code message} may change
 * freely without breaking them. Each constant also carries the HTTP status it maps
 * to, which keeps status selection out of the exception handler's if-else chain.
 */
public enum ErrorCode {

    // ---- 400 ----
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "One or more fields are invalid"),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "Request body could not be parsed"),
    INVALID_PARAMETER(HttpStatus.BAD_REQUEST, "A request parameter is invalid"),
    INVALID_DATE_RANGE(HttpStatus.BAD_REQUEST, "The supplied date range is invalid"),
    INVALID_CSV(HttpStatus.BAD_REQUEST, "The uploaded file is not a readable CSV"),

    // ---- 401 ----
    AUTHENTICATION_REQUIRED(HttpStatus.UNAUTHORIZED, "Authentication is required"),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Email or password is incorrect"),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "The access token has expired"),
    TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "The access token is invalid"),
    ACCOUNT_DISABLED(HttpStatus.UNAUTHORIZED, "This account is disabled"),

    // ---- 403 ----
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "You are not allowed to access this resource"),

    // ---- 404 ----
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "The requested resource does not exist"),

    // ---- 405 / 415 ----
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "HTTP method not supported for this endpoint"),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Content type is not supported"),

    // ---- 409 ----
    EMAIL_ALREADY_REGISTERED(HttpStatus.CONFLICT, "An account with this email already exists"),
    DUPLICATE_RESOURCE(HttpStatus.CONFLICT, "A resource with these identifying values already exists"),
    CONCURRENT_MODIFICATION(HttpStatus.CONFLICT, "The resource was modified by another request; retry"),
    IMPORT_NOT_RETRYABLE(HttpStatus.CONFLICT, "This import is not in a state that can be retried"),

    // ---- 413 ----
    FILE_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "The uploaded file exceeds the configured limit"),

    // ---- 422 ----
    BUSINESS_RULE_VIOLATION(HttpStatus.UNPROCESSABLE_ENTITY, "The request violates a business rule"),

    // ---- 500 / 503 ----
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred"),
    DEPENDENCY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "A required downstream service is unavailable");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
