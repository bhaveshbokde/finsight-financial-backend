package com.finsight.auth.security;

import com.finsight.common.error.ApiException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Turns a valid {@code Authorization: Bearer <jwt>} header into an authenticated
 * {@code SecurityContext} for the duration of one request.
 *
 * <p>Extends {@link OncePerRequestFilter} so the work happens exactly once even
 * when the container performs a {@code FORWARD} or {@code ERROR} dispatch — a
 * plain {@code Filter} can run several times per request and would re-parse the
 * token each time.
 *
 * <h2>Failure behaviour</h2>
 * A bad or expired token does <strong>not</strong> abort the chain here. The filter
 * records the reason, leaves the context unauthenticated and continues. The
 * authorization rules in {@link com.finsight.config.SecurityConfig} then decide:
 * a public endpoint still succeeds, a protected one is rejected by
 * {@link RestAuthenticationEntryPoint}, which reads the attribute set below to
 * return the precise code ({@code TOKEN_EXPIRED} vs {@code TOKEN_INVALID}).
 * Keeping rejection in one place is what makes every 401 body identical.
 *
 * <h2>MDC</h2>
 * {@code userId} is pushed onto the logging MDC and removed in a {@code finally}
 * block. Without that removal the value would leak to the next request served by
 * the same pooled thread and mislabel its log lines.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /** Request attribute carrying the rejection reason to the entry point. */
    public static final String ATTR_AUTH_ERROR = "finsight.auth.error";

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String MDC_USER_ID = "userId";

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        String token = extractBearerToken(request);

        if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                UserPrincipal principal = jwtService.verify(token, TokenType.ACCESS);
                authenticate(principal, request);
                MDC.put(MDC_USER_ID, String.valueOf(principal.getId()));
            } catch (ApiException ex) {
                // Stash the reason; do not write a response from inside the filter.
                request.setAttribute(ATTR_AUTH_ERROR, ex);
                SecurityContextHolder.clearContext();
            }
        }

        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_USER_ID);
            // Spring Security's SecurityContextPersistenceFilter normally clears the
            // context, but clearing here too makes thread reuse safe even if this
            // filter is ever used outside the standard chain.
            SecurityContextHolder.clearContext();
        }
    }

    private void authenticate(UserPrincipal principal, HttpServletRequest request) {
        // Credentials are null: the bearer token has already been verified, and
        // holding it in the context would put it in any context dump or heap dump.
        var authentication = new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities());
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
    }

    /** Returns the raw token, or null when the header is absent or not a Bearer header. */
    private String extractBearerToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }

    /**
     * Skips paths that can never carry a bearer token.
     *
     * <p>This is an optimisation, not an authorization decision — these paths are
     * also declared {@code permitAll()} in {@code SecurityConfig}. Relying on a
     * filter's skip list for access control is a well-known way to accidentally
     * expose an endpoint.
     */
    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/api/v1/auth/register")
                || path.startsWith("/api/v1/auth/login")
                || path.startsWith("/actuator/health")
                || path.startsWith("/swagger-ui")
                || path.startsWith("/v3/api-docs");
    }
}
