package com.finsight.common.error;

import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;
import java.util.UUID;

/**
 * Translates every exception that escapes a controller into the single
 * {@link ApiError} shape.
 *
 * <p>{@code @RestControllerAdvice} registers these handlers across all
 * controllers; Spring picks the handler whose parameter type most closely matches
 * the thrown exception, so the specific handlers below always win over
 * {@link #handleUnexpected}.
 *
 * <h2>Logging policy</h2>
 * <ul>
 *   <li>4xx caused by the client are logged at WARN <em>without</em> a stack trace —
 *       they are expected traffic, and 10 000 bad logins should not produce
 *       10 000 stack traces.</li>
 *   <li>5xx are logged at ERROR <em>with</em> the stack trace, keyed by the same
 *       {@code traceId} that is returned to the caller.</li>
 *   <li>The stack trace is never put in the response body. A caller gets a
 *       {@code traceId} to quote; the detail stays server-side.</li>
 * </ul>
 *
 * <p>Note that exceptions thrown by the security filter chain (an expired JWT, a
 * missing token) never reach this class, because filters run <em>before</em> the
 * {@code DispatcherServlet}. Those are handled by
 * {@link com.finsight.auth.security.RestAuthenticationEntryPoint} and
 * {@link com.finsight.auth.security.RestAccessDeniedHandler}, which deliberately
 * emit the identical {@link ApiError} body.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // ------------------------------------------------------------- application --

    /** Every deliberately raised error: the code already knows its HTTP status. */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApiException(ApiException ex, HttpServletRequest request) {
        ErrorCode code = ex.errorCode();
        if (code.status().is5xxServerError()) {
            return respond(code, ex.getMessage(), request, ex);
        }
        return respond(code, ex.getMessage(), request, null);
    }

    // -------------------------------------------------------------- validation --

    /** {@code @Valid} failure on a {@code @RequestBody}. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleBodyValidation(MethodArgumentNotValidException ex,
                                                         HttpServletRequest request) {
        List<ApiError.FieldViolation> violations = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ApiError.FieldViolation(fe.getField(), redact(fe), fe.getDefaultMessage()))
                .toList();
        return respondWithFields(ErrorCode.VALIDATION_FAILED,
                ErrorCode.VALIDATION_FAILED.defaultMessage(), request, violations);
    }

    /** {@code @Validated} failure on a method parameter (query params, path variables). */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraintViolation(ConstraintViolationException ex,
                                                              HttpServletRequest request) {
        List<ApiError.FieldViolation> violations = ex.getConstraintViolations().stream()
                .map(v -> new ApiError.FieldViolation(
                        lastNode(v.getPropertyPath().toString()),
                        String.valueOf(v.getInvalidValue()),
                        v.getMessage()))
                .toList();
        return respondWithFields(ErrorCode.VALIDATION_FAILED,
                ErrorCode.VALIDATION_FAILED.defaultMessage(), request, violations);
    }

    /** Unparseable JSON, or a value that cannot be coerced into the target type. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException ex,
                                                     HttpServletRequest request) {
        // Surface *which* field failed to coerce, but never echo the raw body:
        // it may contain a password on the registration endpoint.
        String detail = "Request body could not be parsed";
        if (ex.getCause() instanceof InvalidFormatException ife && !ife.getPath().isEmpty()) {
            detail = "Invalid value for field '%s'; expected %s"
                    .formatted(ife.getPath().get(ife.getPath().size() - 1).getFieldName(),
                               ife.getTargetType().getSimpleName());
        }
        return respond(ErrorCode.MALFORMED_REQUEST, detail, request, null);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> handleMissingParam(MissingServletRequestParameterException ex,
                                                       HttpServletRequest request) {
        return respond(ErrorCode.INVALID_PARAMETER,
                "Required parameter '%s' is missing".formatted(ex.getParameterName()), request, null);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                                       HttpServletRequest request) {
        String required = ex.getRequiredType() == null ? "the expected type"
                                                       : ex.getRequiredType().getSimpleName();
        return respond(ErrorCode.INVALID_PARAMETER,
                "Parameter '%s' must be a valid %s".formatted(ex.getName(), required), request, null);
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ApiError> handleMissingPart(MissingServletRequestPartException ex,
                                                      HttpServletRequest request) {
        return respond(ErrorCode.INVALID_PARAMETER,
                "Required multipart file '%s' is missing".formatted(ex.getRequestPartName()), request, null);
    }

    // ------------------------------------------------------------------ upload --

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleTooLarge(MaxUploadSizeExceededException ex,
                                                   HttpServletRequest request) {
        return respond(ErrorCode.FILE_TOO_LARGE,
                "The uploaded file exceeds the maximum permitted size", request, null);
    }

    // ---------------------------------------------------------------- security --
    // Reached only when the exception originates inside a controller/service,
    // i.e. after the filter chain has already authenticated the caller.

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiError> handleBadCredentials(BadCredentialsException ex,
                                                         HttpServletRequest request) {
        // Deliberately identical for "unknown email" and "wrong password" so the
        // endpoint cannot be used to enumerate registered addresses.
        return respond(ErrorCode.INVALID_CREDENTIALS,
                ErrorCode.INVALID_CREDENTIALS.defaultMessage(), request, null);
    }

    @ExceptionHandler(DisabledException.class)
    public ResponseEntity<ApiError> handleDisabled(DisabledException ex, HttpServletRequest request) {
        return respond(ErrorCode.ACCOUNT_DISABLED, ErrorCode.ACCOUNT_DISABLED.defaultMessage(), request, null);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiError> handleAuthentication(AuthenticationException ex,
                                                         HttpServletRequest request) {
        return respond(ErrorCode.AUTHENTICATION_REQUIRED,
                ErrorCode.AUTHENTICATION_REQUIRED.defaultMessage(), request, null);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex,
                                                       HttpServletRequest request) {
        return respond(ErrorCode.ACCESS_DENIED, ErrorCode.ACCESS_DENIED.defaultMessage(), request, null);
    }

    // ----------------------------------------------------------------- database --

    /**
     * A unique or check constraint rejected the write.
     *
     * <p>Services check for duplicates before inserting, but that check and the
     * insert are not atomic: two concurrent requests can both pass the check. The
     * database constraint is the real guarantee and this handler turns the
     * resulting race into a clean 409 instead of a 500.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleIntegrity(DataIntegrityViolationException ex,
                                                    HttpServletRequest request) {
        String traceId = traceId();
        log.warn("Data integrity violation [traceId={}] on {} {}: {}",
                traceId, request.getMethod(), request.getRequestURI(), rootMessage(ex));
        return build(ErrorCode.DUPLICATE_RESOURCE,
                "The operation conflicts with an existing record", request, traceId, null);
    }

    /** Lost optimistic-lock race: the row changed between read and write. */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiError> handleOptimisticLock(OptimisticLockingFailureException ex,
                                                         HttpServletRequest request) {
        return respond(ErrorCode.CONCURRENT_MODIFICATION,
                ErrorCode.CONCURRENT_MODIFICATION.defaultMessage(), request, null);
    }

    // -------------------------------------------------------------- HTTP basics --

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleMethodNotAllowed(HttpRequestMethodNotSupportedException ex,
                                                           HttpServletRequest request) {
        return respond(ErrorCode.METHOD_NOT_ALLOWED,
                "%s is not supported for this endpoint".formatted(ex.getMethod()), request, null);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiError> handleMediaType(HttpMediaTypeNotSupportedException ex,
                                                    HttpServletRequest request) {
        return respond(ErrorCode.UNSUPPORTED_MEDIA_TYPE,
                "Content type '%s' is not supported".formatted(ex.getContentType()), request, null);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNoResource(NoResourceFoundException ex,
                                                     HttpServletRequest request) {
        return respond(ErrorCode.RESOURCE_NOT_FOUND, "No endpoint matches this path", request, null);
    }

    // ------------------------------------------------------------------ fallback --

    /**
     * Last resort. Anything landing here is a bug or an unavailable dependency.
     *
     * <p>The response body is deliberately generic — the exception's own message
     * can contain a JDBC URL, a file path or part of a query — while the full
     * stack trace goes to the log under the returned {@code traceId}.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
        return respond(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.defaultMessage(), request, ex);
    }

    // -------------------------------------------------------------------- helpers --

    private ResponseEntity<ApiError> respond(ErrorCode code, String message,
                                             HttpServletRequest request, Exception toLog) {
        String traceId = traceId();
        if (toLog != null) {
            log.error("Unhandled failure [traceId={}] on {} {}",
                    traceId, request.getMethod(), request.getRequestURI(), toLog);
        } else if (code.status().is4xxClientError()) {
            log.warn("{} [traceId={}] on {} {}: {}",
                    code, traceId, request.getMethod(), request.getRequestURI(), message);
        }
        return build(code, message, request, traceId, null);
    }

    private ResponseEntity<ApiError> respondWithFields(ErrorCode code, String message,
                                                       HttpServletRequest request,
                                                       List<ApiError.FieldViolation> violations) {
        String traceId = traceId();
        log.warn("{} [traceId={}] on {} {}: {} field error(s)",
                code, traceId, request.getMethod(), request.getRequestURI(), violations.size());
        return build(code, message, request, traceId, violations);
    }

    private ResponseEntity<ApiError> build(ErrorCode code, String message, HttpServletRequest request,
                                           String traceId, List<ApiError.FieldViolation> violations) {
        HttpStatus status = code.status();
        return ResponseEntity.status(status)
                .body(ApiError.of(code, message, request.getRequestURI(), traceId, violations));
    }

    /** Reuses the MDC trace id when one is present so logs and responses agree. */
    private String traceId() {
        String fromMdc = MDC.get("traceId");
        return fromMdc != null ? fromMdc : UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /** Never echo the value of a secret-bearing field back to the caller. */
    private String redact(FieldError fieldError) {
        String field = fieldError.getField().toLowerCase();
        if (field.contains("password") || field.contains("secret") || field.contains("token")) {
            return "***";
        }
        Object rejected = fieldError.getRejectedValue();
        if (rejected == null) {
            return null;
        }
        String asString = rejected.toString();
        return asString.length() > 120 ? asString.substring(0, 117) + "..." : asString;
    }

    private String lastNode(String propertyPath) {
        int idx = propertyPath.lastIndexOf('.');
        return idx < 0 ? propertyPath : propertyPath.substring(idx + 1);
    }

    private String rootMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage();
    }
}
