package com.finsight.common.error;

/**
 * Thrown when a resource does not exist <em>or</em> exists but is owned by a
 * different user.
 *
 * <p>Deliberately 404 rather than 403 for the cross-user case: returning 403 would
 * confirm that id 42 exists, turning the endpoint into an existence oracle. Every
 * ownership-scoped lookup in this application is written as
 * {@code findByIdAndUserId(...)} so "missing" and "not yours" are indistinguishable
 * to the caller. See docs/07-SECURITY-AND-AUTHENTICATION.md.
 */
public class ResourceNotFoundException extends ApiException {

    public ResourceNotFoundException(String resource, Object id) {
        super(ErrorCode.RESOURCE_NOT_FOUND, "%s %s was not found".formatted(resource, id));
    }

    public ResourceNotFoundException(String message) {
        super(ErrorCode.RESOURCE_NOT_FOUND, message);
    }
}
