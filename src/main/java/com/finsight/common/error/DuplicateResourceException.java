package com.finsight.common.error;

/**
 * Raised when a uniqueness rule would be violated — a second account for the same
 * email, a second category with the same name, a second budget for the same
 * (user, category, month).
 */
public class DuplicateResourceException extends ApiException {

    public DuplicateResourceException(ErrorCode code, String message) {
        super(code, message);
    }

    public DuplicateResourceException(String message) {
        super(ErrorCode.DUPLICATE_RESOURCE, message);
    }
}
