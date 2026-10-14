package com.finsight.common.error;

/**
 * Base class for every error the application raises deliberately.
 *
 * <p>Carrying an {@link ErrorCode} means {@link GlobalExceptionHandler} can derive
 * the HTTP status and response code from the exception itself instead of
 * maintaining a parallel mapping table.
 *
 * <p>Extends {@link RuntimeException} on purpose: Spring's declarative transaction
 * management rolls back on unchecked exceptions by default, so a failing business
 * rule aborts the surrounding transaction without any {@code rollbackFor} tuning.
 */
public class ApiException extends RuntimeException {

    private final transient ErrorCode errorCode;

    public ApiException(ErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage());
    }

    public ApiException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ApiException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
