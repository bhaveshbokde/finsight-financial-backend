package com.finsight.user.domain;

/**
 * Application roles.
 *
 * <p>The {@code ROLE_} prefix is retained in the enum name because Spring
 * Security's {@code hasRole("ADMIN")} expression prepends {@code ROLE_} before
 * comparing against the granted authority. Storing the prefixed form keeps the
 * database value, the {@code GrantedAuthority} and the SpEL expression consistent
 * with no translation step that could silently drift.
 */
public enum Role {

    /** Ordinary account: full control over its own data, nothing else. */
    ROLE_USER,

    /**
     * Operational role. Currently grants access to the detailed Actuator endpoints
     * only. No endpoint lets an admin read another user's transactions — that
     * would be a privacy regression, and the ownership checks in the service layer
     * are not role-conditional.
     */
    ROLE_ADMIN;

    /** The authority string Spring Security compares against. */
    public String authority() {
        return name();
    }

    /** The bare name used by {@code hasRole(...)} expressions. */
    public String shortName() {
        return name().substring("ROLE_".length());
    }
}
