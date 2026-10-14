package com.finsight.auth.security;

/**
 * Distinguishes the two tokens this application issues.
 *
 * <p>The value is written into a custom {@code typ} claim and checked on every
 * verification. Without it, a long-lived refresh token would be accepted as a
 * bearer credential on ordinary API calls — a classic token-confusion bug that
 * turns a 7-day token into a 7-day API key.
 */
public enum TokenType {

    /** Short-lived credential presented in the {@code Authorization} header. */
    ACCESS,

    /** Long-lived credential accepted <em>only</em> by {@code POST /api/v1/auth/refresh}. */
    REFRESH
}
