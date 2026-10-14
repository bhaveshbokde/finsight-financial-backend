package com.finsight.common.error;

/**
 * A syntactically valid request that a domain rule rejects — for example a budget
 * month in the past, or an EXPENSE transaction pointed at an INCOME category.
 *
 * <p>Maps to 422 Unprocessable Entity: the server understood the request and the
 * body was well-formed, but the instruction cannot be carried out. That is a
 * different situation from 400 (could not even parse/bind the input), and clients
 * routinely need to tell the two apart.
 */
public class BusinessRuleException extends ApiException {

    public BusinessRuleException(String message) {
        super(ErrorCode.BUSINESS_RULE_VIOLATION, message);
    }

    public BusinessRuleException(ErrorCode code, String message) {
        super(code, message);
    }
}
